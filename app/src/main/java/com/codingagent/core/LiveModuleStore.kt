package com.codingagent.core

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import com.codingagent.agent.AgentAction
import com.codingagent.agent.AgentConstitution
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Persist and version live-module source (install, parse, roll back, list history).
 * Extracted out of LiveModules.kt, which mixed storage, execution, and default-module bootstrap
 * in one file. Runtime/execution now lives in LiveModuleRuntime.kt; default module bootstrap in
 * BuiltInModules.kt.
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

data class ParsedModule(val kind: String, val version: Int, val steps: List<ModuleStep>)

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
        val next = install(patched, current.kind, current.version, action, evaluation)
        if (next !is ModuleInstallResult.Installed) return next
        return next
    }

    /**
     * Point the active-module pointer at a previously installed module by id.
     * Returns true when the module directory exists and the pointer was updated;
     * false when the id is not found on disk (no write occurs in that case).
     */
    fun rollback(id: String): Boolean {
        val moduleDir = moduleRoot.resolve(id)
        if (!moduleDir.isDirectory) return false
        val moduleFile = moduleDir.resolve("module.json")
        if (!moduleFile.isFile) return false
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

    private fun checksum(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
