package com.codingagent.core

import com.codingagent.agent.AgentAction
import com.codingagent.knowledge.KnowledgeProvider
import com.codingagent.workspace.ChangeRecord
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.VerificationReport

/**
 * Result of one live-module execution pass. Lives here (not LiveModuleStore.kt) because
 * it is an execution-time concern, matching this file's ONE JOB.
 */
data class ModuleExecution(
    val module: LiveModule,
    val output: List<String>,
    val changes: List<ChangeRecord>,
    val verification: VerificationReport,
    val reloadedAt: Long
)

/**
 * Result of an applyPatch() call: either the runtime switched to the patched module,
 * or the patch was rejected and the previous module remains active.
 */
sealed class ModulePatchResult {
    data class Switched(val module: LiveModule, val execution: ModuleExecution) : ModulePatchResult()
    data class Rejected(val reason: String) : ModulePatchResult()
}

/**
 * ONE JOB: Execute live-module step sequences against the workspace.
 */
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

    private fun expand(value: String, input: String): String = value.replace("\${input}", input)
    private data class LoadedModule(val module: LiveModule, val parsed: ParsedModule, val loadedAt: Long)
}
