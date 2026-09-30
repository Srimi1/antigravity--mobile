package dev.srimi.antigravitymobile

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NativeExecutionService(private val executable: File, private val workspace: File) : ExecutionService {
    private val lock = Mutex()
    @Volatile private var active: Process? = null
    @Volatile private var cancellationRequested = false
    override suspend fun execute(argument: String, onOutput: (String) -> Unit): CommandResult = lock.withLock {
        require(argument in setOf("version", "exit-7", "wait")) { "Command is outside the probe allowlist" }
        withContext(Dispatchers.IO) {
            check(executable.exists() && executable.canExecute()) { "Packaged Android executable is unavailable" }
            val start = System.nanoTime()
            val process = ProcessBuilder(executable.absolutePath, argument).directory(workspace)
                .redirectErrorStream(true).start()
            cancellationRequested = false
            active = process
            try {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { process.destroy() }
                    Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                        val output = StringBuilder()
                        try {
                            process.inputStream.bufferedReader().useLines { lines ->
                                lines.forEach { line ->
                                    if (output.length < 16_384) output.append(line.take(1024)).append('\n')
                                    onOutput(line.take(1024))
                                }
                            }
                            val result = CommandResult(process.waitFor(), output.toString(), (System.nanoTime() - start) / 1_000_000)
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            if (continuation.isActive) {
                                if (cancellationRequested) {
                                    continuation.resume(CommandResult(process.waitFor(), output.toString(), (System.nanoTime() - start) / 1_000_000))
                                } else continuation.resumeWithException(error)
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                    active = null
                }
            }
        }
    }
    override fun cancel() {
        active?.let { process -> cancellationRequested = true; process.destroy() }
    }
}
