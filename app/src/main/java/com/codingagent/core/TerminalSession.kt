package com.codingagent.core

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class TerminalSession(
    private val root: File,
    private val timeoutSeconds: Long = 90,
    private val runner: CommandRunner = CommandRunner(root)
) {
    private val entries = CopyOnWriteArrayList<TerminalEntry>()

    fun execute(command: String): TerminalEntry {
        val trimmed = command.trim()
        require(trimmed.isNotEmpty()) { "A terminal command is required" }
        val result = runner.run(listOf("sh", "-c", trimmed), timeoutSeconds)
        return TerminalEntry(command = trimmed, stdout = result.stdout, stderr = result.stderr, exitCode = result.exitCode, timedOut = result.timedOut).also { entries += it }
    }

    fun history(limit: Int = 50): List<TerminalEntry> = entries.takeLast(limit)
}
