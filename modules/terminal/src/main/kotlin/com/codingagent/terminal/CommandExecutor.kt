package com.codingagent.terminal

import com.codingagent.domain.CommandResult
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CommandExecutor(private val directory: File) {
    private val process = AtomicReference<Process?>()
    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
        process.getAndSet(null)?.let { child ->
            child.destroy()
            if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly()
        }
    }

    fun isBusy(): Boolean = running.get()

    fun run(command: List<String>, timeoutSeconds: Long = 90, onOutput: ((String) -> Unit)? = null): CommandResult {
        require(command.isNotEmpty()) { "Command cannot be empty" }
        require(timeoutSeconds > 0) { "Command timeout must be positive" }
        cancelled.set(false)
        running.set(true)
        return try {
            val child = ProcessBuilder(command).directory(directory).redirectErrorStream(false).start()
            process.set(child)
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val stdoutThread = Thread { readStream(child.inputStream, stdout, onOutput) }
            val stderrThread = Thread { readStream(child.errorStream, stderr, onOutput) }
            stdoutThread.start()
            stderrThread.start()
            val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
            while (child.isAlive && !cancelled.get() && System.nanoTime() < deadline) {
                child.waitFor(100, TimeUnit.MILLISECONDS)
            }
            val timedOut = child.isAlive && !cancelled.get()
            if (child.isAlive) {
                child.destroy()
                if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly()
            }
            stdoutThread.join(2_000)
            stderrThread.join(2_000)
            val exitCode = when {
                cancelled.get() -> 130
                timedOut -> -1
                else -> child.exitValue()
            }
            CommandResult(command.joinToString(" "), exitCode, stdout.toString().trimEnd(), stderr.toString().trimEnd(), timedOut)
        } catch (error: Exception) {
            CommandResult(command.joinToString(" "), -1, "", error.message.orEmpty(), false)
        } finally {
            process.set(null)
            running.set(false)
        }
    }

    private fun readStream(stream: java.io.InputStream, output: StringBuilder, onOutput: ((String) -> Unit)?) {
        stream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (output.length < 256 * 1024) output.appendLine(line)
                onOutput?.invoke(line)
            }
        }
    }
}
