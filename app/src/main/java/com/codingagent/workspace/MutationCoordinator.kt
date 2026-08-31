package com.codingagent.workspace

import java.util.UUID
import com.codingagent.agent.AgentAction
import com.codingagent.agent.AgentActionCategory
import com.codingagent.agent.AgentConstitution
import com.codingagent.agent.ApprovalLedger
import com.codingagent.agent.ApprovalRecord
import com.codingagent.agent.ConstitutionRule
import com.codingagent.intake.TaskOperation

/**
 * ONE JOB: Dual-approval staging, constitution checks, and apply/reject of code changes.
 */
sealed class MutationProposeResult {
    data class Proposed(val proposal: PendingChangeProposal) : MutationProposeResult()
    data class Rejected(val reason: String) : MutationProposeResult()
}

sealed class MutationApprovalResult {
    data class AwaitingSecond(val proposal: PendingChangeProposal, val approval: ApprovalRecord) : MutationApprovalResult()
    data class Applied(val proposal: PendingChangeProposal, val changeSet: ChangeSet) : MutationApprovalResult()
    data class Rejected(val reason: String) : MutationApprovalResult()
}

data class PendingChangeProposal(
    val id: String,
    val request: String,
    val changeSet: ChangeSet,
    val verification: VerificationReport,
    val createdAt: Long,
    val expiresAt: Long,
    val approvals: List<ApprovalRecord> = emptyList()
) {
    val approvalCount: Int get() = approvals.size
}

class MutationCoordinator(
    // internal so AutonomousAgent can derive its own workspace reference from the shared instance.
    internal val workspace: ProjectWorkspace,
    private val ledger: ApprovalLedger = ApprovalLedger(),
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val pending = linkedMapOf<String, PendingChangeProposal>()

    /**
     * Stage a mutation for dual approval.
     * Returns [MutationProposeResult.Rejected] on any validation failure — never throws.
     */
    @Synchronized
    fun propose(
        request: String,
        operations: List<TaskOperation>,
        reason: String = request
    ): MutationProposeResult {
        if (request.isBlank()) return MutationProposeResult.Rejected("A mutation request is required")
        if (operations.isEmpty()) return MutationProposeResult.Rejected("At least one mutation operation is required")

        val changeSet = runCatching { workspace.preview(operations, reason) }
            .getOrElse { ex ->
                return MutationProposeResult.Rejected(
                    "Preview failed: ${ex.message.orEmpty().ifBlank { ex.javaClass.simpleName }}"
                )
            }

        if (changeSet.changes.isEmpty()) return MutationProposeResult.Rejected("Mutation proposal contains no changes")

        val verification = runCatching { workspace.verifyProposal(changeSet) }
            .getOrElse { ex ->
                return MutationProposeResult.Rejected(
                    "Verification failed: ${ex.message.orEmpty().ifBlank { ex.javaClass.simpleName }}"
                )
            }

        if (!verification.passed) {
            return MutationProposeResult.Rejected(
                "Mutation proposal failed verification: ${verification.issues.joinToString { it.message }}"
            )
        }

        val timestamp = now()
        val proposal = PendingChangeProposal(
            id = UUID.randomUUID().toString(),
            request = request,
            changeSet = changeSet,
            verification = verification,
            createdAt = timestamp,
            expiresAt = timestamp + AgentConstitution.APPROVAL_EXPIRATION_MS
        )
        pending[proposal.id] = proposal
        return MutationProposeResult.Proposed(proposal)
    }

    @Synchronized
    fun get(id: String): PendingChangeProposal? = pending[id]

    @Synchronized
    fun approve(id: String, ownerVerified: Boolean, ownerLabel: String): MutationApprovalResult {
        val proposal = pending[id] ?: return MutationApprovalResult.Rejected("Change proposal does not exist")
        val timestamp = now()
        if (timestamp > proposal.expiresAt) {
            pending.remove(id)
            return MutationApprovalResult.Rejected("Change proposal approval expired")
        }
        if (!ownerVerified) return MutationApprovalResult.Rejected("Owner verification is required for every approval")
        val approval = ledger.record(id, ownerLabel, timestamp)
        val candidate = proposal.copy(approvals = proposal.approvals + approval)
        val action = AgentAction(
            description = proposal.request,
            category = AgentActionCategory.CODE_CHANGE,
            ownerVerified = ownerVerified,
            approvalCount = candidate.approvalCount,
            sandboxPassed = proposal.verification.passed,
            clearPermission = true
        )
        val violations = AgentConstitution.check(action, timestamp, proposal.createdAt)
        if (violations.isNotEmpty()) {
            pending[id] = candidate
            return if (candidate.approvalCount < 2 && violations.none {
                    it.rule == ConstitutionRule.OWNER_LOCK ||
                        it.rule == ConstitutionRule.SANDBOX_FIRST ||
                        it.rule == ConstitutionRule.PERMISSION_EXPIRATION
                }
            ) {
                MutationApprovalResult.AwaitingSecond(candidate, approval)
            } else {
                MutationApprovalResult.Rejected(violations.joinToString("; ") { "${it.rule}: ${it.message}" })
            }
        }
        return try {
            val applied = workspace.applyApproved(proposal.changeSet)
            pending.remove(id)
            MutationApprovalResult.Applied(candidate, applied)
        } catch (error: Exception) {
            MutationApprovalResult.Rejected("Approved change could not be applied: ${error.message.orEmpty()}")
        }
    }

    @Synchronized
    fun pending(): List<PendingChangeProposal> = pending.values.toList()

    @Synchronized
    fun clear(id: String): Boolean = pending.remove(id) != null

    @Synchronized
    fun reject(id: String): Boolean = pending.remove(id) != null

    @Synchronized
    fun clearExpired() {
        val timestamp = now()
        pending.entries.removeIf { timestamp > it.value.expiresAt }
    }
}
