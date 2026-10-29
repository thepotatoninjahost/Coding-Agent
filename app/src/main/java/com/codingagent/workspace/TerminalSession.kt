package com.codingagent.workspace

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ONE JOB: Timed shell execution in the project root with cancellation, history, and live streaming.
 *
 * Runs ProcessBuilder([resolvedSh, "-c", command]) with cwd = imported project root.
 * There is no pseudo-terminal: interactive programs (vim, less, ssh password prompts) will not work.
 * The resolved binary is stock Android /system/bin/sh when present, otherwise `sh` on PATH.
 * Default timeout is 180 seconds. The command that is running keeps its full output.
 * Older history keeps the first 256 KB and says when the rest was trimmed.
 */
class TerminalSession(
    private val root: File,
    private val timeoutSeconds: Long = 180,
    private val runner: CommandRunner = CommandRunner(root)
) {
    private val entries = CopyOnWriteArrayList<TerminalEntry>()
    private var currentStdout: String = ""
    private var currentStderr: String = ""
    val shellPath: String = resolveShell()

    /** Full output of the command that just ran. The next command replaces it. */
    fun currentOutput(): String = currentStdout + currentStderr

    fun workingDirectory(): File = root
    fun timeoutSeconds(): Long = timeoutSeconds
    fun isBusy(): Boolean = runner.isRunning()
    fun history(limit: Int = Int.MAX_VALUE): List<TerminalEntry> = entries.takeLast(limit.coerceAtLeast(0))
    fun clearHistory() { entries.clear() }

    fun execute(
        command: String,
        onStdout: ((String) -> Unit)? = null,
        onStderr: ((String) -> Unit)? = null,
        timeoutSeconds: Long = this.timeoutSeconds
    ): TerminalEntry {
        val trimmed = command.trim()
        require(trimmed.isNotEmpty()) { "A terminal command is required" }
        val started = System.currentTimeMillis()
        val result = runner.run(
            listOf(shellPath, "-c", trimmed),
            timeoutSeconds,
            onStdout,
            onStderr
        )
        val durationMs = System.currentTimeMillis() - started
        val cancelled = result.exitCode == 130 && result.stderr.contains("cancelled")
        currentStdout = result.stdout
        currentStderr = result.stderr
        return TerminalEntry(
            command = trimmed,
            stdout = TerminalOutput.cap(result.stdout),
            stderr = TerminalOutput.cap(result.stderr),
            exitCode = result.exitCode,
            timedOut = result.timedOut,
            durationMs = durationMs,
            cancelled = cancelled,
            shell = shellPath
        ).also { entries += it }
    }

    /**
     * Execute an argument vector directly, without joining arguments into shell source.
     * Use this for callers that already have tokenized arguments.
     */
    fun executeArgs(
        command: List<String>,
        onStdout: ((String) -> Unit)? = null,
        onStderr: ((String) -> Unit)? = null,
        timeoutSeconds: Long = this.timeoutSeconds
    ): TerminalEntry {
        require(command.isNotEmpty()) { "A terminal command is required" }
        val started = System.currentTimeMillis()
        val result = runner.run(command, timeoutSeconds, onStdout, onStderr)
        val durationMs = System.currentTimeMillis() - started
        val cancelled = result.exitCode == 130 && result.stderr.contains("cancelled")
        currentStdout = result.stdout
        currentStderr = result.stderr
        return TerminalEntry(
            command = command.joinToString(" "),
            stdout = TerminalOutput.cap(result.stdout),
            stderr = TerminalOutput.cap(result.stderr),
            exitCode = result.exitCode,
            timedOut = result.timedOut,
            durationMs = durationMs,
            cancelled = cancelled,
            shell = "(direct process)"
        ).also { entries += it }
    }

    fun cancel(reason: String = "cancelled by owner") {
        runner.cancel(reason)
    }

    /**
     * Execute a verification command through this session's shared runner.
     * Keeping verification on the same runner makes the UI Stop action cancel
     * verification just like an interactive terminal command.
     */
    fun executeRaw(
        command: List<String>,
        timeoutSeconds: Long = this.timeoutSeconds
    ): CommandResult {
        require(command.isNotEmpty()) { "A terminal command is required" }
        return runner.run(command, timeoutSeconds)
    }

    companion object {
        fun resolveShell(): String {
            val candidates = listOf(
                File("/system/bin/sh"),
                File("/bin/sh")
            )
            val hit = candidates.firstOrNull { it.isFile && it.canExecute() }
            return hit?.absolutePath ?: "sh"
        }
    }
}

object TerminalOutput {
    const val STORED_LIMIT = 256 * 1024
    const val TRIM_NOTE = "\n[output trimmed]\n"

    fun cap(text: String): String {
        if (text.length <= STORED_LIMIT) return text
        val keep = (STORED_LIMIT - TRIM_NOTE.length).coerceAtLeast(0)
        return text.take(keep) + TRIM_NOTE
    }
}
