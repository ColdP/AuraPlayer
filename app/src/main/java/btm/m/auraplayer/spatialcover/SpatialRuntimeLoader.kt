// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.spatialcover

import android.content.Context
import java.io.File

/**
 * Loads the optional downloaded ONNX Runtime native libraries (libonnxruntime.so and
 * libonnxruntime4j_jni.so) and initializes ai.onnxruntime.OnnxRuntime before OrtEnvironment
 * runs OnnxRuntime.init().
 */
object SpatialRuntimeLoader {
    private const val ORT_RUNTIME_CLASS = "ai.onnxruntime.OnnxRuntime"
    private const val ORT_API_VERSION = 31
    private const val ORT_TRAINING_API_VERSION = 23

    private val lock = Any()

    @Volatile
    private var initialized = false

    fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val runtimeClass = Class.forName(ORT_RUNTIME_CLASS)
            val loadedField = runtimeClass.getDeclaredField("loaded").apply { isAccessible = true }
            if (loadedField.getBoolean(null)) {
                initialized = true
                return
            }
            val appContext = context.applicationContext
            val runtimeDir = SpatialModelStore.runtimeDirectory(appContext)
            check(runtimeDir != null && SpatialModelStore.isRuntimeReady(appContext)) {
                "请先下载 ONNX Runtime 运行库"
            }
            val coreLib = File(runtimeDir, SpatialModelStore.RUNTIME_CORE_SO)
            val jniLib = File(runtimeDir, SpatialModelStore.RUNTIME_JNI_SO)
            check(coreLib.isFile && jniLib.isFile) {
                "ONNX Runtime 运行库文件不完整，请重新下载"
            }
            System.load(coreLib.absolutePath)
            System.load(jniLib.absolutePath)

            val initApi = runtimeClass.getDeclaredMethod("initialiseAPIBase", Int::class.javaPrimitiveType).apply {
                isAccessible = true
            }
            val ortApiHandle = (initApi.invoke(null, ORT_API_VERSION) as Number).toLong()
            check(ortApiHandle != 0L) {
                "无法初始化 ONNX Runtime 原生接口"
            }

            val initTrainingApi = runtimeClass.getDeclaredMethod(
                "initialiseTrainingAPIBase",
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val trainingHandle = (initTrainingApi.invoke(null, ortApiHandle, ORT_TRAINING_API_VERSION) as Number).toLong()

            val initCompileApi = runtimeClass.getDeclaredMethod(
                "initialiseCompileAPIBase",
                Long::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val compileHandle = (initCompileApi.invoke(null, ortApiHandle) as Number).toLong()

            val initProviders = runtimeClass.getDeclaredMethod(
                "initialiseProviders",
                Long::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val providers = initProviders.invoke(null, ortApiHandle)

            val initVersion = runtimeClass.getDeclaredMethod("initialiseVersion").apply {
                isAccessible = true
            }
            val version = initVersion.invoke(null)

            runtimeClass.getDeclaredField("ortApiHandle").apply { isAccessible = true }.setLong(null, ortApiHandle)
            runtimeClass.getDeclaredField("ortTrainingApiHandle").apply { isAccessible = true }.setLong(null, trainingHandle)
            runtimeClass.getDeclaredField("ortCompileApiHandle").apply { isAccessible = true }.setLong(null, compileHandle)
            runtimeClass.getDeclaredField("trainingEnabled").apply { isAccessible = true }.setBoolean(null, trainingHandle != 0L)
            runtimeClass.getDeclaredField("providers").apply { isAccessible = true }.set(null, providers)
            runtimeClass.getDeclaredField("version").apply { isAccessible = true }.set(null, version)
            loadedField.setBoolean(null, true)
            initialized = true
        }
    }
}
