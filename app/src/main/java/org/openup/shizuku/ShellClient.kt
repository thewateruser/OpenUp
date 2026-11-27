package org.openup.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openup.BuildConfig
import org.openup.shell.IShellService
import org.openup.shell.ShellService
import rikka.shizuku.Shizuku
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class ShellException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val isSuccess get() = exitCode == 0

    /** Combined trimmed output, convenient for error messages. */
    val output get() = (stdout.trim() + "\n" + stderr.trim()).trim()
}

data class TransferStats(
    val files: Int,
    val dirs: Int,
    val bytes: Long,
    val skipped: Int,
    val shrunk: Int,
    val skippedPaths: List<String>,
) {
    companion object {
        fun from(b: Bundle) = TransferStats(
            files = b.getInt(ShellService.KEY_FILES),
            dirs = b.getInt(ShellService.KEY_DIRS),
            bytes = b.getLong(ShellService.KEY_BYTES),
            skipped = b.getInt(ShellService.KEY_SKIPPED),
            shrunk = b.getInt(ShellService.KEY_SHRUNK),
            skippedPaths = b.getStringArray(ShellService.KEY_SKIPPED_PATHS)?.toList() ?: emptyList(),
        )
    }
}

/**
 * App side of the privileged shell service. Binds the Shizuku user service on demand and moves
 * data to/from it through pipes, so large archives never have to fit in memory or in a temp file.
 */
class ShellClient(private val shizuku: ShizukuManager, private val packageName: String) {
    private val mutex = Mutex()

    @Volatile
    private var service: IShellService? = null
    private var connection: ServiceConnection? = null

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private fun alive(s: IShellService?): IShellService? =
        s?.takeIf { it.asBinder().isBinderAlive && it.asBinder().pingBinder() }

