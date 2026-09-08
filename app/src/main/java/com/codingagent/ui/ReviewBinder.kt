package com.codingagent.ui

import com.codingagent.workspace.ChangeDiff
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.PendingChangeProposal

/**
 * ONE JOB: Bind the Review tab to the real pending proposal on disk.
 */
data class ReviewBinding(
    val proposal: PendingChangeProposal?,
    val proposalId: String?,
    val pendingApproval: Boolean,
    val approvalCount: Int,
    val reason: String
)

object ReviewBinder {
    fun bind(coordinator: MutationCoordinator?, wantedId: String? = null): ReviewBinding {
        val pending = coordinator?.pending().orEmpty()
        val proposal = when {
            !wantedId.isNullOrBlank() -> coordinator?.get(wantedId) ?: pending.firstOrNull { it.id == wantedId } ?: pending.lastOrNull()
            else -> pending.lastOrNull()
        }
        return ReviewBinding(
            proposal = proposal,
            proposalId = proposal?.id,
            pendingApproval = proposal != null,
            approvalCount = proposal?.approvalCount ?: 0,
            reason = proposal?.let { ChangeDiff.ownerReviewText(it) }
                ?: "No staged file changes. The agent has not proposed a write yet."
        )
    }
}
