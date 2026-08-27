package com.codingagent.workspace

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ONE JOB: Timed shell execution in the project root with cancellation, history, and live streaming.
 *
 * Runs ProcessBuilder([resolvedSh, "-c", command]) with cwd = imported project root.
 * There is no pseudo-terminal: interactive programs (vim, less, ssh password prompts) will not work.
 * The resolved binary is stock Android /system/bin/sh when present, otherwise `sh` on PATH.
 * Default timeout is 180 seconds. Combined stream capture is capped at 256 KiB per stream.
 */
class TerminalSession(
    private val root: File,
    private val timeoutSeconds: Long = 180,
    private val runner: CommandRunner = CommandRunner(root)
) {
    private val entries = CopyOnWriteArrayList<TerminalEntry>()
    val shellPath: String = resolveShell()

    fun workingDirectory(): File = root
    fun timeoutSeconds(): Long = timeoutSeconds
    fun isBusy(): Boolean = runner.isRunning()
    fun history(limit: Int = 50): List<TerminalEntry> = entries.takeLast(limit)
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
        return TerminalEntry(
            command = trimmed,
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
            timedOut = result.timedOut,
            durationMs = durationMs,
            cancelled = cancelled,
            shell = shellPath
        ).also { entries += it }
    }

    fun cancel(reason: String = "cancelled by owner") {
        runner.cancel(reason)
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
