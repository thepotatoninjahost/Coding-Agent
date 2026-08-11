package com.codingagent.core

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ONE JOB: Timed shell execution in the project root with cancellation.
 */
class TerminalSession(
    private val root: File,
    private val timeoutSeconds: Long = 90,
    private val runner: CommandRunner = CommandRunner(root)
) {
    private val entries = CopyOnWriteArrayList<TerminalEntry>()

    fun execute(
        command: String,
        onStdout: ((String) -> Unit)? = null,
        onStderr: ((String) -> Unit)? = null
    ): TerminalEntry {
        val trimmed = command.trim()
        require(trimmed.isNotEmpty()) { "A terminal command is required" }
        val result = runner.run(listOf("sh", "-c", trimmed), timeoutSeconds, onStdout, onStderr)
        return TerminalEntry(
            command = trimmed,
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
            timedOut = result.timedOut
        ).also { entries += it }
    }

    /** Stop the active process if one is running. */
    fun cancel(reason: String = "cancelled by owner") {
        runner.cancel(reason)
    }

    fun isBusy(): Boolean = !runner.isCancelled()

    fun history(limit: Int = 50): List<TerminalEntry> = entries.takeLast(limit)
}
