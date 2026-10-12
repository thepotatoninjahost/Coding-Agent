package com.codingagent.agent

import java.time.Instant
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation
import com.codingagent.workspace.AgentTask
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.MutationProposeResult
import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Detect and stage self-repair requests — mutations to the agent's own source tree.
 *
 * Requests against an existing agent tree are delegated to the normal model/tool path;
 * an empty project can bootstrap a repair entry point under dual-owner approval.
 */
object SelfRepair {
    val PRIORITY_FILES = listOf(
        "ChatWorkspace.kt",
        "AutonomousAgent.kt",
        "AgentConstitution.kt",
        "ToolCallOutcomeHandler.kt",
        "SelfEvolution.kt",
        "SelfRepair.kt"
    )

    private val PHRASES = Regex(
        "\\b(fix yourself|modify yourself|self[-\\s]?(repair|heal|healing|modification|modify)|repair yourself|heal yourself|update yourself|improve yourself|fix your own code|modify your own (code|source|files)|repair your own code|self[-\\s]?improvement)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Returns true when [text] is a self-repair or self-modification request. */
    fun isRequest(text: String): Boolean = PHRASES.containsMatchIn(text)

    /**
     * Stage a self-repair proposal for dual-owner approval, or return null so the
     * autonomous loop can edit the agent's own sources with tools.
     */
    fun handle(
        taskId: String,
        request: String,
        plan: AgentPlan,
        workspace: ProjectWorkspace,
        files: ProjectFileService,
        mutations: MutationCoordinator
    ): AgentTask? {
        val hasAgentSources = workspace.summary().files.any { indexed ->
            PRIORITY_FILES.any { name -> indexed.path == name || indexed.path.endsWith("/$name") }
        }
        if (hasAgentSources) return null

        val op = TaskOperation(
            kind = OperationKind.CREATE_FILE,
            path = "src/SelfRepair.kt",
            text = seedSource()
        )
        return propose(
            taskId = taskId,
            request = request,
            plan = plan,
            mutations = mutations,
            operations = listOf(op),
            reason = "Self-repair: bootstrap src/SelfRepair.kt",
            summary = "Self-repair proposal staged. Confirm twice to apply src/SelfRepair.kt."
        )
    }

    private fun propose(
        taskId: String,
        request: String,
        plan: AgentPlan,
        mutations: MutationCoordinator,
        operations: List<TaskOperation>,
        reason: String,
        summary: String
    ): AgentTask {
        return when (val result = mutations.propose(request, operations, reason)) {
            is MutationProposeResult.Proposed -> {
                val proposal = result.proposal
                AgentTask(
                    id = taskId,
                    request = request,
                    status = "needs-approval",
                    plan = plan,
                    changes = proposal.changeSet.changes,
                    verification = proposal.verification,
                    events = listOf("${Instant.now()}: self-repair proposal ${proposal.id} staged; awaiting dual approval"),
                    summary = summary
                )
            }
            is MutationProposeResult.Rejected -> AgentTask(
                id = taskId,
                request = request,
                status = "failed",
                plan = plan,
                changes = emptyList(),
                verification = VerificationReport(false, emptyList()),
                events = listOf("${Instant.now()}: self-repair proposal rejected: ${result.reason}"),
                summary = "Self-repair staging failed: ${result.reason}"
            )
        }
    }

    private fun seedSource(): String = buildString {
        appendLine("package com.codingagent.agent")
        appendLine()
        appendLine("/**")
        appendLine(" * ONE JOB: Detect self-repair requests against the agent source tree.")
        appendLine(" */")
        appendLine("object SelfRepair {")
        appendLine()
        appendLine("    private val PHRASES = Regex(")
        appendLine("        \"\\\\b(fix yourself|modify yourself|self[-\\\\s]?repair|repair yourself|update yourself|improve yourself)\\\\b\",")
        appendLine("        RegexOption.IGNORE_CASE")
        appendLine("    )")
        appendLine()
        appendLine("    fun isRequest(text: String): Boolean = PHRASES.containsMatchIn(text)")
        appendLine("}")
    }
}