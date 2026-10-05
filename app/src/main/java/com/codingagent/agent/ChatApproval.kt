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
        val pending = agent.pendingProposals()
        if (pending.isEmpty()) return null
        if (pending.size > 1) {
            return AgentRuntimeResult.Failed(
                task(
                    request = text,
                    summary = "Approval is ambiguous because multiple proposals are pending. Open Review and approve the intended proposal there.",
                    status = "approval-ambiguous",
                    proposalId = pending.joinToString(",") { it.id }
                )
            )
        }
        val proposal = pending.single()
        return when (val result = agent.approveProposal(proposal.id, ownerVerified = true, ownerLabel = "owner")) {
            is MutationApprovalResult.AwaitingSecond ->
                AgentRuntimeResult.NeedsApproval(
                    task(
                        request = text,
                        summary = "First approval recorded. Type approve or confirm once more to write the files.",
                        status = "waiting-approval",
                        proposalId = proposal.id
                    ),
                    "First approval recorded. Type approve or confirm once more to write the files.",
                    proposal.id
                )
            is MutationApprovalResult.RepairRequired ->
                AgentRuntimeResult.NeedsApproval(
                    task(
                        request = text,
                        summary = "The approved change failed verification and was rolled back. A repair proposal is staged for dual approval: ${result.proposal.id}",
                        status = "repair-waiting-approval",
                        proposalId = result.proposal.id
                    ),
                    "The failed change was rolled back. Review and approve the staged repair proposal, then confirm again.",
                    result.proposal.id
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
                        events = listOf("applied ${proposal.id}"),
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
