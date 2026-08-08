package com.codingagent.core

import java.io.File

class ProjectFileService(private val workspace: ProjectWorkspace) {
    fun list(path: String = ""): List<String> {
        val directory = resolveDirectory(path)
        return directory.listFiles()?.sortedBy { it.name.lowercase() }?.map { it.relativeTo(workspace.projectRoot()).invariantSeparatorsPath }.orEmpty()
    }

    fun read(path: String): EditorDocument = AgentTools(workspace).read(path)

    fun save(path: String, content: String, coordinator: MutationCoordinator): PendingChangeProposal = AgentTools(workspace).proposeSave(path, content, coordinator)

    private fun resolveDirectory(path: String): File {
        val root = workspace.projectRoot().canonicalFile
        val directory = root.resolve(path).canonicalFile
        require(directory.toPath().startsWith(root.toPath())) { "Unsafe project path" }
        require(directory.isDirectory) { "Directory does not exist: $path" }
        return directory
    }
}
