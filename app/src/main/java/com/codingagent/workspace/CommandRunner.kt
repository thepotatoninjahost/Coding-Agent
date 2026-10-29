package com.codingagent.workspace

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * ONE JOB: Run a shell command with cancellation and a timeout while preserving complete output.
 * Extracted out of ProjectWorkspace.kt — process execution is unrelated to file-state,
 * transactions, and verification, which is what the rest of that file does.
 */
class CommandRunner(private val directory: File) {
    private val activeProcess = AtomicReference<Process?>(null)
    private val cancellationGeneration = AtomicLong(0L)
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lifecycleLock = Any()

    fun cancel(reason: String = "cancelled") {
        synchronized(lifecycleLock) {
            cancellationGeneration.incrementAndGet()
        }
        activeProcess.getAndSet(null)?.let { terminate(it) }
    }

    fun isCancelled(): Boolean {
        val active = activeRunGeneration
        return active >= 0L && cancellationGeneration.get() != active
    }

    fun isRunning(): Boolean = running.get()

    @Volatile
    private var activeRunGeneration: Long = -1L

    fun run(
        command: List<String>,
        timeoutSeconds: Long,
        onStdout: ((String) -> Unit)? = null,
        onStderr: ((String) -> Unit)? = null
    ): CommandResult {
        require(command.isNotEmpty()) { "Command cannot be empty" }
        synchronized(lifecycleLock) {
            check(running.compareAndSet(false, true)) { "A terminal command is already running" }
            activeRunGeneration = cancellationGeneration.get()
        }

        val runGeneration = activeRunGeneration
        return try {
            val process = ProcessBuilder(command)
                .directory(directory)
                .redirectErrorStream(false)
                .start()
            activeProcess.set(process)

            // Cancellation can race ProcessBuilder.start(). Publish the process first,
            // then immediately honor a generation change that happened during startup.
            if (cancellationGeneration.get() != runGeneration) {
                activeProcess.compareAndSet(process, null)
                terminate(process)
            }

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = Thread { process.inputStream.use { input -> readLimited(input, stdout, onStdout) } }
            val errThread = Thread { process.errorStream.use { input -> readLimited(input, stderr, onStderr) } }
            outThread.start()
            errThread.start()

            var completed = false
            val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
            while (System.nanoTime() < deadline) {
                if (cancellationGeneration.get() != runGeneration) break
                if (!process.isAlive) {
                    completed = true
                    break
                }
                process.waitFor(200, TimeUnit.MILLISECONDS)
            }
            if (!completed && cancellationGeneration.get() == runGeneration && !process.isAlive) {
                completed = true
            }
            if (!completed) {
                terminate(process)
                // A child process may inherit the shell's pipe handles and keep readers
                // blocked after the shell itself has exited. Close our read ends so timeout
                // and cancellation return promptly without dropping already captured text.
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }

            outThread.join(1_000)
            errThread.join(1_000)

            val cancelled = !completed && cancellationGeneration.get() != runGeneration
            val timedOut = !completed && !cancelled
            val exit = when {
                completed -> runCatching { process.exitValue() }.getOrDefault(-1)
                cancelled -> 130
                else -> -1
            }
            val note = if (cancelled) "command cancelled" else ""
            CommandResult(
                command.joinToString(" "),
                exit,
                stdout.toString().trimEnd('\n'),
                (stderr.toString().trimEnd('\n') +
                    if (note.isNotEmpty()) (if (stderr.isNotEmpty()) "\n" else "") + note else "")
                    .trimEnd('\n'),
                timedOut
            )
        } catch (error: Exception) {
            CommandResult(command.joinToString(" "), -1, "", error.message.orEmpty(), false)
        } finally {
            activeProcess.set(null)
            activeRunGeneration = -1L
            running.set(false)
        }
    }

    private fun terminate(process: Process) {
        runCatching {
            process.destroy()
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
    }

    private fun readLimited(input: java.io.InputStream, output: StringBuilder, onChunk: ((String) -> Unit)? = null) {
        val buffer = CharArray(8 * 1024)
        val roomForText = TerminalOutput.STORED_LIMIT - TerminalOutput.TRIM_NOTE.length
        var discarded = false
        input.reader(Charsets.UTF_8).use { reader ->
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                val chunk = String(buffer, 0, count)
                val room = roomForText - output.length
                if (room <= 0) {
                    discarded = true
                    continue
                }
                val piece = if (chunk.length <= room) chunk else chunk.take(room)
                if (piece.length < chunk.length) discarded = true
                output.append(piece)
                onChunk?.invoke(piece)
            }
        }
        if (discarded) {
            output.append(TerminalOutput.TRIM_NOTE)
            onChunk?.invoke(TerminalOutput.TRIM_NOTE)
        }
    }

}
