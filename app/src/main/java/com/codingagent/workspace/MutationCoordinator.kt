package com.codingagent.workspace

import java.util.UUID
import com.codingagent.agent.AgentAction
import com.codingagent.agent.AgentActionCategory
import com.codingagent.agent.AgentConstitution
import com.codingagent.agent.ApprovalLedger
import com.codingagent.agent.ApprovalRecord
import com.codingagent.agent.ConstitutionRule
import com.codingagent.agent.SelfEvolution
import com.codingagent.agent.SelfRepair
import com.codingagent.agent.RepairCycleConfig
import com.codingagent.intake.TaskOperation
import com.codingagent.intake.TaskIntakeParser

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
    data class RepairRequired(val proposal: PendingChangeProposal, val failure: String) : MutationApprovalResult()
    data class Rejected(val reason: String) : MutationApprovalResult()
}

data class PendingChangeProposal(
    val id: String,
    val request: String,
    val changeSet: ChangeSet,
    val verification: VerificationReport,
    val createdAt: Long,
    val expiresAt: Long,
    val approvals: List<ApprovalRecord> = emptyList(),
    val repairAttempt: Int = 0,
    val repairRootRequest: String = request
) {
    val approvalCount: Int get() = approvals.size
}

class MutationCoordinator(
    internal val workspace: ProjectWorkspace,
    private val ledger: ApprovalLedger = ApprovalLedger(),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val repairConfig: RepairCycleConfig = RepairCycleConfig()
) {
    private val pending = linkedMapOf<String, PendingChangeProposal>()
    private val evolution = SelfEvolution(workspace.projectRoot())
    @Volatile private var repairProvider: ((String, Int) -> ChangeSet?)? = null

    fun setRepairProvider(provider: ((String, Int) -> ChangeSet?)?) {
        repairProvider = provider
    }

    init {
        OpenJobStore.bind(workspace.projectRoot())
        PendingProposalStore.load(workspace.projectRoot()).forEach { pending[it.id] = it }
        clearExpired()
    }

    @Synchronized
    fun propose(
        request: String,
        operations: List<TaskOperation>,
        reason: String = request
    ): MutationProposeResult {
        clearExpired()
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
        persist()
        runCatching {
            OpenJobStore.markWaiting(
                workspace.projectRoot(),
                proposal.id,
                proposal.changeSet.changes.map { it.path }.distinct(),
                request
            )
        }
        return MutationProposeResult.Proposed(proposal)
    }

    @Synchronized
    fun get(id: String): PendingChangeProposal? {
        clearExpired()
        return pending[id]
    }

    @Synchronized
    fun approve(id: String, ownerApproval: OwnerApprovalToken): MutationApprovalResult {
        clearExpired()
        val proposal = pending[id] ?: return MutationApprovalResult.Rejected("Change proposal does not exist")
        val timestamp = now()
        if (timestamp > proposal.expiresAt) {
            return MutationApprovalResult.Rejected(
                "Change proposal approval window expired. Open Review, reject, and the agent will restage the same files."
            )
        }
        if (!ownerApproval.consume(proposal.id, timestamp)) {
            return MutationApprovalResult.Rejected("A fresh authenticated owner approval is required for this proposal")
        }
        if (proposal.approvalCount >= 2) {
            return MutationApprovalResult.Rejected("This proposal already has the required two approvals")
        }
        val confirmationNumber = proposal.approvalCount + 1
        val approval = ledger.record(id, "device-owner", timestamp, confirmationNumber)
        val candidate = proposal.copy(approvals = proposal.approvals + approval)
        val action = AgentAction(
            description = proposal.request,
            category = AgentActionCategory.CODE_CHANGE,
            ownerVerified = true,
            approvalCount = candidate.approvalCount,
            sandboxPassed = proposal.verification.passed,
            clearPermission = true
        )
        val violations = AgentConstitution.check(action, timestamp, proposal.createdAt)
        if (violations.isNotEmpty()) {
            pending[id] = candidate
            persist()
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
            val intake = TaskIntakeParser(workspace.projectRoot()).parse(proposal.request)
            val postApply = if (intake.verificationCommands.isEmpty()) {
                workspace.verify()
            } else {
                workspace.runChecks(intake.verificationCommands, 180)
            }
            if (!postApply.passed) {
                val rollback = workspace.rollback(applied)
                if (rollback == RollbackResult.Restored) {
                    pending.remove(id)
                    persist()
                    OpenJobStore.markReady(workspace.projectRoot())
                }
                val details = buildString {
                    append("Approved change failed post-apply checks")
                    if (postApply.commands.isNotEmpty()) {
                        append(". Commands: ")
                        append(postApply.commands.joinToString("; ") { result ->
                            "${result.command} (exit ${result.exitCode})"
                        })
                    }
                    if (postApply.issues.isNotEmpty()) {
                        append(". Issues: ")
                        append(postApply.issues.joinToString { "${it.path}:${it.line}: ${it.message}" })
                    }
                }
                if (rollback == RollbackResult.Restored) {
                    val nextRepairAttempt = proposal.repairAttempt + 1
                    if (nextRepairAttempt <= repairConfig.maxRepairAttempts) {
                        val repair = runCatching {
                            repairProvider?.let { provider ->
                                com.codingagent.agent.CompilerTestRepairCycle(workspace).prepareRepair(
                                    postApply,
                                    attempt = nextRepairAttempt,
                                    repair = provider
                                )
                            }
                        }.getOrNull()
                        if (repair != null) {
                            val repairProposal = stageRepairProposal(
                                rootRequest = proposal.repairRootRequest,
                                repairAttempt = nextRepairAttempt,
                                changeSet = repair
                            )
                            if (repairProposal != null) {
                                return MutationApprovalResult.RepairRequired(repairProposal, details)
                            }
                        }
                    }
                    return MutationApprovalResult.Rejected("$details; changes were rolled back")
                }
                return MutationApprovalResult.Rejected(
                    "$details; rollback was incomplete: $rollback"
                )
            }
            pending.remove(id)
            persist()
            runCatching { OpenJobStore.markApplied(workspace.projectRoot()) }
            recordEvolution(candidate, applied)
            MutationApprovalResult.Applied(candidate, applied)
        } catch (error: Exception) {
            MutationApprovalResult.Rejected("Approved change could not be applied: ${error.message.orEmpty()}")
        }
    }

    @Synchronized
    fun pending(): List<PendingChangeProposal> {
        clearExpired()
        return pending.values.toList()
    }

    @Synchronized
    fun clear(id: String): Boolean {
        val gone = pending.remove(id) != null
        if (gone) {
            persist()
            OpenJobStore.markReady(workspace.projectRoot())
        }
        return gone
    }

    @Synchronized
    fun reject(id: String): Boolean {
        val gone = pending.remove(id) != null
        if (gone) {
            persist()
            OpenJobStore.markReady(workspace.projectRoot())
        }
        return gone
    }

    @Synchronized
    fun clearExpired() {
        val timestamp = now()
        val expiredIds = pending.values
            .filter { timestamp > it.expiresAt }
            .map { it.id }
        if (expiredIds.isEmpty()) return
        expiredIds.forEach { pending.remove(it) }
        persist()
        OpenJobStore.markReady(workspace.projectRoot())
    }

    @Synchronized
    fun refreshExpiry(id: String): PendingChangeProposal? {
        clearExpired()
        val current = pending[id] ?: return null
        val refreshed = current.copy(expiresAt = now() + AgentConstitution.APPROVAL_EXPIRATION_MS)
        pending[id] = refreshed
        persist()
        return refreshed
    }

    private fun stageRepairProposal(
        rootRequest: String,
        repairAttempt: Int,
        changeSet: ChangeSet
    ): PendingChangeProposal? {
        val verification = runCatching { workspace.verifyProposal(changeSet) }.getOrNull() ?: return null
        if (!verification.passed || changeSet.changes.isEmpty()) return null
        val timestamp = now()
        val proposal = PendingChangeProposal(
            id = UUID.randomUUID().toString(),
            request = "Self-repair attempt $repairAttempt after failed change: ${rootRequest.take(180)}",
            changeSet = changeSet,
            verification = verification,
            createdAt = timestamp,
            expiresAt = timestamp + AgentConstitution.APPROVAL_EXPIRATION_MS,
            repairAttempt = repairAttempt,
            repairRootRequest = rootRequest
        )
        pending[proposal.id] = proposal
        persist()
        runCatching {
            OpenJobStore.markWaiting(
                workspace.projectRoot(),
                proposal.id,
                changeSet.changes.map { it.path }.distinct(),
                proposal.request
            )
        }
        return proposal
    }

    private fun recordEvolution(proposal: PendingChangeProposal, changeSet: ChangeSet) {
        runCatching {
            val latestApproval = proposal.approvals.maxOfOrNull { it.approvedAt }
            val kind = if (SelfRepair.isRequest(proposal.request)) "self-repair" else "code-change"
            changeSet.changes.forEach { change ->
                val file = workspace.projectRoot().resolve(change.path)
                if (!file.isFile) return@forEach
                val staged = evolution.stageSource(file, kind)
                val action = AgentAction(
                    description = proposal.request,
                    category = AgentActionCategory.CODE_CHANGE,
                    ownerVerified = true,
                    approvalCount = proposal.approvalCount,
                    sandboxPassed = proposal.verification.passed,
                    clearPermission = true
                )
                evolution.promoteSource(staged, kind, proposal.verification, action, latestApproval)
            }
        }
    }

    private fun persist() {
        runCatching { PendingProposalStore.save(workspace.projectRoot(), pending.values.toList()) }
    }
}
