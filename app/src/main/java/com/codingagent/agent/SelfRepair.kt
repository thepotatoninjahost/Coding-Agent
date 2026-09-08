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
 */
object SelfRepair {

    private val PHRASES = Regex(
        """\b(fix yourself|modify yourself|self[\-\s]?repair|repair yourself|update yourself|improve yourself)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Returns true when [text] is a self-repair request. */
    fun isRequest(text: String): Boolean = PHRASES.containsMatchIn(text)

    /**
     * Choose the best repair operation for the current workspace state.
     * Priority: well-known agent files that lack the contract stamp.
     */
    fun chooseOperation(
        workspace: ProjectWorkspace,
        files: ProjectFileService,
        verification: VerificationReport
    ): TaskOperation? {
        val priority = listOf(
            "ChatWorkspace.kt",
            "AutonomousAgent.kt",
            "AgentConstitution.kt",
            "ToolCallOutcomeHandler.kt"
        )
        val indexed = workspace.summary().files
        for (candidate in priority) {
            val found = indexed.firstOrNull { it.path == candidate || it.path.endsWith("/$candidate") }
                ?: continue
            val content = runCatching { files.read(found.path).content }.getOrNull() ?: continue
            if (content.contains("SELF_REPAIR_CONTRACT")) continue
            val stamped = content.trimEnd() + "\n// SELF_REPAIR_CONTRACT: agent-reviewed\n"
            return TaskOperation(
                kind = OperationKind.REPLACE,
                path = found.path,
                oldText = content,
                newText = stamped
            )
        }
        return null
    }

    /**
     * Stage a self-repair proposal for dual-owner approval.
     * Always targets src/SelfRepair.kt so the agent can evolve its own repair logic.
     */
    fun handle(
        taskId: String,
        request: String,
        plan: AgentPlan,
        workspace: ProjectWorkspace,
        files: ProjectFileService,
        mutations: MutationCoordinator
    ): AgentTask {
        val selfRepairSource = buildString {
            appendLine("package com.codingagent.agent")
            appendLine()
            appendLine("/**")
            appendLine(" * ONE JOB: Detect and stage self-repair mutations for the agent source tree.")
            appendLine(" */")
            appendLine("object SelfRepair {")
            appendLine("    private val PHRASES = Regex(")
            appendLine("        \"\"\"\\\\b(fix yourself|modify yourself|self[\\\\-\\\\s]?repair)\\\\b\"\"\",")
            appendLine("        RegexOption.IGNORE_CASE")
            appendLine("    )")
            appendLine()
            appendLine("    fun isRequest(text: String): Boolean = PHRASES.containsMatchIn(text)")
            appendLine("}")
        }

        val op = TaskOperation(
            kind = OperationKind.CREATE_FILE,
            path = "src/SelfRepair.kt",
            text = selfRepairSource
        )

        return when (val result = mutations.propose(request, listOf(op), "Self-repair: stage src/SelfRepair.kt")) {
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
                    summary = "Self-repair proposal staged. Confirm twice to apply src/SelfRepair.kt."
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
}
