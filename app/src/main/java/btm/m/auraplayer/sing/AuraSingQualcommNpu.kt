package btm.m.auraplayer.sing

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtHardwareDevice
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.RunOptions
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.util.Log
import btm.m.auraplayer.spatialcover.SpatialModelResource
import btm.m.auraplayer.spatialcover.SpatialModelStore
import btm.m.auraplayer.spatialcover.SpatialRuntimeLoader
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Hardware detection, on-demand QNN NPU/GPU runtime management, and Execution Provider
 * registration targeting Qualcomm Snapdragon 8 Elite (SM8750 / HTP v79) and above (v81+).
 */
object AuraSingQualcommNpu {
    private const val TAG = "AuraSingQnn"
    const val QNN_RESOURCE_ID = "aura_sing_qnn_npu_runtime"
    const val QNN_SUBDIR = "qnn_runtime/arm64-v8a"
    const val QNN_EP_REGISTRATION_NAME = "QNNExecutionProvider"
    const val QNN_EP_LIB = "libonnxruntime_providers_qnn.so"
    const val QNN_HTP_LIB = "libQnnHtp.so"
    const val QNN_GPU_LIB = "libQnnGpu.so"

    val QNN_REQUIRED_LIBS = setOf(
        QNN_EP_LIB,
        QNN_HTP_LIB,
        "libQnnHtpPrepare.so",
        "libQnnSystem.so",
        "libQnnHtpV79Stub.so",
        "libQnnHtpV79Skel.so",
        "libQnnHtpV81Stub.so",
        "libQnnHtpV81Skel.so",
        QNN_GPU_LIB,
    )

    private val qnnLock = Any()

    @Volatile
    private var epRegistered = false

    fun runtimeResource(): SpatialModelResource? {
        if (SpatialModelStore.runtimeAbi() == null) return null
        return SpatialModelResource(
            id = QNN_RESOURCE_ID,
            fileName = "qnn-runtime-2.51.0-sd8elite-arm64-v8a.zip",
            url = "https://coldp.github.io/hyperchanger-updateinfo/runtime/qnn-runtime-2.51.0-sd8elite-arm64-v8a.zip",
            sizeBytes = 55_673_652L,
            sha256 = "ed0561cd11e5c074f417ae957c07e3c36abe0f9afb6828be059ba2aea2b9646f",
            subDir = QNN_SUBDIR,
            isArchive = true,
            archiveRequiredFiles = QNN_REQUIRED_LIBS,
        )
    }

    fun isReady(context: Context): Boolean {
        val res = runtimeResource() ?: return false
        return SpatialModelStore.isReady(context.applicationContext, res)
    }

    fun directory(context: Context): File? {
        val res = runtimeResource() ?: return null
        return SpatialModelStore.directory(context.applicationContext, res.subDir)
    }

    fun socModelName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty() else ""

    fun socManufacturerName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER.orEmpty() else ""

    fun isQualcommSoC(
        manufacturer: String = socManufacturerName(),
        socModel: String = socModelName(),
        hardware: String = Build.HARDWARE.orEmpty(),
        board: String = Build.BOARD.orEmpty(),
    ): Boolean {
        val mfg = manufacturer.lowercase()
        val soc = socModel.lowercase()
        val hw = hardware.lowercase()
        val bd = board.lowercase()
        return "qti" in mfg ||
            "qualcomm" in mfg ||
            soc.startsWith("sm") ||
            soc.startsWith("qcs") ||
            "qcom" in hw ||
            bd in setOf("sun", "canoe", "pineapple", "kalama", "taro")
    }

    fun isSnapdragon8EliteOrAbove(
        socModel: String = socModelName(),
        hardware: String = Build.HARDWARE.orEmpty(),
        board: String = Build.BOARD.orEmpty(),
    ): Boolean {
        val normalized = socModel.trim().uppercase()
        if (normalized.isNotEmpty()) {
            // SM8750 (8 Elite), SM8735 (8s Gen 4 / same v79 gen), SM88xx (8 Elite Gen 5), SM89xx+
            val smMatch = Regex("SM(\\d{4})").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (smMatch != null && smMatch >= 8735) return true
            if ("ELITE" in normalized || "ORYON" in normalized) return true
        }
        val bd = board.lowercase()
        val hw = hardware.lowercase()
        return bd in setOf("sun", "canoe") || hw in setOf("sun", "canoe")
    }

