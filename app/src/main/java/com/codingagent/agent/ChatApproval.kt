package com.codingagent.agent

import java.util.UUID
import com.codingagent.workspace.AgentTask
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Route approval phrases to the authenticated Review flow.
 * Chat text never grants owner authority.
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
                    summary = "More than one change is waiting. Open Review and say yes to the one you mean.",
                    status = "approval-ambiguous",
                    proposalId = pending.joinToString(",") { it.id }
                )
            )

        return AgentRuntimeResult.NeedsApproval(
            task(
                request = text,
                summary = "Saying yes in chat is not enough. Open Review and say yes there, with your saved words and your phone's lock.",
                status = "waiting-owner-authentication",
                proposalId = proposal.id,
                verification = proposal.verification
            ),
            "Open Review and say yes there. A chat message cannot approve a change by itself.",
            proposal.id
        )
    }

    private fun task(
        request: String,
        summary: String,
        status: String,
        proposalId: String,
        verification: VerificationReport = VerificationReport(false, emptyList())
    ): AgentTask = AgentTask(
        id = UUID.randomUUID().toString(),
        request = request,
        status = status,
        plan = AgentPlan(request, emptyList(), emptyList()),
        changes = emptyList(),
        verification = verification,
        events = listOf(proposalId),
        summary = summary
    )
}
