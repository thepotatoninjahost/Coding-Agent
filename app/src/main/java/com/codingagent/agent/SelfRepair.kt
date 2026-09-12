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
 * "fix yourself" / "modify yourself" is not a toy file drop. When the imported project
 * already contains agent sources, this stages a dual-approval replace on the first
 * unstamped priority file. When every priority file is already stamped, this returns
 * null so the model loop can do the real edit. An empty project only bootstraps
 * src/SelfRepair.kt so dual-approval still has a concrete first change.
 */
object SelfRepair {

    const val CONTRACT_STAMP = "SELF_REPAIR_CONTRACT"

    val PRIORITY_FILES = listOf(
        "ChatWorkspace.kt",
        "AutonomousAgent.kt",
        "AgentConstitution.kt",
        "ToolCallOutcomeHandler.kt",
        "SelfEvolution.kt",
        "SelfRepair.kt"
    )

    private val PHRASES = Regex(
        """\\b(fix yourself|modify yourself|self[\\-\\s]?repair|repair yourself|update yourself|improve yourself)\\b""",
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
        val indexed = workspace.summary().files
        for (candidate in PRIORITY_FILES) {
            val found = indexed.firstOrNull { it.path == candidate || it.path.endsWith("/$candidate") }
                ?: continue
            val content = runCatching { files.read(found.path).content }.getOrNull() ?: continue
            if (content.contains(CONTRACT_STAMP)) continue
            val stamped = content.trimEnd() + "\n// $CONTRACT_STAMP: agent-reviewed\n"
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
        val targeted = chooseOperation(workspace, files, workspace.verify())
        if (targeted != null) {
            return propose(
                taskId = taskId,
                request = request,
                plan = plan,
                mutations = mutations,
                operations = listOf(targeted),
                reason = "Self-repair: stamp ${targeted.path}",
                summary = "Self-repair proposal staged for ${targeted.path}. Confirm twice to apply."
            )
        }

        val hasAgentSources = workspace.summary().files.any { indexed ->
            PRIORITY_FILES.any { name -> indexed.path == name || indexed.path.endsWith("/$name") }
        }
        if (hasAgentSources) {
            return null
        }

        val seed = seedSource()
        val op = TaskOperation(
            kind = OperationKind.CREATE_FILE,
            path = "src/SelfRepair.kt",
            text = seed
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
        appendLine("    const val CONTRACT_STAMP = \"$CONTRACT_STAMP\"")
        appendLine()
        appendLine("    private val PHRASES = Regex(")
        appendLine("        \"\"\"\\\\b(fix yourself|modify yourself|self[\\\\-\\\\s]?repair|repair yourself|update yourself|improve yourself)\\\\b\"\"\",")
        appendLine("        RegexOption.IGNORE_CASE")
        appendLine("    )")
        appendLine()
        appendLine("    fun isRequest(text: String): Boolean = PHRASES.containsMatchIn(text)")
        appendLine("}")
        appendLine("// $CONTRACT_STAMP: agent-reviewed")
    }
}
