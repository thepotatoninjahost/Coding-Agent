package com.codingagent.live

import com.codingagent.domain.*
import com.codingagent.policy.AgentAction
import com.codingagent.policy.AgentActionCategory
import com.codingagent.policy.AgentConstitution
import com.codingagent.policy.ApprovalLedger

import java.io.File
import java.security.MessageDigest
import java.util.UUID

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

class SelfEvolution(private val root: File) {
    private val evolutionRoot = root.resolve(".coding-agent/evolution")
    private val versionsRoot = evolutionRoot.resolve("versions")
    private val activeFile = evolutionRoot.resolve("active-version")
    private val historyFile = evolutionRoot.resolve("history.tsv")

    init {
        versionsRoot.mkdirs()
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
        val violations = AgentConstitution.check(action.copy(sandboxPassed = evaluation.passed), approvalAt = approvalAt)
        if (violations.isNotEmpty()) return PromotionResult.Rejected(violations.joinToString("; ") { "${it.rule}: ${it.message}" })
        if (!staged.isFile) return PromotionResult.Rejected("Staged source does not exist")
        val parent = staged.parentFile ?: return PromotionResult.Rejected("Staged source directory is missing")
        val version = EvolutionVersion(
            id = parent.name,
            kind = kind,
            sourcePath = staged.absolutePath,
            checksum = checksum(staged),
            evaluationPassed = evaluation.passed,
            createdAt = System.currentTimeMillis()
        )
        activeFile.parentFile?.mkdirs()
        activeFile.writeText(version.id)
        historyFile.appendText(listOf(version.id, kind, version.checksum, version.evaluationPassed, version.createdAt).joinToString("\t") + "\n")
        return PromotionResult.Promoted(version)
    }

    fun activeVersion(): String? = activeFile.takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }

    fun history(): List<EvolutionVersion> = if (!historyFile.isFile) emptyList() else historyFile.readLines().mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size != 5) null else EvolutionVersion(fields[0], fields[1], "", fields[2], fields[3].toBoolean(), fields[4].toLongOrNull() ?: 0)
    }

    private fun checksum(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

class ExperienceRecorder(private val root: File) {
    private val file = root.resolve(".coding-agent/experience.tsv")

    @Synchronized
    fun record(task: String, operation: String, result: String, evidence: String, passed: Boolean) {
        file.parentFile?.mkdirs()
        file.appendText(listOf(System.currentTimeMillis(), passed, task, operation, result, evidence).joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') } + "\n")
    }

    fun all(): List<String> = if (file.isFile) file.readLines() else emptyList()
}
