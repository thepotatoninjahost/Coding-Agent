package com.codingagent.model

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import com.codingagent.agent.AgentAction
import com.codingagent.agent.AgentConstitution
import com.codingagent.workspace.VerificationReport

sealed class ModelInstallResult {
    data class Installed(val model: LiveModel) : ModelInstallResult()
    data class Rejected(val reason: String) : ModelInstallResult()
}

data class ModelImportItem(
    val name: String,
    val sizeBytes: Long,
    val checksum: String,
    val accepted: Boolean,
    val reason: String? = null
)

data class LiveModelPackage(
    val manifest: File,
    val files: List<File>,
    val totalBytes: Long,
    val complete: Boolean,
    val missing: List<String>,
    val invalid: List<String>
)

data class LiveModel(
    val id: String,
    val name: String,
    val format: String,
    val sourcePath: String,
    val checksum: String,
    val sizeBytes: Long,
    val createdAt: Long
)

/**
 * ONE JOB: Store and checksum optional on-device model packages (vendor-neutral).
 * Not tied to any local inference vendor. Remote HTTP models do not use this store.
 */
class LiveModelStore(private val root: File) {
    private val modelRoot = com.codingagent.workspace.ProjectMetadataBoundary.normalizePath(root.resolve(".coding-agent/models"))
    private val activeFile = com.codingagent.workspace.ProjectMetadataBoundary.normalizePath(root.resolve(".coding-agent/models/active-model"))
    private val historyFile = com.codingagent.workspace.ProjectMetadataBoundary.normalizePath(root.resolve(".coding-agent/models/history.tsv"))

    init { modelRoot.mkdirs() }

