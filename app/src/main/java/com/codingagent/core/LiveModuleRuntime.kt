package com.codingagent.core

import com.codingagent.agent.AgentAction
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation
import com.codingagent.knowledge.KnowledgeProvider
import com.codingagent.workspace.ChangeRecord
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.MutationProposeResult
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Load and execute the active live module against a project workspace.
 * Extracted out of LiveModules.kt (storage now in LiveModuleStore.kt, default-module bootstrap
 * in BuiltInModules.kt).
 */
data class ModuleExecution(
    val module: LiveModule,
    val output: List<String>,
    val changes: List<ChangeRecord>,
    val verification: VerificationReport,
    val reloadedAt: Long
)

sealed class ModulePatchResult {
    data class Switched(val module: LiveModule, val execution: ModuleExecution) : ModulePatchResult()
    data class Rejected(val reason: String) : ModulePatchResult()
}

class LiveModuleRuntime(
    private val workspace: ProjectWorkspace,
    private val knowledge: KnowledgeProvider,
    private val store: LiveModuleStore,
    // FIX: "replace_exact" previously only emitted a "PROPOSAL_REQUIRED" string and never
    // called anything — every live-module mutation silently did nothing to the project. It now
    // stages a real, checksum-verified proposal through the same dual-approval
    // MutationCoordinator every other write path in this app uses. Defaults to a private
    // coordinator so existing 3-arg constructor calls (including LiveUpdateTest.kt) keep
    // compiling unchanged. If this runtime is later wired into the UI, pass the app's shared
    // MutationCoordinator explicitly so live-module proposals surface in the same Review tab as
    // model-driven changes — with the default, they'd stage into an isolated coordinator no
    // approval UI ever sees.
    private val mutations: MutationCoordinator = MutationCoordinator(workspace)
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
                    val (path, oldText, newText) = parts
                    when (
                        val result = mutations.propose(
                            request = "Live module ${current.module.id} replace_exact $path",
                            operations = listOf(TaskOperation(OperationKind.REPLACE, path, oldText, newText)),
                            reason = "live-module ${current.module.id} step"
                        )
                    ) {
                        is MutationProposeResult.Proposed ->
                            output += "PROPOSAL_READY id=${result.proposal.id} path=$path " +
                                "changes=${result.proposal.changeSet.changes.size} approval_required=2 " +
                                "Confirm twice through MutationCoordinator to apply this change to disk."
                        is MutationProposeResult.Rejected ->
                            output += "ERROR: replace_exact proposal rejected — ${result.reason}"
                    }
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
