package com.codingagent.agent

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import com.codingagent.workspace.VerificationReport

sealed class PromotionResult {
    data class Promoted(val version: EvolutionVersion) : PromotionResult()
    data class Rejected(val reason: String) : PromotionResult()
}

data class EvolutionVersion(
    val id: String,
    val kind: String,
    val sourcePath: String,
    val checksum: String,
    val evaluationPassed: Boolean,
    val createdAt: Long
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
        val version = EvolutionVersion(
            id = parent.name,
            kind = kind,
            sourcePath = staged.absolutePath,
            checksum = checksum(staged),
            evaluationPassed = evaluation.passed,
            createdAt = System.currentTimeMillis()
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
                    staged.absolutePath
                ).joinToString("\t") + "\n"
            )
            activeFile.writeText(version.id)
            PromotionResult.Promoted(version)
        }.getOrElse { error ->
            PromotionResult.Rejected("Could not durably record the promoted version: ${error.message.orEmpty()}")
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
                createdAt = fields[4].toLongOrNull() ?: 0
            )
        }
    }

    private fun checksum(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
