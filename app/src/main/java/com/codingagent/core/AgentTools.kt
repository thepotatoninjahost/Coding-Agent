package com.codingagent.core

import java.security.MessageDigest

class AgentTools(private val workspace: ProjectWorkspace) {
    private val terminalSession = TerminalSession(workspace.projectRoot())

    fun read(path: String): EditorDocument {
        val file = workspace.projectRoot().resolve(path)
        require(file.canonicalFile.toPath().startsWith(workspace.projectRoot().canonicalFile.toPath())) { "Unsafe project path" }
        require(file.isFile) { "File does not exist: $path" }
        val content = file.readText()
        return EditorDocument(path, content, checksum(content), false)
    }

    fun proposeSave(path: String, content: String, coordinator: MutationCoordinator): PendingChangeProposal {
        val current = read(path)
        require(current.content != content) { "No changes to save" }
        return coordinator.propose("Editor save: $path", listOf(TaskOperation(OperationKind.REPLACE, path, current.content, content)), "Editor save")
    }

    /** Preferred entry — runs through the shared session so cancel works. */
    fun terminal(
        command: List<String>,
        timeoutSeconds: Long = 90,
        onStdout: ((String) -> Unit)? = null,
        onStderr: ((String) -> Unit)? = null
    ): TerminalEntry {
        require(command.isNotEmpty()) { "Command cannot be empty" }
        val joined = command.joinToString(" ")
        return terminalSession.execute(joined, onStdout, onStderr)
    }

    fun runTerminal(
        command: String,
        onStdout: ((String) -> Unit)? = null,
        onStderr: ((String) -> Unit)? = null
    ): TerminalEntry = terminalSession.execute(command, onStdout, onStderr)

    fun cancelTerminal(reason: String = "cancelled by owner") {
        terminalSession.cancel(reason)
    }

    fun terminalHistory(limit: Int = 50): List<TerminalEntry> = terminalSession.history(limit)

    private fun checksum(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
