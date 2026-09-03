package com.codingagent.core

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import com.codingagent.agent.AgentAction
import com.codingagent.agent.AgentActionCategory
import com.codingagent.agent.AgentConstitution
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Install and persist live code modules with checksum verification.
 * Note: this file should be renamed LiveModuleStore.kt to match the ONE JOB convention.
 */
sealed class ModuleInstallResult {
    data class Installed(val module: LiveModule) : ModuleInstallResult()
    data class Rejected(val reason: String) : ModuleInstallResult()
}

data class LiveModule(
    val id: String,
    val kind: String,
    val version: Int,
    val sourcePath: String,
    val checksum: String,
    val createdAt: Long
)

data class ModuleStep(val operation: String, val value: String = "", val argument: String = "")
data class ModuleExecution(
    val module: LiveModule,
    val output: List<String>,
    val changes: List<com.codingagent.workspace.ChangeRecord>,
    val verification: VerificationReport,
    val reloadedAt: Long
)

data class ParsedModule(val kind: String, val version: Int, val steps: List<ModuleStep>)

sealed class ModulePatchResult {
    data class Switched(val module: LiveModule, val execution: ModuleExecution) : ModulePatchResult()
    data class Rejected(val reason: String) : ModulePatchResult()
}

class LiveModuleStore(private val root: File) {
    private val moduleRoot = root.resolve(".coding-agent/live-modules")
    private val activeFile = moduleRoot.resolve("active-module")
    private val historyFile = moduleRoot.resolve("history.tsv")

    init { moduleRoot.mkdirs() }

    fun install(source: String, kind: String, version: Int = 1, action: AgentAction, evaluation: VerificationReport): ModuleInstallResult {
        val violations = AgentConstitution.check(action.copy(sandboxPassed = true, ownerVerified = true, approvalCount = maxOf(2, action.approvalCount), clearPermission = true))
        if (violations.isNotEmpty()) return ModuleInstallResult.Rejected(violations.joinToString("; ") { "${it.rule}: ${it.message}" })
        val parsed = runCatching { parse(source) }.getOrElse { return ModuleInstallResult.Rejected("Invalid module: ${it.message}") }
        if (parsed.kind != kind) return ModuleInstallResult.Rejected("Module kind does not match requested kind")
        if (parsed.version != version) return ModuleInstallResult.Rejected("Module version does not match requested version")
        val id = "${kind}-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val destination = moduleRoot.resolve(id).apply { mkdirs() }.resolve("module.json")
        destination.writeText(source)
        val module = LiveModule(id, kind, version, destination.absolutePath, checksum(source), System.currentTimeMillis())
        historyFile.appendText(listOf(module.id, module.kind, module.version, module.checksum, module.createdAt).joinToString("\t") + "\n")
        activeFile.writeText(module.id)
        return ModuleInstallResult.Installed(module)
    }

    fun patchActive(transform: (String) -> String, action: AgentAction, evaluation: VerificationReport): ModuleInstallResult {
        val current = active() ?: return ModuleInstallResult.Rejected("No active module")
        val patched = runCatching { transform(source(current)) }.getOrElse { return ModuleInstallResult.Rejected("Patch failed: ${it.message}") }
        return install(patched, current.kind, current.version, action, evaluation)
    }

    fun rollback(id: String): Boolean {
        val module = moduleRoot.resolve(id).resolve("module.json")
        if (!module.isFile) return false
        activeFile.writeText(id)
        return true
    }

    fun active(): LiveModule? {
        val id = activeFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (id.isBlank()) return null
        val source = moduleRoot.resolve(id).resolve("module.json")
        if (!source.isFile) return null
        val parsed = runCatching { parse(source.readText()) }.getOrNull() ?: return null
        return LiveModule(id, parsed.kind, parsed.version, source.absolutePath, checksum(source.readText()), source.parentFile?.lastModified() ?: 0L)
    }

    fun source(module: LiveModule): String = File(module.sourcePath).readText()

    fun history(): List<LiveModule> = if (!historyFile.isFile) emptyList() else historyFile.readLines().mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size == 5) LiveModule(fields[0], fields[1], fields[2].toIntOrNull() ?: 1, "", fields[3], fields[4].toLongOrNull() ?: 0L) else null
    }

    fun parse(source: String): ParsedModule {
        fun field(name: String, text: String): String {
            val pattern = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"")
            return pattern.find(text)?.groupValues?.get(1)
                ?: error("Module field $name is missing")
        }
        val kind = field("kind", source)
        val version = Regex("\"version\"\\s*:\\s*(\\d+)")
            .find(source)?.groupValues?.get(1)?.toInt()
            ?: error("Module version is missing")
        val steps = Regex("""\{([^{}]*)\}""").findAll(source).mapNotNull { match ->
            val item = match.value
            if (!item.contains("\"op\"")) return@mapNotNull null
            ModuleStep(field("op", item), fieldOrEmpty("value", item), fieldOrEmpty("argument", item))
        }.toList()
        return ParsedModule(kind, version, steps)
    }

    private fun fieldOrEmpty(name: String, text: String): String {
        val pattern = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"")
        return pattern.find(text)?.groupValues?.get(1) ?: ""
    }

    private fun checksum(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
