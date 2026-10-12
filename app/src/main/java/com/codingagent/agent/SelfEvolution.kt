package com.codingagent.agent

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import com.codingagent.workspace.VerificationReport

sealed class PromotionResult {
    data class Promoted(val version: EvolutionVersion) : PromotionResult()
    data class Rejected(val reason: String) : PromotionResult()
}

sealed class SnapshotRestoreResult {
    data class Restored(val version: EvolutionVersion, val destination: File) : SnapshotRestoreResult()
    data class Rejected(val reason: String) : SnapshotRestoreResult()
}

data class EvolutionVersion(
    val id: String,
    val kind: String,
    val sourcePath: String,
    val checksum: String,
    val evaluationPassed: Boolean,
    val createdAt: Long,
    val targetPath: String = ""
)

/**
 * ONE JOB: Stage and promote versioned source snapshots for local self-improvement.
 */
class SelfEvolution(private val root: File) {
    private val evolutionRoot = root.resolve(".coding-agent/evolution")
    private val versionsRoot = evolutionRoot.resolve("versions")
    private val activeFile = evolutionRoot.resolve("active-version")
    private val historyFile = evolutionRoot.resolve("history.tsv")

    init {
        versionsRoot.mkdirs()
        LessonContext.bindEvolution(::history)
    }

    fun stageSource(source: File, kind: String): File {
        require(source.isFile) { "Evolution source does not exist" }
        val id = UUID.randomUUID().toString()
        val destination = versionsRoot.resolve(id).apply { mkdirs() }.resolve(source.name)
        source.copyTo(destination)
        destination.parentFile.resolve(".target-path").writeText(inferTargetPath(source).orEmpty())
        return destination
    }

    fun promoteSource(
        staged: File,
        kind: String,
        evaluation: VerificationReport,
        action: AgentAction,
        approvalAt: Long? = null
    ): PromotionResult {
        if (!evaluation.passed) {
            return PromotionResult.Rejected("Evolution source cannot be promoted without a passing evaluation")
        }
        val violations = AgentConstitution.check(action.copy(sandboxPassed = true), approvalAt = approvalAt)
        if (violations.isNotEmpty()) return PromotionResult.Rejected(violations.joinToString("; ") { "${it.rule}: ${it.message}" })
        if (!staged.isFile) return PromotionResult.Rejected("Staged source does not exist")
        val parent = staged.parentFile ?: return PromotionResult.Rejected("Staged source directory is missing")
        val expectedParent = runCatching { versionsRoot.canonicalFile }.getOrNull()
            ?: return PromotionResult.Rejected("Evolution versions directory is unavailable")
        val actualParent = runCatching { parent.canonicalFile }.getOrNull()
            ?: return PromotionResult.Rejected("Staged source directory is unavailable")
        if (actualParent.parentFile != expectedParent) {
            return PromotionResult.Rejected("Only snapshots created under the evolution versions directory can be promoted")
        }
        val targetPath = readStagedTargetPath(staged).orEmpty()
        val version = EvolutionVersion(
            id = parent.name,
            kind = kind,
            sourcePath = staged.absolutePath,
            checksum = checksum(staged),
            evaluationPassed = true,
            createdAt = System.currentTimeMillis(),
            targetPath = targetPath
        )
        return runCatching {
            activeFile.parentFile?.mkdirs()
            historyFile.appendText(
                listOf(
                    version.id,
                    kind,
                    version.checksum,
                    version.evaluationPassed,
                    version.createdAt,
                    staged.absolutePath,
                    version.targetPath
                ).joinToString("\t") + "\n"
            )
            activeFile.writeText(version.id)
            PromotionResult.Promoted(version)
        }.getOrElse { error ->
            PromotionResult.Rejected("Could not durably record the promoted version: ${error.message.orEmpty()}")
        }
    }

