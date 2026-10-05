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
        val proposal = pending.singleOrNull()
            ?: return AgentRuntimeResult.Failed(
                task(
                    request = text,
                    summary = "Approval is ambiguous because multiple proposals are pending. Open Review and approve the intended proposal there.",
                    status = "approval-ambiguous",
                    proposalId = pending.joinToString(",") { it.id }
                )
            )

        return AgentRuntimeResult.NeedsApproval(
            task(
                request = text,
                summary = "Owner authentication is required before a proposal can be approved. Use the Confirm button in Review.",
                status = "waiting-owner-authentication",
                proposalId = proposal.id
            ),
            "Approval must be completed through the authenticated Review confirmation. Chat text cannot grant owner authority.",
            proposal.id
        )
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
