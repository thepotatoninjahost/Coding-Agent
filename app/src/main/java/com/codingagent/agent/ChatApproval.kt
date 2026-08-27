package com.codingagent.agent

import java.util.UUID
import com.codingagent.workspace.AgentTask
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Treat chat phrases approve/confirm/yes/apply/ok as a dual-approval tap.
 */
object ChatApproval {
    private val phrases = setOf("approve", "confirm", "yes", "apply", "ok")

    fun isApprovalPhrase(text: String): Boolean = text.lowercase().trim() in phrases

    fun tryApprove(agent: AutonomousAgent, text: String): AgentRuntimeResult? {
        if (!isApprovalPhrase(text)) return null
        val pending = agent.pendingProposals().firstOrNull()
            ?: return done("No pending proposal to approve.")
        return when (val result = agent.approveProposal(pending.id, ownerVerified = true, ownerLabel = "owner")) {
            is MutationApprovalResult.AwaitingSecond ->
                done("First approval recorded. Confirm once more to write the files.", pending.id)
            is MutationApprovalResult.Applied -> {
                val paths = result.changeSet.changes.map { it.path }.distinct()
                done(
                    "APPLIED to disk after dual approval.\nFiles:\n" +
                        paths.joinToString("\n") { "- $it" },
                    pending.id
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

    private fun done(summary: String, proposalId: String? = null): AgentRuntimeResult {
        val task = AgentTask(
            id = UUID.randomUUID().toString(),
            request = "approve",
            status = "completed",
            plan = AgentPlan("approve", emptyList(), emptyList()),
            changes = emptyList(),
            verification = VerificationReport(true, emptyList()),
            events = emptyList(),
            summary = summary
        )
        return if (summary.startsWith("First approval")) {
            AgentRuntimeResult.NeedsApproval(task, summary, proposalId.orEmpty())
        } else {
            AgentRuntimeResult.Completed(task)
        }
    }
}
