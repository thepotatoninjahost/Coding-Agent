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
    /**
     * Chat text is never an approval credential. Do not define default approval
     * words or passphrases here: only the authenticated Review flow may authorize
     * a proposal, regardless of what the user types or dictates into chat.
     */
    fun isApprovalPhrase(text: String): Boolean = false

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
                proposalId = proposal.id,
                verification = proposal.verification
            ),
            "Approval must be completed through the authenticated Review confirmation. Chat text cannot grant owner authority.",
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
