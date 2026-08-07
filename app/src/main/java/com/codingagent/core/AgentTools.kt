package com.codingagent.core

import java.io.File
import java.security.MessageDigest

class AgentTools(private val workspace: ProjectWorkspace) {
    private val terminalSession = TerminalSession(workspace.projectRoot())

    fun read(path: String): EditorDocument {
        val file = resolveExistingFile(path)
        val content = file.readText()
        val relative = file.relativeTo(workspace.projectRoot()).invariantSeparatorsPath
        return EditorDocument(relative, content, checksum(content), false)
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

    /**
     * Resolve a project-relative path. On Android the filesystem is often case-insensitive,
     * but Java File.isFile is still case-sensitive on some mounts — walk for a case-insensitive match.
     */
    private fun resolveExistingFile(path: String): File {
        val root = workspace.projectRoot().canonicalFile
        val direct = root.resolve(path).canonicalFile
        require(direct.toPath().startsWith(root.toPath())) { "Unsafe project path" }
        if (direct.isFile) return direct

        val normalized = path.trim().trimStart('/').replace('\\', '/')
        val match = findCaseInsensitive(root, normalized)
            ?: throw IllegalArgumentException("File does not exist: $path")
        require(match.canonicalFile.toPath().startsWith(root.toPath())) { "Unsafe project path" }
        return match
    }

    private fun findCaseInsensitive(root: File, relative: String): File? {
        val parts = relative.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var current = root
        for ((index, part) in parts.withIndex()) {
            val children = current.listFiles() ?: return null
            val hit = children.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            if (index == parts.lastIndex) {
                return if (hit.isFile) hit else null
            }
            if (!hit.isDirectory) return null
            current = hit
        }
        return null
    }

    private fun checksum(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
