package com.codingagent.workspace

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ONE JOB: Run a shell command with cancellation, a timeout, and bounded output capture.
 * Extracted out of ProjectWorkspace.kt — process execution is unrelated to file-state,
 * transactions, and verification, which is what the rest of that file does.
 */
class CommandRunner(private val directory: File) {
    private val activeProcess = java.util.concurrent.atomic.AtomicReference<Process?>(null)
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    fun cancel(reason: String = "cancelled") {
        cancelled.set(true)
        activeProcess.getAndSet(null)?.let { process ->
            runCatching {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
    }

    fun isCancelled(): Boolean = cancelled.get()

    fun isRunning(): Boolean = activeProcess.get()?.isAlive == true

    fun run(
        command: List<String>,
        timeoutSeconds: Long,
        onStdout: ((String) -> Unit)? = null,
        onStderr: ((String) -> Unit)? = null
    ): CommandResult {
        require(command.isNotEmpty()) { "Command cannot be empty" }
        cancelled.set(false)
        return try {
            val process = ProcessBuilder(command).directory(directory).redirectErrorStream(false).start()
            activeProcess.set(process)
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = Thread { process.inputStream.use { input -> readLimited(input, stdout, onStdout) } }
            val errThread = Thread { process.errorStream.use { input -> readLimited(input, stderr, onStderr) } }
            outThread.start()
            errThread.start()
            var completed = false
            val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
            while (System.nanoTime() < deadline) {
                if (cancelled.get()) break
                if (!process.isAlive) { completed = true; break }
                process.waitFor(200, TimeUnit.MILLISECONDS)
            }
            if (!completed) {
                if (!cancelled.get() && !process.isAlive) completed = true
            }
            if (!completed) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            outThread.join(2_000)
            errThread.join(2_000)
            val timedOut = !completed && !cancelled.get()
            val exit = when {
                completed -> runCatching { process.exitValue() }.getOrDefault(-1)
                cancelled.get() -> 130
                else -> -1
            }
            val note = when {
                cancelled.get() && stderr.isEmpty() -> "command cancelled"
                else -> ""
            }
            CommandResult(
                command.joinToString(" "),
                exit,
                stdout.toString().trimEnd('\n'),
                (stderr.toString().trimEnd('\n') + if (note.isNotEmpty()) (if (stderr.isNotEmpty()) "\n" else "") + note else "").trimEnd('\n'),
                timedOut
            )
        } catch (error: Exception) {
            CommandResult(command.joinToString(" "), -1, "", error.message.orEmpty(), false)
        } finally {
            activeProcess.set(null)
        }
    }

    private fun readLimited(input: java.io.InputStream, output: StringBuilder, onChunk: ((String) -> Unit)? = null) {
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            val chunk = String(buffer, 0, count, Charsets.UTF_8)
            appendLimited(output, chunk)
            onChunk?.invoke(chunk)
        }
    }

    private fun appendLimited(output: StringBuilder, value: String) {
        if (output.length >= 256 * 1024) return
        output.append(value.take(256 * 1024 - output.length))
    }
}
