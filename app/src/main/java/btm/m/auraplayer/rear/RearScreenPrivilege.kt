// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.rear

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import btm.m.auraplayer.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

enum class RearPrivilegeState {
    ROOT,
    SHIZUKU,
    UNAUTHORIZED;

    val isAuthorized: Boolean
        get() = this != UNAUTHORIZED
}

data class ShellExecutionResult(
    val exitCode: Int,
    val output: String,
) {
    val isSuccess: Boolean
        get() = exitCode == 0
}

fun resolvePrivilegeState(hasRoot: Boolean, shizukuAuthorized: Boolean): RearPrivilegeState = when {
    hasRoot -> RearPrivilegeState.ROOT
    shizukuAuthorized -> RearPrivilegeState.SHIZUKU
    else -> RearPrivilegeState.UNAUTHORIZED
}

class RearScreenPrivilegeManager(context: Context) {
    private val appContext = context.applicationContext
    private val stateMutex = Mutex()
    private val binderMutex = Mutex()

    private val _state = MutableStateFlow(readInitialFastState())
    val state: StateFlow<RearPrivilegeState> = _state.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _shizukuBinderAlive = MutableStateFlow(runCatching { Shizuku.pingBinder() }.getOrDefault(false))
    val shizukuBinderAlive: StateFlow<Boolean> = _shizukuBinderAlive.asStateFlow()

    @Volatile
    private var rootCheckedOnce = false

    @Volatile
    private var rootGranted = false

    @Volatile
    private var boundShellBinder: IBinder? = null

    @Volatile
    private var pendingBinderDeferred: CompletableDeferred<IBinder?>? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(appContext, RearScreenShellService::class.java))
            .daemon(false)
            .processNameSuffix("rear_shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            boundShellBinder = service
            pendingBinderDeferred?.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundShellBinder = null
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        _shizukuBinderAlive.value = true
        updateResolvedState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _shizukuBinderAlive.value = false
        boundShellBinder = null
        updateResolvedState()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                updateResolvedState()
            } else {
                updateResolvedState()
            }
        }
    }

    init {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }
    }

    private fun readInitialFastState(): RearPrivilegeState =
        resolvePrivilegeState(hasRoot = rootGranted, shizukuAuthorized = isShizukuAuthorized())

    private fun isShizukuAlive(): Boolean =
        runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)

    private fun isShizukuAuthorized(): Boolean = runCatching {
        isShizukuAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun updateResolvedState() {
        val alive = isShizukuAlive()
        _shizukuBinderAlive.value = alive
        val shizukuOk = alive && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        _state.value = resolvePrivilegeState(hasRoot = rootGranted, shizukuAuthorized = shizukuOk)
    }

    suspend fun refresh(forceRootProbe: Boolean = false): RearPrivilegeState = stateMutex.withLock {
        _checking.value = true
        try {
            if (forceRootProbe || !rootCheckedOnce) {
                rootGranted = withContext(Dispatchers.IO) { probeRootAccess() }
                rootCheckedOnce = true
            }
            updateResolvedState()
            _state.value
        } finally {
            _checking.value = false
        }
    }

    /**
     * Requests Shizuku permission immediately if daemon is alive and unauthorized,
     * otherwise re-probes Root and refreshes Shizuku state.
     */
    suspend fun requestOrRefresh(): PrivilegeActionOutcome {
        if (!rootGranted && isShizukuAlive()) {
            val granted = runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (granted) {
                updateResolvedState()
                return PrivilegeActionOutcome(_state.value, requestedShizukuDialog = false)
            }
            val requested = runCatching {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }.isSuccess
            if (requested) {
                updateResolvedState()
                return PrivilegeActionOutcome(_state.value, requestedShizukuDialog = true)
            }
        }
        val updated = refresh(forceRootProbe = true)
        if (updated.isAuthorized) {
            return PrivilegeActionOutcome(updated, requestedShizukuDialog = false)
        }
        if (isShizukuAlive()) {
            val alreadyGranted = runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (!alreadyGranted) {
                val requested = runCatching {
                    Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                }.isSuccess
                return PrivilegeActionOutcome(updated, requestedShizukuDialog = requested)
            }
        }
        return PrivilegeActionOutcome(updated, requestedShizukuDialog = false)
    }

    suspend fun executeCommand(command: String): ShellExecutionResult {
        val current = refresh(forceRootProbe = false)
        return when (current) {
            RearPrivilegeState.ROOT -> withContext(Dispatchers.IO) { executeViaRoot(command) }
            RearPrivilegeState.SHIZUKU -> executeViaShizuku(command)
            RearPrivilegeState.UNAUTHORIZED -> ShellExecutionResult(-1, "Privilege not granted")
        }
    }

    private fun probeRootAccess(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val finished = process.waitFor(4, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            val out = process.inputStream.bufferedReader().use { it.readText() }
            process.exitValue() == 0 && out.contains("uid=0")
        }
    }.getOrDefault(false)

    private fun executeViaRoot(command: String): ShellExecutionResult = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val finished = process.waitFor(12, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            ShellExecutionResult(-1, "Timed out")
        } else {
            val output = process.inputStream.bufferedReader().use { it.readText() }.take(8192).trim()
            ShellExecutionResult(process.exitValue(), output)
        }
    }.getOrElse { error ->
        ShellExecutionResult(-1, error.message ?: "Root execution failed")
    }

    private suspend fun executeViaShizuku(command: String): ShellExecutionResult {
        val binder = obtainShizukuShellBinder()
            ?: return ShellExecutionResult(-1, "Failed to bind Shizuku UserService")
        return withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(RearScreenShellService.INTERFACE_DESCRIPTOR)
                data.writeString(command)
                binder.transact(RearScreenShellService.TRANSACTION_EXEC, data, reply, 0)
                reply.readException()
                val exitCode = reply.readInt()
                val output = reply.readString().orEmpty()
                ShellExecutionResult(exitCode, output)
            } catch (error: Throwable) {
                boundShellBinder = null
                ShellExecutionResult(-1, error.message ?: "Shizuku transaction failed")
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    private suspend fun obtainShizukuShellBinder(): IBinder? = binderMutex.withLock {
        val existing = boundShellBinder
        if (existing != null && existing.isBinderAlive && existing.pingBinder()) {
            return existing
        }
        boundShellBinder = null
        val deferred = CompletableDeferred<IBinder?>()
        pendingBinderDeferred = deferred
        val bound = runCatching {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        }.isSuccess
        if (!bound) {
            pendingBinderDeferred = null
            return null
        }
        return try {
            withTimeoutOrNull(5000L) { deferred.await() }
        } finally {
            if (pendingBinderDeferred === deferred) pendingBinderDeferred = null
        }
    }

    fun dispose() {
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
        }
    }

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 1718
    }
}

data class PrivilegeActionOutcome(
    val state: RearPrivilegeState,
    val requestedShizukuDialog: Boolean,
)