    /**
     * Loads the ONNX Runtime base engine, configures `ADSP_LIBRARY_PATH` / `LD_LIBRARY_PATH`,
     * and registers the downloadable Qualcomm QNN Execution Provider plugin into [OrtEnvironment].
     * Returns the QNN runtime directory when active, or null if not downloaded or unsupported.
     */
    fun ensureQnnRegistered(context: Context): File? {
        val appContext = context.applicationContext
        if (!SpatialModelStore.isRuntimeReady(appContext) || !isReady(appContext)) return null
        val qnnDir = directory(appContext) ?: return null
        if (epRegistered) return qnnDir
        synchronized(qnnLock) {
            if (epRegistered) return qnnDir
            return runCatching {
                SpatialRuntimeLoader.ensureLoaded(appContext)
                val adspPaths = "${qnnDir.absolutePath};/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp"
                runCatching { Os.setenv("ADSP_LIBRARY_PATH", adspPaths, true) }
                val existingLd = runCatching { Os.getenv("LD_LIBRARY_PATH") }.getOrNull().orEmpty()
                val nextLd = if (existingLd.isBlank()) qnnDir.absolutePath else "${qnnDir.absolutePath}:$existingLd"
                runCatching { Os.setenv("LD_LIBRARY_PATH", nextLd, true) }

                runCatching {
                    val sysLib = File(qnnDir, "libQnnSystem.so")
                    if (sysLib.isFile) System.load(sysLib.absolutePath)
                }

                val epLib = File(qnnDir, QNN_EP_LIB)
                check(epLib.isFile) { "缺少 $QNN_EP_LIB" }
                val env = OrtEnvironment.getEnvironment()
                env.registerExecutionProviderLibrary(QNN_EP_REGISTRATION_NAME, epLib.absolutePath)
                epRegistered = true
                Log.i(TAG, "Registered Qualcomm QNN EP from ${epLib.absolutePath} (SoC=${socModelName()})")
                qnnDir
            }.onFailure { err ->
                Log.w(TAG, "Failed to register QNN EP, falling back to XNNPACK/CPU", err)
            }.getOrNull()
        }
    }

    class AcceleratedSessionHandle(
        val session: OrtSession,
        val backendLabel: String,
        val runOptions: RunOptions? = null,
    ) : AutoCloseable {
        override fun close() {
            kotlin.runCatching { runOptions?.close() }
            kotlin.runCatching { session.close() }
        }
    }

    private const val PREFS_BENCH = "aura.ort.accel_bench.v2"
    private const val BACKEND_QNN_HTP_STRICT = "QNN_HTP_STRICT"
    private const val BACKEND_QNN_GPU = "QNN_GPU"
    private const val BACKEND_XNNPACK = "XNNPACK"
    private const val BACKEND_CPU = "CPU"
    private const val FAST_ENOUGH_MS = 250L