    suspend fun service(): IShellService {
        alive(service)?.let { return it }
        return mutex.withLock {
            alive(service)?.let { return@withLock it }
            shizuku.requireReady()
            val deferred = CompletableDeferred<IShellService>()
            withContext(Dispatchers.Main) {
                connection?.let { old ->
                    try {
                        Shizuku.unbindUserService(serviceArgs, old, false)
                    } catch (_: Throwable) {
                    }
                }
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder != null && binder.pingBinder()) {
                            val s = IShellService.Stub.asInterface(binder)
                            service = s
                            deferred.complete(s)
                        } else {
                            deferred.completeExceptionally(ShizukuUnavailableException("Shizuku returned an invalid service"))
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        service = null
                    }
                }
                connection = conn
                try {
                    Shizuku.bindUserService(serviceArgs, conn)
                } catch (t: Throwable) {
                    deferred.completeExceptionally(ShizukuUnavailableException("Could not start the Shizuku service: ${t.message}"))
                }
            }
            withTimeoutOrNull(30_000) { deferred.await() }
                ?: throw ShizukuUnavailableException("Timed out waiting for the Shizuku service to start")
        }
    }

    suspend fun uid(): Int = withContext(Dispatchers.IO) { call { it.uid } }

    private suspend fun <T> call(block: (IShellService) -> T): T {
        val s = service()
        return try {
            block(s)
        } catch (e: RemoteException) {
            service = null
            throw ShellException("Lost connection to the Shizuku service", e)
        }
    }

    private fun Bundle.throwIfError(): Bundle {
        getString(ShellService.KEY_ERROR)?.let { throw ShellException(it) }
        return this
    }

    private fun remoteFailure(e: RemoteException): Bundle {
        service = null
        return Bundle().apply { putString(ShellService.KEY_ERROR, "Lost connection to the Shizuku service (${e.javaClass.simpleName})") }
    }

    /** Runs [argv] with shell privileges. [stdin] (optional) is streamed to the command. */
    suspend fun exec(vararg argv: String, stdin: ((OutputStream) -> Unit)? = null): ExecResult =
        withContext(Dispatchers.IO) {
            val s = service()
            val bundle = if (stdin == null) {
                try {
                    s.exec(arrayOf(*argv), null)
                } catch (e: RemoteException) {
                    remoteFailure(e)
                }
            } else {
                coroutineScope {
                    val pipe = ParcelFileDescriptor.createPipe()
                    val readSide = pipe[0]
                    val writeSide = pipe[1]
                    val callResult = async(Dispatchers.IO) {
                        try {
                            s.exec(arrayOf(*argv), readSide)
                        } catch (e: RemoteException) {
                            remoteFailure(e)
                        } finally {
                            readSide.close()
                        }
                    }
                    val producerError = runCatching {
                        ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { stdin(it) }
                    }.exceptionOrNull()
                    val result = callResult.await()
                    // A broken pipe only means the command stopped reading; its exit code and output
                    // explain why. Any other producer failure (e.g. wrong password) is the real error.
                    if (producerError != null && !isBrokenPipe(producerError)) throw producerError
                    result
                }
            }
            bundle.throwIfError()
            ExecResult(
                bundle.getInt(ShellService.KEY_EXIT_CODE, -1),
                bundle.getString(ShellService.KEY_STDOUT).orEmpty(),
                bundle.getString(ShellService.KEY_STDERR).orEmpty(),
            )
        }

    /** Like [exec] but throws a [ShellException] containing the output if the exit code is not 0. */
    suspend fun execOrThrow(vararg argv: String, stdin: ((OutputStream) -> Unit)? = null): ExecResult {
        val r = exec(*argv, stdin = stdin)
        if (!r.isSuccess) throw ShellException("${argv.take(3).joinToString(" ")} failed (${r.exitCode}): ${r.output.take(500)}")
        return r
    }

    /**
     * Streams a tar archive of [dirPath] to [consumer], which must read it to the end.
     * Returns the consumer's result together with transfer statistics.
     */
    suspend fun <T> tar(dirPath: String, excludes: List<String>, consumer: (InputStream) -> T): Pair<T, TransferStats> =
        withContext(Dispatchers.IO) {
            val s = service()
            coroutineScope {
                val pipe = ParcelFileDescriptor.createPipe()
                val readSide = pipe[0]
                val writeSide = pipe[1]
                val callResult = async(Dispatchers.IO) {
                    try {
                        s.tar(dirPath, writeSide, excludes.toTypedArray())
                    } catch (e: RemoteException) {
                        remoteFailure(e)
                    } finally {
                        writeSide.close()
                    }
                }
                val outcome = runCatching {
                    ParcelFileDescriptor.AutoCloseInputStream(readSide).use { consumer(it) }
                }
                val result = callResult.await()
                val serviceError = result.getString(ShellService.KEY_ERROR)
                outcome.exceptionOrNull()?.let { e ->
                    if (serviceError != null && isBrokenPipe(e)) throw ShellException(serviceError, e)
                    throw e
                }
                if (serviceError != null) throw ShellException(serviceError)
                outcome.getOrThrow() to TransferStats.from(result)
            }
        }

    /**
     * Restores the tar archive written by [producer] into [dirPath]. The contents of [dirPath] are
     * replaced, or with [merge] only overwritten where the archive has the same files.
     */
    suspend fun untar(dirPath: String, merge: Boolean = false, producer: (OutputStream) -> Unit): TransferStats =
        withContext(Dispatchers.IO) {
            val s = service()
            coroutineScope {
                // A reliable pipe carries a close status: the shell process only replaces existing
                // data if this side closed it without an error.
                val pipe = ParcelFileDescriptor.createReliablePipe()
                val readSide = pipe[0]
                val writeSide = pipe[1]
                val callResult = async(Dispatchers.IO) {
                    try {
                        if (merge) s.untarMerge(dirPath, readSide) else s.untar(dirPath, readSide)
                    } catch (e: RemoteException) {
                        remoteFailure(e)
                    } finally {
                        readSide.close()
                    }
                }
                val producerError = runCatching {
                    // Not closed by itself: the descriptor is closed below, with or without an error.
                    val out = FileOutputStream(writeSide.fileDescriptor)
                    producer(out)
                    out.flush()
                }.exceptionOrNull()
                try {
                    if (producerError == null) {
                        writeSide.close()
                    } else {
                        writeSide.closeWithError(producerError.message ?: producerError.javaClass.simpleName)
                    }
                } catch (_: IOException) {
                }
                val result = callResult.await()
                val serviceError = result.getString(ShellService.KEY_ERROR)
                if (producerError != null) {
                    if (serviceError != null && isBrokenPipe(producerError)) throw ShellException(serviceError, producerError)
                    throw producerError
                }
                if (serviceError != null) throw ShellException(serviceError)
                TransferStats.from(result)
            }
        }

    /** Size of [path] in bytes, or -1 if it does not exist. Throws if it cannot be read. */
    suspend fun sizeOf(path: String): Long = withContext(Dispatchers.IO) {
        val size = call { it.sizeOf(path) }
        if (size == ShellService.SIZE_ERROR) throw ShellException("Cannot read $path")
        size
    }

    /** Sub-directory names of [path], or null if it cannot be read. */
    suspend fun listDirectories(path: String): List<String>? = withContext(Dispatchers.IO) {
        call { it.listDirectories(path) }?.toList()
    }

    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) { call { it.delete(path) } }

    suspend fun wifiBackup(): String = withContext(Dispatchers.IO) {
        call { it.wifiBackup() }.throwIfError().getString(ShellService.KEY_JSON)
            ?: throw ShellException("No Wi-Fi data returned")
    }

    /** Returns the number of restored networks and messages about networks that were skipped. */
    suspend fun wifiRestore(json: String): Pair<Int, List<String>> = withContext(Dispatchers.IO) {
        val b = call { it.wifiRestore(json) }.throwIfError()
        b.getInt(ShellService.KEY_RESTORED) to (b.getStringArray(ShellService.KEY_MESSAGES)?.toList() ?: emptyList())
    }

    companion object {
        fun isBrokenPipe(t: Throwable): Boolean = generateSequence(t) { it.cause }.any {
            (it is ErrnoException && it.errno == OsConstants.EPIPE) ||
                (it is IOException && it.message?.contains("EPIPE") == true) ||
                (it is IOException && it.message?.contains("Broken pipe", ignoreCase = true) == true)
        }
    }
}
