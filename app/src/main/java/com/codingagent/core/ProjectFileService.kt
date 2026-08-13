package com.codingagent.core

import java.io.File

/**
 * ONE JOB: Safe list/read of project files for agent tools.
 */
class ProjectFileService(private val workspace: ProjectWorkspace) {
    fun list(path: String = ""): List<String> {
        val directory = resolveDirectory(path)
        val root = workspace.projectRoot()
        return directory.listFiles()
            ?.sortedBy { it.name.lowercase() }
            ?.map { ProjectPaths.relative(root, it) }
            .orEmpty()
    }

    /**
     * Source-file **names only** (e.g. AutonomousAgent.kt), sorted unique.
     * For "list project source files" style requests.
     */
    fun listSourceFileNames(): List<String> {
        return workspace.summary().files
            .map { File(it.path).name }
            .distinct()
            .sortedBy { it.lowercase() }
    }

    /**
     * Source-file relative paths (e.g. app/src/main/java/.../AutonomousAgent.kt).
     */
    fun listSourceFilePaths(): List<String> {
        return workspace.summary().files.map { it.path }.sorted()
    }

    fun read(path: String): EditorDocument = AgentTools(workspace).read(path)

    fun save(path: String, content: String, coordinator: MutationCoordinator): PendingChangeProposal =
        AgentTools(workspace).proposeSave(path, content, coordinator)

    private fun resolveDirectory(path: String): File {
        val root = workspace.projectRoot().canonicalFile
        val directory = root.resolve(path).canonicalFile
        require(directory.toPath().startsWith(root.toPath())) { "Unsafe project path" }
        require(directory.isDirectory) { "Directory does not exist: $path" }
        return directory
    }
}