    /**
     * Shared hardware-accelerated ORT session builder for both Aura Sing and Spatial Cover.
     * Prevents silent slow HTP+1-thread CPU fallback via `session.disable_cpu_ep_fallback=1`
     * on NPU/GPU, and automatically benchmarks candidates on first run per `(cacheTag, SoC)` to
     * pick and cache the fastest execution provider (QNN HTP NPU vs Adreno GPU vs XNNPACK Oryon).
     */
    fun createAcceleratedSession(
        context: Context,
        modelPath: String,
        cacheTag: String,
        isInt8Quantized: Boolean,
        preferQnn: Boolean = true,
        useXnnpack: Boolean = true,
        qnnCacheSubdir: String = "ort_qnn_cache",
    ): AcceleratedSessionHandle {
        val appContext = context.applicationContext
        SpatialRuntimeLoader.ensureLoaded(appContext)
        val env = OrtEnvironment.getEnvironment()
        val isElitePlus = isSnapdragon8EliteOrAbove()
        val cores = Runtime.getRuntime().availableProcessors()
        val fastThreads = if (isElitePlus) cores.coerceIn(6, 8) else cores.coerceIn(4, 6)
        val socTag = socModelName().ifBlank { Build.HARDWARE.orEmpty().ifBlank { "arm64" } }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")

        val qnnDir = if (preferQnn) ensureQnnRegistered(appContext) else null
        val benchPrefs = appContext.getSharedPreferences(PREFS_BENCH, Context.MODE_PRIVATE)
        val benchKey = "${cacheTag}|${socTag}|${if (qnnDir != null) 1 else 0}|${if (useXnnpack) 1 else 0}|${if (isInt8Quantized) 1 else 0}"
        val cachedWinner = benchPrefs.getString(benchKey, null)

        val allEpDevices = if (qnnDir != null) runCatching { env.epDevices }.getOrDefault(emptyList()) else emptyList()
        val qnnEpDevices = allEpDevices.filter { it.epName == QNN_EP_REGISTRATION_NAME }
        val npuDevices = qnnEpDevices.filter { it.device.type == OrtHardwareDevice.OrtHardwareDeviceType.NPU }
        val gpuDevices = qnnEpDevices.filter { it.device.type == OrtHardwareDevice.OrtHardwareDeviceType.GPU }

        fun openCandidate(tag: String): AcceleratedSessionHandle? = when (tag) {
            BACKEND_QNN_HTP_STRICT -> if (qnnDir != null && npuDevices.isNotEmpty()) {
                tryCreateQnnHtpSession(
                    appContext = appContext,
                    env = env,
                    modelPath = modelPath,
                    cacheTag = cacheTag,
                    qnnCacheSubdir = qnnCacheSubdir,
                    qnnDir = qnnDir,
                    npuDevices = npuDevices,
                    isElitePlus = isElitePlus,
                    fastThreads = fastThreads,
                )
            } else null
            BACKEND_QNN_GPU -> if (qnnDir != null && gpuDevices.isNotEmpty()) {
                tryCreateQnnGpuSession(env, modelPath, qnnDir, gpuDevices, fastThreads)
            } else null
            BACKEND_XNNPACK -> if (useXnnpack && !isInt8Quantized) {
                tryCreateXnnpackSession(env, modelPath, isElitePlus, fastThreads)
            } else null
            else -> createCpuSession(env, modelPath, isElitePlus, fastThreads)
        }

        // Fast path: reuse previously benchmarked fastest backend for this model + SoC.
        if (cachedWinner != null) {
            openCandidate(cachedWinner)?.let { return it }
            benchPrefs.edit().remove(benchKey).apply()
        }

        // First-run probe: measure candidate latency cleanly (only 1 session alive at a time).
        val candidates = buildList {
            if (qnnDir != null && npuDevices.isNotEmpty()) add(BACKEND_QNN_HTP_STRICT)
            if (qnnDir != null && gpuDevices.isNotEmpty()) add(BACKEND_QNN_GPU)
            if (useXnnpack && !isInt8Quantized) add(BACKEND_XNNPACK)
            add(BACKEND_CPU)
        }

        if (candidates.size == 1) {
            val onlyTag = candidates[0]
            val handle = openCandidate(onlyTag) ?: createCpuSession(env, modelPath, isElitePlus, fastThreads)
            benchPrefs.edit().putString(benchKey, onlyTag).apply()
            return handle
        }

        var bestTag: String? = null
        var bestHandle: AcceleratedSessionHandle? = null
        var bestMs = Long.MAX_VALUE

        for (tag in candidates) {
            val candidate = openCandidate(tag) ?: continue
            val ms = measureSingleInferenceMs(env, candidate)
            Log.i(TAG, "Backend benchmark [$cacheTag / $tag / ${candidate.backendLabel}]: ${ms}ms")
            if (ms < bestMs) {
                bestHandle?.close()
                bestHandle = candidate
                bestMs = ms
                bestTag = tag
                if (ms <= FAST_ENOUGH_MS && (tag == BACKEND_QNN_HTP_STRICT || tag == BACKEND_QNN_GPU)) {
                    break
                }
            } else {
                candidate.close()
            }
        }

        val finalTag = bestTag ?: BACKEND_CPU
        val finalHandle = bestHandle ?: createCpuSession(env, modelPath, isElitePlus, fastThreads)
        benchPrefs.edit().putString(benchKey, finalTag).apply()
        return finalHandle
    }

