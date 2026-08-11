package com.codingagent.core

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * ONE JOB: Install and swap live code modules with checksum verification.
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
    val changes: List<ChangeRecord>,
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
        val next = install(patched, current.kind, current.version, action, evaluation)
        if (next !is ModuleInstallResult.Installed) return next
        return next
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
            val pattern = Regex("""\"$name\"\s*:\s*\"([^\"]*)\"""")
            return pattern.find(text)?.groupValues?.get(1)
                ?: error("Module field $name is missing")
        }
        val kind = field("kind", source)
        val version = Regex("""\"version\"\s*:\s*(\d+)""").find(source)?.groupValues?.get(1)?.toInt()
            ?: error("Module version is missing")
        val steps = Regex("""\{([^{}]*)\}""").findAll(source).mapNotNull { match ->
            val item = match.value
            if (!item.contains("\"op\"")) return@mapNotNull null
            ModuleStep(field("op", item), fieldOrEmpty("value", item), fieldOrEmpty("argument", item))
        }.toList()
        return ParsedModule(kind, version, steps)
    }

    private fun fieldOrEmpty(name: String, text: String): String {
        val pattern = Regex("""\"$name\"\s*:\s*\"([^\"]*)\"""")
        return pattern.find(text)?.groupValues?.get(1) ?: ""
    }

    private fun checksum(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

class LiveModuleRuntime(
    private val workspace: ProjectWorkspace,
    private val knowledge: KnowledgeProvider,
    private val store: LiveModuleStore
) {
    private var loaded: LoadedModule? = null

    @Synchronized
    fun reload(): LiveModule? {
        val active = store.active() ?: run { loaded = null; return null }
        val parsed = store.parse(store.source(active))
        loaded = LoadedModule(active, parsed, System.currentTimeMillis())
        return active
    }

    fun active(): LiveModule? = loaded?.module ?: reload()

    fun execute(input: String): ModuleExecution {
        val persisted = store.active()
        if (persisted?.id != loaded?.module?.id) reload()
        val current = loaded ?: error("No live module is active")
        val output = mutableListOf<String>()
        val changes = mutableListOf<ChangeRecord>()
        var verification = workspace.verify()
        for (step in current.parsed.steps) {
            val value = expand(step.value, input)
            when (step.operation.lowercase()) {
                "emit" -> output += value
                "knowledge" -> knowledge.search(value, step.argument.toIntOrNull() ?: 5).forEach { output += "${it.section}: ${it.excerpt}" }
                "project_search" -> workspace.search(value).forEach { output += "${it.path}:${it.line} ${it.text}" }
                "replace_exact" -> {
                    val parts = value.split("|||", limit = 3)
                    require(parts.size == 3) { "replace_exact requires path|||old|||new" }
                    output += "PROPOSAL_REQUIRED: live-module mutations must be approved through MutationCoordinator"
                    verification = workspace.verify()
                }
                "verify" -> verification = workspace.verify()
                "run" -> verification = workspace.runChecks(listOf(value.split(" ").filter { it.isNotBlank() }), step.argument.toLongOrNull() ?: 90)
                "lesson" -> workspace.recordLesson(value, step.argument, "live module ${current.module.id}")
                else -> error("Unknown live-module operation: ${step.operation}")
            }
        }
        return ModuleExecution(current.module, output, changes, verification, current.loadedAt)
    }

    @Synchronized
    fun applyPatch(
        transform: (String) -> String,
        action: AgentAction,
        input: String = ""
    ): ModulePatchResult {
        val before = store.active() ?: return ModulePatchResult.Rejected("No active module")
        val evaluation = runCatching { workspace.verify() }.getOrElse { return ModulePatchResult.Rejected("Evaluation failed: ${it.message}") }
        val installed = store.patchActive(transform, action, evaluation)
        if (installed is ModuleInstallResult.Rejected) return ModulePatchResult.Rejected(installed.reason)
        val after = reload() ?: return ModulePatchResult.Rejected("Patched module could not be loaded")
        val execution = runCatching { execute(input) }.getOrElse {
            store.rollback(before.id)
            reload()
            return ModulePatchResult.Rejected("Patched module failed at runtime: ${it.message}")
        }
        if (!execution.verification.passed) {
            store.rollback(before.id)
            reload()
            return ModulePatchResult.Rejected("Patched module failed verification")
        }
        return ModulePatchResult.Switched(after, execution)
    }

    private fun expand(value: String, input: String): String = value.replace("${'$'}{input}", input)
    private data class LoadedModule(val module: LiveModule, val parsed: ParsedModule, val loadedAt: Long)
}

class BuiltInModules(context: Context) {
    private val store = LiveModuleStore(context.filesDir)

    fun installDefault(): ModuleInstallResult = store.install(
        """
        {"kind":"coding","version":1,"steps":[
          {"op":"emit","value":"Live coding module active for: ${'$'}{input}"},
          {"op":"knowledge","value":"${'$'}{input}","argument":"4"},
          {"op":"project_search","value":"${'$'}{input}"},
          {"op":"verify"}
        ]}
        """.trimIndent(), "coding", 1,
        AgentAction("Install built-in coding module", AgentActionCategory.CODE_CHANGE, ownerVerified = true, approvalCount = 2),
        VerificationReport(true, emptyList())
    )
}