    /**
     * Restore a promoted snapshot to its original project-relative path.
     * The caller must verify the proposed restored source before invoking recovery.
     */
    fun restoreSnapshot(
        versionId: String,
        evaluation: VerificationReport,
        action: AgentAction,
        approvalAt: Long? = null
    ): SnapshotRestoreResult {
        if (!evaluation.passed) return SnapshotRestoreResult.Rejected("Snapshot recovery requires a passing evaluation")
        val version = history().lastOrNull { it.id == versionId }
            ?: return SnapshotRestoreResult.Rejected("Evolution version was not found in history")
        if (!version.evaluationPassed) return SnapshotRestoreResult.Rejected("Evolution version has no passing source evaluation")
        val violations = AgentConstitution.check(action.copy(sandboxPassed = true), approvalAt = approvalAt)
        if (violations.isNotEmpty()) {
            return SnapshotRestoreResult.Rejected(violations.joinToString("; ") { "${it.rule}: ${it.message}" })
        }
        val snapshot = managedSnapshot(File(version.sourcePath))
            ?: return SnapshotRestoreResult.Rejected("Evolution snapshot is missing or outside the managed versions directory")
        if (runCatching { checksum(snapshot) != version.checksum }.getOrDefault(true)) {
            return SnapshotRestoreResult.Rejected("Evolution snapshot checksum does not match its recorded history")
        }
        val destination = safeTarget(version.targetPath)
            ?: return SnapshotRestoreResult.Rejected("Evolution version has no safe project-relative recovery target")
        val originalBytes = if (destination.isFile) runCatching { destination.readBytes() }.getOrElse {
            return SnapshotRestoreResult.Rejected("Could not read the current recovery target: ${it.message.orEmpty()}")
        } else null
        var temporary: File? = null
        return try {
            destination.parentFile?.mkdirs()
            temporary = Files.createTempFile(destination.parentFile.toPath(), ".evolution-restore-", ".tmp").toFile()
            Files.copy(snapshot.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            moveReplacing(temporary, destination)
            temporary = null
            activeFile.parentFile?.mkdirs()
            activeFile.writeText(version.id)
            SnapshotRestoreResult.Restored(version, destination)
        } catch (error: Exception) {
            temporary?.delete()
            val rollbackSucceeded = runCatching {
                if (originalBytes == null) {
                    destination.delete()
                } else {
                    val rollbackFile = Files.createTempFile(destination.parentFile.toPath(), ".evolution-rollback-", ".tmp").toFile()
                    try {
                        rollbackFile.writeBytes(originalBytes)
                        moveReplacing(rollbackFile, destination)
                    } finally {
                        rollbackFile.delete()
                    }
                }
            }.isSuccess
            SnapshotRestoreResult.Rejected(
                "Snapshot recovery failed: ${error.message.orEmpty()}" +
                    if (rollbackSucceeded) "; the original target was restored" else "; target rollback failed and manual recovery is required"
            )
        }
    }

    fun activeVersion(): String? = activeFile.takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }

    fun history(): List<EvolutionVersion> = if (!historyFile.isFile) emptyList() else historyFile.readLines().mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size < 5) {
            null
        } else {
            EvolutionVersion(
                id = fields[0],
                kind = fields[1],
                sourcePath = fields.getOrNull(5).orEmpty(),
                checksum = fields[2],
                evaluationPassed = fields[3].toBoolean(),
                createdAt = fields[4].toLongOrNull() ?: 0,
                targetPath = fields.getOrNull(6).orEmpty()
            )
        }
    }

    private fun managedSnapshot(file: File): File? {
        val snapshot = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val versions = runCatching { versionsRoot.canonicalFile }.getOrNull() ?: return null
        val versionDirectory = snapshot.parentFile ?: return null
        if (!snapshot.isFile || versionDirectory.parentFile != versions) return null
        return snapshot
    }

    private fun readStagedTargetPath(staged: File): String? {
        val managed = managedSnapshot(staged) ?: return null
        val metadata = managed.parentFile.resolve(".target-path")
        if (!metadata.isFile) return null
        return metadata.readText().trim().takeIf { isSafeRelativePath(it) }
    }

    private fun inferTargetPath(source: File): String? {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val canonicalSource = runCatching { source.canonicalFile }.getOrNull() ?: return null
        if (!canonicalSource.toPath().startsWith(canonicalRoot.toPath())) return null
        val relative = canonicalRoot.toPath().relativize(canonicalSource.toPath()).toString().replace(File.separatorChar, '/')
        return relative.takeIf { isSafeRelativePath(it) }
    }

    private fun safeTarget(relativePath: String): File? {
        if (!isSafeRelativePath(relativePath)) return null
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val destination = runCatching { canonicalRoot.resolve(relativePath).canonicalFile }.getOrNull() ?: return null
        if (!destination.toPath().startsWith(canonicalRoot.toPath())) return null
        return destination
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') ||
            path.split('/').any { it == ".." || it.isBlank() }
        ) return false
        return !path.substringBefore('/').equals(".coding-agent", ignoreCase = true)
    }

    private fun moveReplacing(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun checksum(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
