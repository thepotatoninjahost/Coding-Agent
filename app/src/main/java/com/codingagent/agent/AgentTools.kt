package com.codingagent.agent

import java.io.File
import java.security.MessageDigest
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation
import com.codingagent.workspace.EditorDocument
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.MutationProposeResult
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.TerminalEntry

/**
 * ONE JOB: Shared tool implementations used by autonomous and offline paths.
 */
class AgentTools(private val workspace: ProjectWorkspace) {
    private val terminalSession = workspace.terminal()

    fun read(path: String): EditorDocument {
        val file = resolveExistingFile(path)
        val content = file.readText()
        val relative = file.relativeTo(workspace.projectRoot()).invariantSeparatorsPath
        return EditorDocument(relative, content, checksum(content), false)
    }

    /**
     * Stage a save for dual approval.
     * Returns [MutationProposeResult] — never throws. Callers must handle both branches.
     */
    fun proposeSave(path: String, content: String, coordinator: MutationCoordinator): MutationProposeResult {
        val current = runCatching { read(path) }
            .getOrElse { ex ->
                return MutationProposeResult.Rejected(
                    "Could not read $path: ${ex.message.orEmpty().ifBlank { ex.javaClass.simpleName }}"
                )
            }
        if (current.content == content) return MutationProposeResult.Rejected("No changes to save")
        return coordinator.propose(
            request = "Editor save: $path",
            operations = listOf(TaskOperation(OperationKind.REPLACE, path, current.content, content)),
            reason = "Editor save"
        )
    }

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

    fun terminalWorkingDirectory(): File = terminalSession.workingDirectory()

    val terminalShellPath: String get() = terminalSession.shellPath

    fun terminalTimeoutSeconds(): Long = terminalSession.timeoutSeconds()

    fun isTerminalBusy(): Boolean = terminalSession.isBusy()

    fun clearTerminalHistory() = terminalSession.clearHistory()

    private fun resolveExistingFile(path: String): File {
        val root = workspace.projectRoot().canonicalFile
        require(!isReservedProjectMetadataPath(root, path, root.resolve(path))) {
            "Private agent and Git metadata are not accessible as project source"
        }
        val direct = root.resolve(path).canonicalFile
        require(direct.toPath().startsWith(root.toPath())) { "Unsafe project path" }
        require(!isReservedProjectMetadataPath(root, path, direct)) {
            "Private agent and Git metadata are not accessible as project source"
        }
        if (direct.isFile) return direct

        val normalized = path.trim().trimStart('/').replace('\\', '/')
        val match = findCaseInsensitive(root, normalized)
            ?: throw IllegalArgumentException("File does not exist: $path")
        require(match.canonicalFile.toPath().startsWith(root.toPath())) { "Unsafe project path" }
        require(!isReservedProjectMetadataPath(root, normalized, match)) {
            "Private agent and Git metadata are not accessible as project source"
        }
        return match
    }

    private fun isReservedProjectMetadataPath(root: File, requestedPath: String, candidate: File): Boolean {
        val requestedParts = requestedPath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (requestedParts.any { it.equals(".git", ignoreCase = true) } ||
            requestedParts.any { it.equals(".coding-agent", ignoreCase = true) }
        ) return true

        val relative = runCatching {
            root.toPath().relativize(candidate.canonicalFile.toPath()).toString().replace('\\', '/')
        }.getOrElse { return true }
        val canonicalParts = relative.split('/').filter { it.isNotEmpty() }
        return canonicalParts.any { it.equals(".git", ignoreCase = true) } ||
            canonicalParts.any { it.equals(".coding-agent", ignoreCase = true) }
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
