// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.rear

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import kotlin.system.exitProcess

/**
 * Shizuku UserService binder executing rear-display shell commands under shell/root UID.
 */
class RearScreenShellService() : Binder() {
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            TRANSACTION_DESTROY -> {
                exitProcess(0)
                true
            }
            TRANSACTION_EXEC -> {
                data.enforceInterface(INTERFACE_DESCRIPTOR)
                val command = data.readString().orEmpty()
                val result = runShellCommand(command)
                reply?.apply {
                    writeNoException()
                    writeInt(result.exitCode)
                    writeString(result.output)
                }
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    private fun runShellCommand(command: String): ShellExecutionResult {
        if (command.isBlank()) return ShellExecutionResult(-1, "Empty command")
        return runCatching {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.take(8192)
            val exitCode = process.waitFor()
            ShellExecutionResult(exitCode, output.trim())
        }.getOrElse { error ->
            ShellExecutionResult(-1, error.message ?: "Command failed")
        }
    }

    companion object {
        const val INTERFACE_DESCRIPTOR = "btm.m.auraplayer.rear.IRearShellService"
        const val TRANSACTION_EXEC = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_DESTROY = 16777115
    }
}
