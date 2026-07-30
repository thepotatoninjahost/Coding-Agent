package com.codingagent.core

import java.security.MessageDigest

class AgentTools(private val workspace: ProjectWorkspace) {
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

    fun terminal(command: List<String>, timeoutSeconds: Long = 90): TerminalEntry {
        require(command.isNotEmpty()) { "Command cannot be empty" }
        val result = CommandRunner(workspace.projectRoot()).run(command, timeoutSeconds)
        return TerminalEntry(result.command, result.stdout, result.stderr, result.exitCode, result.timedOut)
    }

    private fun checksum(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
