package com.codingagent.agent

import java.util.UUID
import com.codingagent.workspace.AgentTask
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Apply a pending mutation when the user types an explicit approval word.
 * Returns null when this turn is not an approval, so chat continues normally.
 */
object ChatApproval {
    private val phrases = setOf("approve", "confirm", "apply")

    fun isApprovalPhrase(text: String): Boolean = text.lowercase().trim() in phrases

    fun tryApprove(agent: AutonomousAgent, text: String): AgentRuntimeResult? {
        if (!isApprovalPhrase(text)) return null
        val pending = agent.pendingProposals().firstOrNull() ?: return null
        return when (val result = agent.approveProposal(pending.id, ownerVerified = true, ownerLabel = "owner")) {
            is MutationApprovalResult.AwaitingSecond ->
                AgentRuntimeResult.NeedsApproval(
                    task(
                        request = text,
                        summary = "First approval recorded. Type approve or confirm once more to write the files.",
                        status = "waiting-approval",
                        proposalId = pending.id
                    ),
                    "First approval recorded. Type approve or confirm once more to write the files.",
                    pending.id
                )
            is MutationApprovalResult.Applied -> {
                val paths = result.changeSet.changes.map { it.path }.distinct()
                AgentRuntimeResult.Completed(
                    AgentTask(
                        id = UUID.randomUUID().toString(),
                        request = text,
                        status = "completed",
                        plan = AgentPlan(text, emptyList(), emptyList()),
                        changes = result.changeSet.changes,
                        verification = VerificationReport(true, emptyList()),
                        events = listOf("applied ${pending.id}"),
                        summary = "APPLIED to disk after dual approval.\nFiles:\n" +
                            paths.joinToString("\n") { "- $it" }
                    )
                )
            }
            is MutationApprovalResult.Rejected ->
                AgentRuntimeResult.Failed(
                    AgentTask(
                        id = UUID.randomUUID().toString(),
                        request = text,
                        status = "failed",
                        plan = AgentPlan(text, emptyList(), emptyList()),
                        changes = emptyList(),
                        verification = VerificationReport(false, emptyList()),
                        events = emptyList(),
                        summary = "Approval rejected: ${result.reason}"
                    )
                )
        }
    }

    private fun task(
        request: String,
        summary: String,
        status: String,
        proposalId: String
    ): AgentTask = AgentTask(
        id = UUID.randomUUID().toString(),
        request = request,
        status = status,
        plan = AgentPlan(request, emptyList(), emptyList()),
        changes = emptyList(),
        verification = VerificationReport(true, emptyList()),
        events = listOf(proposalId),
        summary = summary
    )
}
