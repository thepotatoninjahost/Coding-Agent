package com.codingagent.terminal

import com.codingagent.domain.CommandResult
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

data class TerminalEntry(
    val command: String,
    val result: CommandResult
)

class TerminalSession(root: File, private val executor: CommandExecutor = CommandExecutor(root)) {
    private val history = CopyOnWriteArrayList<TerminalEntry>()

    fun execute(command: String, timeoutSeconds: Long = 90, onOutput: ((String) -> Unit)? = null): TerminalEntry {
        val trimmed = command.trim()
        require(trimmed.isNotEmpty()) { "A terminal command is required" }
        return TerminalEntry(trimmed, executor.run(listOf("sh", "-c", trimmed), timeoutSeconds, onOutput)).also(history::add)
    }

    fun cancel() = executor.cancel()

    fun isBusy(): Boolean = executor.isBusy()

    fun history(limit: Int = 50): List<TerminalEntry> = history.takeLast(limit)
}
