package com.codingagent.workspace

import java.util.UUID
import java.io.File
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
        reconcileDurableApplyState()
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
        if (OpenJobStore.load(workspace.projectRoot())?.status == "recovery-required") {
            return MutationProposeResult.Rejected("This job requires recovery before another mutation can be staged")
        }

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
        if (!persist()) {
            pending.remove(proposal.id)
            return MutationProposeResult.Rejected("Could not durably persist the change proposal; no mutation was staged")
        }
        try {
            OpenJobStore.markWaiting(
                workspace.projectRoot(),
                proposal.id,
                proposal.changeSet.changes.map { it.path }.distinct(),
                request
            )
        } catch (_: Exception) {
            pending.remove(proposal.id)
            persist()
            return MutationProposeResult.Rejected(
                "Could not durably record the pending job state; no mutation was staged"
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
        val openJob = OpenJobStore.load(workspace.projectRoot())
        when {
            openJob?.status == "recovery-required" -> return MutationApprovalResult.Rejected("This job requires recovery before another approval can be accepted")
            openJob?.status == "applying" && openJob.proposalId == id -> return MutationApprovalResult.Rejected("This proposal is already in the apply phase; recover the job before approving again")
            openJob?.status == "applied" && openJob.appliedProposalId == id -> {
                pending.remove(id)
                persist()
                return MutationApprovalResult.Rejected("This proposal has already been applied")
            }
        }
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
            val blockingBeyondSecondConfirmation = violations.any {
                it.blocking && it.rule != ConstitutionRule.DOUBLE_CONFIRMATION
            }
            if (blockingBeyondSecondConfirmation) {
                // A rejected approval is never persisted. The proposal remains exactly as it
                // was before this attempt, so a later valid approval cannot inherit invalid
                // authorization state.
                return MutationApprovalResult.Rejected(
                    violations.joinToString("; ") { "${it.rule}: ${it.message}" }
                )
            }

            val previous = pending[id]
            pending[id] = candidate
            if (!persist()) {
                if (previous != null) pending[id] = previous else pending.remove(id)
                return MutationApprovalResult.Rejected("Could not durably persist owner approval; approval was not accepted")
            }
            return MutationApprovalResult.AwaitingSecond(candidate, approval)
        }
        var applied: ChangeSet? = null
        return try {
            try {
                OpenJobStore.markApplying(workspace.projectRoot(), proposal.id, proposal.changeSet.changes.map { it.path }.distinct(), proposal.request)
            } catch (_: Exception) {
                return MutationApprovalResult.Rejected("Could not durably enter the apply phase; no mutation was attempted")
            }

            val appliedChangeSet = workspace.applyApproved(proposal.changeSet)
            applied = appliedChangeSet
            val intake = TaskIntakeParser(workspace.projectRoot()).parse(proposal.request)
            val postApply = if (intake.verificationCommands.isEmpty()) {
                workspace.verify()
            } else {
                workspace.runChecks(intake.verificationCommands, 180)
            }
            if (!postApply.passed) {
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
                val rollback = workspace.rollback(appliedChangeSet)
                if (rollback == RollbackResult.Restored) {
                    pending.remove(id)
                    if (!persist()) {
                        pending[id] = proposal
                        runCatching { OpenJobStore.markReady(workspace.projectRoot()) }
                        return MutationApprovalResult.Rejected(
                            "$details; rollback restored files, but pending-state persistence failed"
                        )
                    }
                    runCatching { OpenJobStore.markReady(workspace.projectRoot()) }
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
            try {
                OpenJobStore.markApplied(workspace.projectRoot(), proposal.id)
            } catch (_: Exception) {
                val rollback = runCatching { workspace.rollback(applied) }.getOrElse { RollbackResult.Rejected(it.message.orEmpty()) }
                if (rollback == RollbackResult.Restored) {
                    runCatching { OpenJobStore.markReady(workspace.projectRoot()) }
                    return MutationApprovalResult.Rejected("Change was rolled back because completed-job persistence failed")
                }
                return MutationApprovalResult.Rejected("CRITICAL: completed-job persistence failed and rollback was incomplete: $rollback")
            }

            pending.remove(id)
            if (!persist()) {
                pending[id] = proposal
                val rollback = runCatching { workspace.rollback(applied) }.getOrElse { RollbackResult.Rejected(it.message.orEmpty()) }
                return if (rollback == RollbackResult.Restored) {
                    runCatching { OpenJobStore.markReady(workspace.projectRoot()) }
                    MutationApprovalResult.Rejected("Change was rolled back because approved-state persistence failed")
                } else {
                    MutationApprovalResult.Rejected(
                        "CRITICAL: change was applied but could not be durably persisted or rolled back: $rollback"
                    )
                }
            }
            recordEvolution(candidate, appliedChangeSet)
            MutationApprovalResult.Applied(candidate, appliedChangeSet)
        } catch (error: Exception) {
            val completedChangeSet = applied
            if (completedChangeSet != null) {
                val rollback = runCatching { workspace.rollback(completedChangeSet) }
                    .getOrElse { RollbackResult.Rejected(it.message.orEmpty()) }
                if (rollback == RollbackResult.Restored) {
                    val ready = runCatching { OpenJobStore.markReady(workspace.projectRoot()) }.isSuccess
                    if (!ready) {
                        runCatching {
                            OpenJobStore.markRecoveryRequired(
                                workspace.projectRoot(),
                                "An approved mutation was rolled back after an unexpected verification failure, but ready-state persistence failed"
                            )
                        }
                    }
                    return MutationApprovalResult.Rejected(
                        "Approved change was rolled back after an unexpected failure: ${error.message.orEmpty()}"
                    )
                }
                runCatching {
                    OpenJobStore.markRecoveryRequired(
                        workspace.projectRoot(),
                        "An approved mutation could not be rolled back after an unexpected failure"
                    )
                }
                return MutationApprovalResult.Rejected(
                    "CRITICAL: approved change could not be safely rolled back: $rollback"
                )
            }

            when (diskState(proposal.changeSet)) {
                DiskState.BEFORE -> runCatching { OpenJobStore.markReady(workspace.projectRoot()) }
                DiskState.AFTER -> {
                    val markedApplied = runCatching {
                        OpenJobStore.markApplied(workspace.projectRoot(), proposal.id)
                    }.isSuccess
                    if (markedApplied) {
                        pending.remove(id)
                        if (persist()) {
                            return MutationApprovalResult.Applied(candidate, proposal.changeSet)
                        }
                        pending[id] = proposal
                    }
                }
                DiskState.MIXED -> runCatching {
                    OpenJobStore.markRecoveryRequired(
                        workspace.projectRoot(),
                        "An approved mutation failed during application and left a mixed on-disk state"
                    )
                }
            }
            MutationApprovalResult.Rejected("Approved change could not be safely completed: ${error.message.orEmpty()}")
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
        if (gone && !persist()) {
            return false
        }
        if (gone) {
            runCatching { OpenJobStore.markReady(workspace.projectRoot()) }
        }
        return gone
    }

    @Synchronized
    fun reject(id: String): Boolean {
        val gone = pending.remove(id) != null
        if (gone && !persist()) {
            return false
        }
        if (gone) {
            runCatching { OpenJobStore.markReady(workspace.projectRoot()) }
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
        val expired = expiredIds.mapNotNull { id -> pending.remove(id)?.let { id to it } }
        if (!persist()) {
            expired.forEach { (id, proposal) -> pending[id] = proposal }
            return
        }
        runCatching { OpenJobStore.markReady(workspace.projectRoot()) }
    }

    @Synchronized
    fun refreshExpiry(id: String): PendingChangeProposal? {
        clearExpired()
        val current = pending[id] ?: return null
        val refreshed = current.copy(expiresAt = now() + AgentConstitution.APPROVAL_EXPIRATION_MS)
        pending[id] = refreshed
        if (!persist()) {
            pending[id] = current
            return current
        }
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
        if (!persist()) {
            pending.remove(proposal.id)
            return null
        }
        try {
            OpenJobStore.markWaiting(
                workspace.projectRoot(),
                proposal.id,
                changeSet.changes.map { it.path }.distinct(),
                proposal.request
            )
        } catch (_: Exception) {
            pending.remove(proposal.id)
            persist()
            return null
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

    private fun reconcileDurableApplyState() {
        val root = workspace.projectRoot()
        val job = OpenJobStore.load(root) ?: return
        val proposalId = job.proposalId ?: job.appliedProposalId ?: return
        val proposal = pending[proposalId] ?: return

        when (job.status) {
            "applying" -> when (diskState(proposal.changeSet)) {
                DiskState.BEFORE -> runCatching { OpenJobStore.markReady(root) }
                DiskState.AFTER -> {
                    runCatching { OpenJobStore.markApplied(root, proposal.id) }
                        .onSuccess {
                            pending.remove(proposal.id)
                            if (!persist()) pending[proposal.id] = proposal
                        }
                }
                DiskState.MIXED -> runCatching {
                    OpenJobStore.markRecoveryRequired(root, "A mutation transaction was interrupted with a partially applied file set")
                }
            }
            "applied" -> when (diskState(proposal.changeSet)) {
                DiskState.AFTER -> {
                    pending.remove(proposal.id)
                    if (!persist()) pending[proposal.id] = proposal
                }
                DiskState.BEFORE, DiskState.MIXED -> runCatching {
                    OpenJobStore.markRecoveryRequired(root, "The job is marked applied but its recorded proposal does not match the on-disk checksums")
                }
            }
        }
    }

    private enum class DiskState { BEFORE, AFTER, MIXED }

    private fun diskState(changeSet: ChangeSet): DiskState {
        var before = 0
        var after = 0
        for (record in changeSet.changes) {
            val file = try {
                val candidate = rootSafeFile(record.path)
                val rootPath = workspace.projectRoot().canonicalFile.toPath()
                val canonical = candidate.canonicalFile.toPath()
                require(canonical.startsWith(rootPath)) { "Unsafe recovery path: " + record.path }
                candidate
            } catch (_: Exception) {
                return DiskState.MIXED
            }
            val checksum = if (file.isFile) {
                runCatching { FileIntegrity.sha256(file.readText(Charsets.UTF_8)) }.getOrElse { return DiskState.MIXED }
            } else {
                "<missing>"
            }
            when (checksum) {
                record.beforeChecksum -> before++
                record.afterChecksum -> after++
                else -> return DiskState.MIXED
            }
        }
        return when {
            after == changeSet.changes.size -> DiskState.AFTER
            before == changeSet.changes.size -> DiskState.BEFORE
            else -> DiskState.MIXED
        }
    }

    private fun rootSafeFile(path: String): File {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains("..") && !path.contains('\\')) {
            "Unsafe project path"
        }
        return workspace.projectRoot().resolve(path)
    }

    private fun persist(): Boolean =
        runCatching {
            PendingProposalStore.save(workspace.projectRoot(), pending.values.toList())
            true
        }.getOrElse { false }
}
