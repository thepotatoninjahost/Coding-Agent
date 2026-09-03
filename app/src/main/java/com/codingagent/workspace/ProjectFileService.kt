package com.codingagent.workspace

import java.io.File
import com.codingagent.agent.AgentTools

/**
 * ONE JOB: Safe list/read of project files for agent tools.
 */
class ProjectFileService(private val workspace: ProjectWorkspace) {
    // Was constructing a fresh AgentTools(workspace) inside read() and save() on every call —
    // both are hot-path (once per model tool turn via AgentToolDispatch; once per editor
    // open/save in MainActivity). One shared instance scoped to this service's ProjectWorkspace
    // is correct and avoids the repeated allocation.
    private val tools = AgentTools(workspace)

    fun list(path: String = ""): List<String> {
        val directory = resolveDirectory(path)
        val root = workspace.projectRoot()
        return directory.listFiles()
            ?.sortedBy { it.name.lowercase() }
            ?.map { ProjectPaths.relative(root, it) }
            .orEmpty()
    }

    fun listSourceFileNames(): List<String> {
        return workspace.summary().files
            .map { File(it.path).name }
            .distinct()
            .sortedBy { it.lowercase() }
    }

    fun listSourceFilePaths(): List<String> {
        return workspace.summary().files.map { it.path }.sorted()
    }

    fun read(path: String): EditorDocument = tools.read(path)

    fun save(path: String, content: String, coordinator: MutationCoordinator): MutationProposeResult =
        tools.proposeSave(path, content, coordinator)

    private fun resolveDirectory(path: String): File {
        val root = workspace.projectRoot().canonicalFile
        val directory = root.resolve(path).canonicalFile
        require(directory.toPath().startsWith(root.toPath())) { "Unsafe project path" }
        require(directory.isDirectory) { "Directory does not exist: $path" }
        return directory
    }
}