    private fun measureSingleInferenceMs(env: OrtEnvironment, handle: AcceleratedSessionHandle): Long {
        return runCatching {
            val firstInput = handle.session.inputInfo.entries.firstOrNull() ?: return@runCatching Long.MAX_VALUE
            val tInfo = firstInput.value.info as? TensorInfo ?: return@runCatching Long.MAX_VALUE
            val concreteShape = LongArray(tInfo.shape.size) { i ->
                val d = tInfo.shape[i]
                if (d > 0L) d else 1L
            }
            val totalElementsLong = concreteShape.fold(1L) { acc, dim -> acc * dim }
            if (totalElementsLong <= 0L || totalElementsLong > 16_000_000L) return@runCatching Long.MAX_VALUE
            val elemCount = totalElementsLong.toInt()
            val tensor = when (tInfo.type) {
                OnnxJavaType.UINT8 -> OnnxTensor.createTensor(
                    env,
                    ByteBuffer.allocate(elemCount),
                    concreteShape,
                    OnnxJavaType.UINT8,
                )
                OnnxJavaType.FLOAT -> OnnxTensor.createTensor(
                    env,
                    FloatBuffer.allocate(elemCount),
                    concreteShape,
                )
                else -> return@runCatching Long.MAX_VALUE
            }
            tensor.use { t ->
                val t0 = SystemClock.elapsedRealtime()
                val res = handle.runOptions?.let { handle.session.run(mapOf(firstInput.key to t), it) }
                    ?: handle.session.run(mapOf(firstInput.key to t))
                res.close()
                val firstRunMs = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
                // If fast enough, do one steady-state run so JIT/graph warmup doesn't skew winner.
                if (firstRunMs > 1200L) return@use firstRunMs
                val t1 = SystemClock.elapsedRealtime()
                val res2 = handle.runOptions?.let { handle.session.run(mapOf(firstInput.key to t), it) }
                    ?: handle.session.run(mapOf(firstInput.key to t))
                res2.close()
                (SystemClock.elapsedRealtime() - t1).coerceAtLeast(1L)
            }
        }.getOrDefault(Long.MAX_VALUE)
    }

    private fun tryCreateXnnpackSession(
        env: OrtEnvironment,
        modelPath: String,
        isElitePlus: Boolean,
        fastThreads: Int,
    ): AcceleratedSessionHandle? {
        val xnnSession = runCatching {
            val options = OrtSession.SessionOptions().apply {
                setCPUArenaAllocator(true)
                setMemoryPatternOptimization(true)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
                addConfigEntry("session.intra_op.allow_spinning", "0")
                addXnnpack(mapOf("intra_op_num_threads" to fastThreads.toString()))
            }
            try {
                env.createSession(modelPath, options)
            } finally {
                options.close()
            }
        }.getOrNull() ?: return null
        val label = if (isElitePlus) "XNNPACK (Oryon ${fastThreads}T)" else "XNNPACK (${fastThreads}T)"
        return AcceleratedSessionHandle(xnnSession, label)
    }

    private fun createCpuSession(
        env: OrtEnvironment,
        modelPath: String,
        isElitePlus: Boolean,
        fastThreads: Int,
    ): AcceleratedSessionHandle {
        val cpuOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(fastThreads)
            setInterOpNumThreads(1)
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            addConfigEntry("session.intra_op.allow_spinning", "1")
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val cpuSession = try {
            env.createSession(modelPath, cpuOptions)
        } finally {
            cpuOptions.close()
        }
        val label = if (isElitePlus) "Oryon CPU (${fastThreads}T)" else "CPU (${fastThreads}T)"
        return AcceleratedSessionHandle(cpuSession, label)
    }

