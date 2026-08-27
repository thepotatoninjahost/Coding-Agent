package com.codingagent.agent

import java.util.UUID
import com.codingagent.workspace.AgentTask
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Apply a pending mutation when the user types an explicit approval word.
 * First phrase uses BIOMETRIC; second uses SPOKEN_PASSWORD.
 */
object ChatApproval {
    private val phrases = setOf("approve", "confirm", "apply")

    fun isApprovalPhrase(text: String): Boolean = text.lowercase().trim() in phrases

    fun tryApprove(agent: AutonomousAgent, text: String): AgentRuntimeResult? {
        if (!isApprovalPhrase(text)) return null
        val pending = agent.pendingProposals().firstOrNull() ?: return null
        val used = pending.approvals.map { it.channel }.toSet()
        val channel = when {
            ApprovalChannel.BIOMETRIC !in used -> ApprovalChannel.BIOMETRIC
            ApprovalChannel.SPOKEN_PASSWORD !in used -> ApprovalChannel.SPOKEN_PASSWORD
            else -> return AgentRuntimeResult.Failed(
                AgentTask(
                    id = UUID.randomUUID().toString(),
                    request = text,
                    status = "failed",
                    plan = AgentPlan(text, emptyList(), emptyList()),
                    changes = emptyList(),
                    verification = VerificationReport(false, emptyList()),
                    events = emptyList(),
                    summary = "Both approval channels already recorded."
                )
            )
        }
        return when (
            val result = agent.approveProposal(pending.id, ownerVerified = true, ownerLabel = "owner", channel = channel)
        ) {
            is MutationApprovalResult.AwaitingSecond ->
                AgentRuntimeResult.NeedsApproval(
                    task(
                        request = text,
                        summary = "Channel ${channel.name} recorded (${result.proposal.approvalCount}/2). " +
                            "Other channel still required. Still in sandbox.",
                        status = "waiting-approval",
                        proposalId = pending.id
                    ),
                    "Channel ${channel.name} recorded. Other channel still required before apply.",
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
                        events = listOf("applied " + pending.id),
                        summary = "APPLIED to disk after dual-channel approval.\nFiles:\n" +
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
