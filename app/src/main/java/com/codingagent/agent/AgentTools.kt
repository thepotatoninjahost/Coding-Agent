package com.codingagent.agent

import java.io.File
import java.security.MessageDigest
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation
import com.codingagent.workspace.AgentFileChunk
import com.codingagent.workspace.EditorDocument
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.MutationProposeResult
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.ProjectPaths
import com.codingagent.workspace.TerminalEntry

/**
 * ONE JOB: Shared tool implementations used by autonomous and offline paths.
 */
class AgentTools(private val workspace: ProjectWorkspace) {
    private val terminalSession = workspace.terminal()

    fun read(path: String): EditorDocument {
        val file = resolveExistingFile(path)
        require(file.length() <= MAX_READ_BYTES) { "File exceeds the 2 MiB editor-read limit; use the agent's chunked file reader for complete access" }
        val content = file.readText()
        val relative = file.relativeTo(workspace.projectRoot()).invariantSeparatorsPath
        return EditorDocument(relative, content, checksum(content), false)
    }

    /** Reads a complete file through ordered character ranges without loading it all at once. */
    fun readChunk(path: String, startCharacter: Int = 0, maxCharacters: Int = 12_000): AgentFileChunk {
        require(startCharacter >= 0) { "startCharacter cannot be negative" }
        require(maxCharacters > 0) { "maxCharacters must be positive" }
        val file = resolveExistingFile(path)
        java.io.PushbackReader(file.reader(Charsets.UTF_8), 1).use { reader ->
            var skipped = 0
            while (skipped < startCharacter) {
                val amount = reader.skip((startCharacter - skipped).toLong()).toInt()
                if (amount > 0) skipped += amount else {
                    val next = reader.read()
                    if (next < 0) return AgentFileChunk(
                        file.relativeTo(workspace.projectRoot()).invariantSeparatorsPath, skipped, "", skipped, false
                    )
                    skipped++
                }
            }
            val content = StringBuilder()
            while (content.length < maxCharacters) {
                val next = reader.read()
                if (next < 0) break
                content.append(next.toChar())
            }
            if (content.isNotEmpty() && content.last().isHighSurrogate()) {
                val next = reader.read()
                if (next >= 0 && next.toChar().isLowSurrogate()) content.append(next.toChar())
                else if (next >= 0) reader.unread(next)
            }
            val lookahead = reader.read()
            val hasMore = lookahead >= 0
            if (lookahead >= 0) reader.unread(lookahead)
            val relative = file.relativeTo(workspace.projectRoot()).invariantSeparatorsPath
            return AgentFileChunk(relative, skipped, content.toString(), skipped + content.length, hasMore)
        }
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
        return terminalSession.executeArgs(command, onStdout, onStderr, timeoutSeconds)
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
        val direct = root.resolve(path).canonicalFile
        require(direct.toPath().startsWith(root.toPath())) { "Unsafe project path" }
        require(!ProjectPaths.isSensitivePath(ProjectPaths.relative(root, direct))) {
            "Sensitive project paths are not accessible as project source"
        }
        if (direct.isFile) return direct

        val normalized = path.trim().trimStart('/').replace('\\', '/')
        val match = findCaseInsensitive(root, normalized)
            ?: throw IllegalArgumentException("File does not exist: $path")
        require(match.canonicalFile.toPath().startsWith(root.toPath())) { "Unsafe project path" }
        require(!ProjectPaths.isSensitivePath(ProjectPaths.relative(root, match))) {
            "Sensitive project paths are not accessible as project source"
        }
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

    companion object {
        private const val MAX_READ_BYTES = 2L * 1024 * 1024
    }

    private fun checksum(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
