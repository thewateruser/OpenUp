package org.openup.shell

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku user service. Shizuku starts this class in a separate process that runs as the shell
 * user (or root), which gives OpenUp access to Android/data, Android/obb, the package manager
 * shell commands and the Wi-Fi credential store without the device being rooted.
 *
 * Shizuku instantiates the class through its no-argument constructor.
 */
class ShellService : IShellService.Stub() {

    data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

    override fun destroy() {
        exitProcess(0)
    }

    override fun getUid(): Int = Process.myUid()

    override fun exec(argv: Array<String>?, stdin: ParcelFileDescriptor?): Bundle = guard {
        requireNotNull(argv) { "argv is null" }
        require(argv.isNotEmpty()) { "argv is empty" }
        val r = runCommand(argv, stdin)
        Bundle().apply {
            putInt(KEY_EXIT_CODE, r.exitCode)
            putString(KEY_STDOUT, r.stdout)
            putString(KEY_STDERR, r.stderr)
        }
    }

    override fun tar(dirPath: String?, output: ParcelFileDescriptor?, excludes: Array<String>?): Bundle = guard {
        requireNotNull(output) { "output is null" }
        ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
            val stats = FileOps.tar(File(requireNotNull(dirPath)), stream, excludes?.toSet() ?: emptySet())
            stream.flush()
            Bundle().apply {
                putInt(KEY_FILES, stats.files)
                putInt(KEY_DIRS, stats.dirs)
                putLong(KEY_BYTES, stats.bytes)
                putInt(KEY_SKIPPED, stats.skipped)
                putInt(KEY_SHRUNK, stats.shrunk)
                putStringArray(KEY_SKIPPED_PATHS, stats.skippedPaths.toTypedArray())
            }
        }
    }

    override fun untar(dirPath: String?, input: ParcelFileDescriptor?): Bundle = guard {
        requireNotNull(input) { "input is null" }
        ParcelFileDescriptor.AutoCloseInputStream(input).use { stream ->
            val stats = FileOps.untar(File(requireNotNull(dirPath)), stream)
            Bundle().apply {
                putInt(KEY_FILES, stats.files)
                putInt(KEY_DIRS, stats.dirs)
                putLong(KEY_BYTES, stats.bytes)
                putInt(KEY_SKIPPED, stats.skipped)
                putStringArray(KEY_SKIPPED_PATHS, stats.skippedPaths.toTypedArray())
            }
        }
    }

    override fun sizeOf(path: String?): Long = try {
        FileOps.sizeOf(File(requireNotNull(path)))
    } catch (_: Throwable) {
        -1
    }

    override fun delete(path: String?): Boolean = try {
        val f = File(requireNotNull(path))
        FileOps.deleteRecursive(f.toPath())
        !f.exists()
    } catch (_: Throwable) {
        false
    }

    override fun wifiBackup(): Bundle = guard {
        Bundle().apply { putString(KEY_JSON, WifiOps.backup(Process.myUid())) }
    }

    override fun wifiRestore(json: String?): Bundle = guard {
        val (restored, messages) = WifiOps.restore(Process.myUid(), requireNotNull(json))
        Bundle().apply {
            putInt(KEY_RESTORED, restored)
            putInt(KEY_SKIPPED, messages.size)
            putStringArray(KEY_MESSAGES, messages.toTypedArray())
        }
    }

    private inline fun guard(block: () -> Bundle): Bundle = try {
        block()
    } catch (t: Throwable) {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        Bundle().apply {
            putString(KEY_ERROR, "${cause.javaClass.simpleName}: ${cause.message ?: "(no message)"}")
        }
    }

    companion object {
        const val KEY_ERROR = "error"
        const val KEY_EXIT_CODE = "exitCode"
        const val KEY_STDOUT = "stdout"
        const val KEY_STDERR = "stderr"
        const val KEY_FILES = "files"
        const val KEY_DIRS = "dirs"
        const val KEY_BYTES = "bytes"
        const val KEY_SKIPPED = "skipped"
        const val KEY_SHRUNK = "shrunk"
        const val KEY_SKIPPED_PATHS = "skippedPaths"
        const val KEY_JSON = "json"
        const val KEY_RESTORED = "restored"
        const val KEY_MESSAGES = "messages"

        // Results travel back in a Binder transaction (about 1 MiB shared buffer), so keep them small.
        private const val OUTPUT_CAP = 128 * 1024

        private fun readCapped(input: InputStream): String {
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            input.use {
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    // Keep draining after the cap so the child never blocks on a full pipe.
                    val room = OUTPUT_CAP - bos.size()
                    if (room > 0) bos.write(buf, 0, minOf(n, room))
                }
            }
            return bos.toString(Charsets.UTF_8.name())
        }

        /** Runs [argv] without a shell, feeding [stdin] (if any) and capturing both output streams. */
        fun runCommand(argv: Array<String>, stdin: ParcelFileDescriptor?): CommandResult {
            val process = try {
                ProcessBuilder(*argv).start()
            } catch (t: Throwable) {
                try {
                    stdin?.close()
                } catch (_: Throwable) {
                }
                throw t
            }
            val pump = thread(name = "stdin-pump") {
                try {
                    if (stdin != null) {
                        ParcelFileDescriptor.AutoCloseInputStream(stdin).use { src ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                val n = src.read(buf)
                                if (n < 0) break
                                process.outputStream.write(buf, 0, n)
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // The command stopped reading (it exited); its exit code tells what happened.
                } finally {
                    try {
                        process.outputStream.close()
                    } catch (_: Throwable) {
                    }
                    try {
                        stdin?.close()
                    } catch (_: Throwable) {
                    }
                }
            }
            var stdout = ""
            val outReader = thread(name = "stdout-reader") { stdout = readCapped(process.inputStream) }
            val stderr = readCapped(process.errorStream)
            val code = process.waitFor()
            outReader.join()
            pump.join()
            return CommandResult(code, stdout, stderr)
        }
    }
}
