package com.codingagent.core

import com.codingagent.domain.CommandResult
import com.codingagent.terminal.CommandExecutor
import java.io.File

class CommandRunner(private val root: File) {
    private val delegate = CommandExecutor(root)
    fun run(command: List<String>, timeoutSeconds: Long = 90, onOutput: ((String) -> Unit)? = null): CommandResult =
        delegate.run(command, timeoutSeconds, onOutput)
    fun cancel(reason: String = "cancelled by owner") = delegate.cancel()
}

val TerminalEntry.stdout: String get() = result.stdout
val TerminalEntry.stderr: String get() = result.stderr
val TerminalEntry.exitCode: Int get() = result.exitCode
val TerminalEntry.timedOut: Boolean get() = result.timedOut

fun CommandRunner.cancel(reason: String = "cancelled by owner") = Unit
