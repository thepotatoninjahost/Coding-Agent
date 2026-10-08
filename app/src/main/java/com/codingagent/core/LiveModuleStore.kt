package com.codingagent.core

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import com.codingagent.agent.AgentAction
import com.codingagent.agent.AgentConstitution
import com.codingagent.workspace.VerificationReport

private val SAFE_ID = Regex("[A-Za-z0-9_-]+")

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
        val safeKind = kind.trim()
        if (!safeKind.matches(SAFE_ID)) {
            return ModuleInstallResult.Rejected("Module kind contains unsafe path characters")
        }
        if (!evaluation.passed) {
            return ModuleInstallResult.Rejected(
                "Module evaluation failed: ${evaluation.issues.joinToString { "${it.path}:${it.line}: ${it.message}" }}"
            )
        }
        val violations = AgentConstitution.check(action.copy(sandboxPassed = evaluation.passed))
        if (violations.isNotEmpty()) return ModuleInstallResult.Rejected(violations.joinToString("; ") { "${it.rule}: ${it.message}" })
        val parsed = runCatching { parse(source) }.getOrElse { return ModuleInstallResult.Rejected("Invalid module: ${it.message}") }
        if (parsed.kind != safeKind) return ModuleInstallResult.Rejected("Module kind does not match requested kind")
        if (parsed.version != version) return ModuleInstallResult.Rejected("Module version does not match requested version")
        val id = "${safeKind}-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
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
        if (!id.matches(SAFE_ID)) return false
        val moduleDir = moduleRoot.resolve(id)
        if (!moduleDir.isDirectory) return false
        val moduleFile = moduleDir.resolve("module.json")
        if (!moduleFile.isFile) return false
        activeFile.writeText(id)
        return true
    }

    fun active(): LiveModule? {
        val id = activeFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (!id.matches(SAFE_ID)) return null
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
        val root = org.json.JSONObject(source)
        val kind = root.optString("kind").trim().takeIf { it.isNotBlank() }
            ?: error("Module field kind is missing")
        if (!root.has("version")) error("Module version is missing")
        val version = root.optInt("version", Int.MIN_VALUE)
        if (version == Int.MIN_VALUE) error("Module version must be an integer")
        val stepsArray = root.optJSONArray("steps")
            ?: error("Module field steps is missing")
        val steps = buildList {
            for (index in 0 until stepsArray.length()) {
                val item = stepsArray.optJSONObject(index)
                    ?: error("Module step $index must be an object")
                val op = item.optString("op").trim()
                if (op.isBlank()) error("Module step $index is missing op")
                add(
                    ModuleStep(
                        operation = op,
                        value = item.optString("value", ""),
                        argument = item.optString("argument", "")
                    )
                )
            }
        }
        return ParsedModule(kind, version, steps)
    }

    private fun checksum(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