    private fun tryCreateQnnHtpSession(
        appContext: Context,
        env: OrtEnvironment,
        modelPath: String,
        cacheTag: String,
        qnnCacheSubdir: String,
        qnnDir: File,
        npuDevices: List<ai.onnxruntime.OrtEpDevice>,
        isElitePlus: Boolean,
        fastThreads: Int,
    ): AcceleratedSessionHandle? {
        val ctxDir = File(appContext.filesDir, qnnCacheSubdir).apply { mkdirs() }
        val socTag = socModelName().ifBlank { "arm64" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val ctxModelFile = File(ctxDir, "${cacheTag}_${socTag}_v2_ctx.onnx")

        fun buildHtpOptions(enableContextCache: Boolean, outCtxPath: String): OrtSession.SessionOptions {
            val htpLib = File(qnnDir, QNN_HTP_LIB).absolutePath
            val epOpts = buildMap {
                put("backend_path", htpLib)
                put("htp_performance_mode", "burst")
                // Mode 1 balances compile speed (~seconds vs minutes) with HTP execution speed.
                put("htp_graph_finalization_optimization_mode", "1")
                put("rpc_control_latency", "100")
                put("enable_htp_fp16_precision", "1")
                put("enable_htp_shared_memory_allocator", "1")
                put("vtcm_mb", "8")
                put("extended_udma", "1")
                if (isElitePlus) {
                    val socUpper = socModelName().trim().uppercase()
                    if (socUpper.startsWith("SM88")) put("htp_arch", "81")
                    else if (socUpper.startsWith("SM87")) put("htp_arch", "79")
                }
            }
            return OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                setIntraOpNumThreads(fastThreads)
                setInterOpNumThreads(1)
                // Strictly require QNN HTP execution without silent slow host fallback.
                addConfigEntry("session.disable_cpu_ep_fallback", "1")
                if (enableContextCache) {
                    addConfigEntry("ep.context_enable", "1")
                    addConfigEntry("ep.context_embed_mode", "0")
                    addConfigEntry("ep.context_file_path", outCtxPath)
                }
                addExecutionProvider(npuDevices, epOpts)
            }
        }

        if (ctxModelFile.isFile && ctxModelFile.length() > 0L) {
            val cachedSession = runCatching {
                val opts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    addExecutionProvider(
                        npuDevices,
                        mapOf(
                            "backend_path" to File(qnnDir, QNN_HTP_LIB).absolutePath,
                            "htp_performance_mode" to "burst",
                            "rpc_control_latency" to "100",
                            "enable_htp_shared_memory_allocator" to "1",
                            "vtcm_mb" to "8",
                        ),
                    )
                }
                try {
                    env.createSession(ctxModelFile.absolutePath, opts)
                } finally {
                    opts.close()
                }
            }.onFailure { err ->
                Log.w(TAG, "Cached QNN context load failed, recompiling: ${err.message}")
                ctxDir.listFiles()?.filter { it.name.startsWith("${cacheTag}_$socTag") }?.forEach { it.delete() }
            }.getOrNull()
            if (cachedSession != null) {
                val rOpts = runCatching {
                    RunOptions().apply {
                        addRunConfigEntry("qnn.perf_mode", "burst")
                        addRunConfigEntry("qnn.rpc_control_latency", "100")
                    }
                }.getOrNull()
                return AcceleratedSessionHandle(cachedSession, "NPU (QNN HTP)", rOpts)
            }
        }

        val freshSession = runCatching {
            val opts = buildHtpOptions(enableContextCache = true, outCtxPath = ctxModelFile.absolutePath)
            try {
                env.createSession(modelPath, opts)
            } finally {
                opts.close()
            }
        }.getOrElse { firstErr ->
            Log.w(TAG, "QNN HTP strict context-cache init failed (${firstErr.message}), retrying plain HTP")
            runCatching {
                val opts = buildHtpOptions(enableContextCache = false, outCtxPath = "")
                try {
                    env.createSession(modelPath, opts)
                } finally {
                    opts.close()
                }
            }.onFailure { err ->
                Log.w(TAG, "QNN HTP strict init rejected: ${err.message}")
            }.getOrNull()
        } ?: return null

        val rOpts = runCatching {
            RunOptions().apply {
                addRunConfigEntry("qnn.perf_mode", "burst")
                addRunConfigEntry("qnn.rpc_control_latency", "100")
            }
        }.getOrNull()
        return AcceleratedSessionHandle(freshSession, "NPU (QNN HTP)", rOpts)
    }

    private fun tryCreateQnnGpuSession(
        env: OrtEnvironment,
        modelPath: String,
        qnnDir: File,
        gpuDevices: List<ai.onnxruntime.OrtEpDevice>,
        fastThreads: Int,
    ): AcceleratedSessionHandle? {
        val gpuSession = runCatching {
            val gpuLib = File(qnnDir, QNN_GPU_LIB).absolutePath
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                setIntraOpNumThreads(fastThreads)
                setInterOpNumThreads(1)
                addConfigEntry("session.disable_cpu_ep_fallback", "1")
                addExecutionProvider(gpuDevices, mapOf("backend_path" to gpuLib, "precision" to "fp16"))
            }
            try {
                env.createSession(modelPath, opts)
            } finally {
                opts.close()
            }
        }.onFailure { err ->
            Log.w(TAG, "QNN GPU init rejected: ${err.message}")
        }.getOrNull() ?: return null
        return AcceleratedSessionHandle(gpuSession, "Adreno GPU (QNN)")
    }
}