    fun inspectPackage(directory: File): LiveModelPackage {
        val directoryIsSymlink = java.nio.file.Files.isSymbolicLink(directory.toPath())
        val manifest = directory.resolve(MANIFEST_NAME)
        val children = if (directoryIsSymlink) emptyList() else directory.listFiles()?.toList().orEmpty()
        val symlinks = children.filter { java.nio.file.Files.isSymbolicLink(it.toPath()) }
        val files = children
            .filter { it.isFile && it.name != MANIFEST_NAME && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .sortedBy { it.name }
        val missing = buildList {
            if (directoryIsSymlink || !manifest.isFile || java.nio.file.Files.isSymbolicLink(manifest.toPath())) {
                add(MANIFEST_NAME)
            }
            if (files.isEmpty()) add("payload")
        }
        val invalid = symlinks.map { "${it.name}: symbolic links are not allowed" } +
            files.filter { it.length() == 0L }.map { "${it.name}: empty" }
        val complete = missing.isEmpty() && invalid.isEmpty()
        return LiveModelPackage(manifest, files, files.sumOf { it.length() }, complete, missing, invalid)
    }

    fun installPackage(
        directory: File,
        name: String,
        format: String,
        action: AgentAction,
        evaluation: VerificationReport
    ): ModelInstallResult {
        val pack = inspectPackage(directory)
        if (!pack.complete) {
            return ModelInstallResult.Rejected(
                "Incomplete model package: missing ${pack.missing.joinToString().ifBlank { "manifest or payload" }}" +
                    (pack.invalid.joinToString().takeIf { it.isNotBlank() }?.let { "; invalid $it" }.orEmpty())
            )
        }
        if (!evaluation.passed) {
            return ModelInstallResult.Rejected(
                "Model evaluation failed: ${evaluation.issues.joinToString { "${it.path}:${it.line}: ${it.message}" }}"
            )
        }
        val violations = AgentConstitution.check(action.copy(sandboxPassed = evaluation.passed))
        if (violations.isNotEmpty()) {
            return ModelInstallResult.Rejected(violations.joinToString("; ") { "${it.rule}: ${it.message}" })
        }
        val id = "${name.replace(Regex("[^A-Za-z0-9_-]"), "-")}-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val destination = modelRoot.resolve(id).apply { mkdirs() }
        pack.manifest.copyTo(destination.resolve(MANIFEST_NAME))
        pack.files.forEach { it.copyTo(destination.resolve(it.name)) }
        val manifestModel = LiveModel(
            id, name, format, destination.absolutePath,
            checksumDirectory(destination), pack.totalBytes, System.currentTimeMillis()
        )
        historyFile.appendText(
            listOf(
                manifestModel.id, manifestModel.name, manifestModel.format,
                manifestModel.checksum, manifestModel.sizeBytes, manifestModel.createdAt
            ).joinToString("\t") + "\n"
        )
        activeFile.writeText(id)
        return ModelInstallResult.Installed(manifestModel)
    }

    fun importReport(directory: File): List<ModelImportItem> =
        inspectPackage(directory).files.map { file ->
            ModelImportItem(file.name, file.length(), checksum(file), file.length() > 0L)
        }

    fun install(
        source: File,
        name: String,
        format: String,
        action: AgentAction,
        evaluation: VerificationReport
    ): ModelInstallResult {
        if (!source.isFile) return ModelInstallResult.Rejected("Model file does not exist")
        if (!evaluation.passed) {
            return ModelInstallResult.Rejected(
                "Model evaluation failed: ${evaluation.issues.joinToString { "${it.path}:${it.line}: ${it.message}" }}"
            )
        }
        val violations = AgentConstitution.check(action.copy(sandboxPassed = evaluation.passed))
        if (violations.isNotEmpty()) {
            return ModelInstallResult.Rejected(violations.joinToString("; ") { "${it.rule}: ${it.message}" })
        }
        val id = "${name.replace(Regex("[^A-Za-z0-9_-]"), "-")}-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val destination = modelRoot.resolve(id).apply { mkdirs() }.resolve(source.name)
        source.copyTo(destination)
        val model = LiveModel(
            id, name, format, destination.absolutePath,
            checksum(destination), destination.length(), System.currentTimeMillis()
        )
        historyFile.appendText(
            listOf(model.id, model.name, model.format, model.checksum, model.sizeBytes, model.createdAt)
                .joinToString("\t") + "\n"
        )
        activeFile.writeText(model.id)
        return ModelInstallResult.Installed(model)
    }

    fun active(): LiveModel? {
        val id = activeFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (!id.matches(SAFE_ID)) return null
        val directory = runCatching {
            com.codingagent.workspace.ProjectMetadataBoundary.resolve(root, ".coding-agent/models/$id")
        }.getOrNull() ?: return null
        if (!directory.isDirectory) return null
        val fields = history().firstOrNull { it.id == id } ?: return null
        val children = directory.listFiles()?.toList().orEmpty()
        if (children.any { java.nio.file.Files.isSymbolicLink(it.toPath()) }) return null
        val payload = children.filter { it.isFile && it.name != MANIFEST_NAME }
        val source = payload.singleOrNull() ?: directory
        return fields.copy(sourcePath = source.absolutePath)
    }

    fun history(): List<LiveModel> = if (!historyFile.isFile) {
        emptyList()
    } else {
        historyFile.readLines().mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size == 6) {
                LiveModel(
                    fields[0], fields[1], fields[2], "",
                    fields[3], fields[4].toLongOrNull() ?: 0L, fields[5].toLongOrNull() ?: 0L
                )
            } else {
                null
            }
        }
    }

    fun modelBytes(model: LiveModel): ByteArray {
        val source = File(model.sourcePath)
        val rootPath = modelRoot.canonicalFile.toPath()
        val canonicalSource = source.canonicalFile.toPath()
        require(canonicalSource.startsWith(rootPath)) { "Model source is outside private model storage" }
        val relative = root.canonicalFile.toPath().relativize(source.absoluteFile.toPath())
            .toString().replace('\\', '/')
        val safeSource = com.codingagent.workspace.ProjectMetadataBoundary.resolve(root, relative)
        val entries = safeSource.walkTopDown().toList()
        require(entries.none { java.nio.file.Files.isSymbolicLink(it.toPath()) }) {
            "Model payload may not contain symbolic links"
        }
        return entries.filter { it.isFile }.sortedBy { it.absolutePath }
            .fold(ByteArray(0)) { acc, file -> acc + file.readBytes() }
    }

    companion object {
        private val SAFE_ID = Regex("""[A-Za-z0-9_-]+""")
    }

    private fun checksum(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checksumDirectory(directory: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        directory.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { file ->
            digest.update(file.name.toByteArray())
            digest.update(checksum(file).toByteArray())
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MANIFEST_NAME = "model.manifest"
    }
}
