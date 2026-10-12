package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.AgentAction
import com.codingagent.agent.AgentActionCategory
import com.codingagent.agent.PromotionResult
import com.codingagent.agent.SelfEvolution
import com.codingagent.workspace.VerificationReport
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.MutationProposeResult
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.OwnerApprovalToken
import com.codingagent.workspace.ProjectWorkspace

class SelfEvolutionTest {
    private fun approvedAction(category: AgentActionCategory = AgentActionCategory.CODE_CHANGE) = AgentAction(
        description = "Promote verified source",
        category = category,
        ownerVerified = true,
        approvalCount = 2,
        sandboxPassed = true,
        clearPermission = true
    )

    @Test
    fun rejectsFailedEvaluationEvenForReadOnlyAction() {
        val root = Files.createTempDirectory("evolution-failed").toFile()
        val evolution = SelfEvolution(root)
        val source = root.resolve("Main.kt").apply { writeText("fun main() = Unit\n") }
        val staged = evolution.stageSource(source, "self-repair")

        val result = evolution.promoteSource(
            staged,
            "self-repair",
            VerificationReport(false, emptyList()),
            approvedAction(AgentActionCategory.READ_ONLY)
        )

        assertTrue(result is PromotionResult.Rejected)
        assertTrue((result as PromotionResult.Rejected).reason.contains("passing evaluation"))
        assertEquals(null, evolution.activeVersion())
        assertTrue(evolution.history().isEmpty())
    }

    @Test
    fun rejectsReadOnlyActionEvenWhenEvaluationPasses() {
        val root = Files.createTempDirectory("evolution-read-only").toFile()
        val evolution = SelfEvolution(root)
        val source = root.resolve("Main.kt").apply { writeText("fun main() = Unit\\n") }
        val staged = evolution.stageSource(source, "self-repair")

        val result = evolution.promoteSource(
            staged,
            "self-repair",
            VerificationReport(true, emptyList()),
            approvedAction(AgentActionCategory.READ_ONLY)
        )

        assertTrue(result is PromotionResult.Rejected)
        assertTrue((result as PromotionResult.Rejected).reason.contains("CODE_CHANGE"))
        assertEquals(null, evolution.activeVersion())
        assertTrue(evolution.history().isEmpty())
    }

    @Test
    fun rejectsReadOnlyActionEvenWhenEvaluationPasses() {
        val root = Files.createTempDirectory("evolution-read-only").toFile()
        val evolution = SelfEvolution(root)
        val source = root.resolve("Main.kt").apply { writeText("fun main() = Unit\n") }
        val staged = evolution.stageSource(source, "self-repair")

        val result = evolution.promoteSource(
            staged,
            "self-repair",
            VerificationReport(true, emptyList()),
            approvedAction(AgentActionCategory.READ_ONLY)
        )

        assertTrue(result is PromotionResult.Rejected)
        assertTrue((result as PromotionResult.Rejected).reason.contains("CODE_CHANGE"))
        assertEquals(null, evolution.activeVersion())
        assertTrue(evolution.history().isEmpty())
    }

    @Test
    fun rejectsSourceOutsideManagedVersionDirectory() {
        val root = Files.createTempDirectory("evolution-unmanaged").toFile()
        val evolution = SelfEvolution(root)
        val unmanaged = root.resolve("Unmanaged.kt").apply { writeText("class Unmanaged\n") }

        val result = evolution.promoteSource(
            unmanaged,
            "self-repair",
            VerificationReport(true, emptyList()),
            approvedAction()
        )

        assertTrue(result is PromotionResult.Rejected)
        assertTrue((result as PromotionResult.Rejected).reason.contains("evolution versions directory"))
        assertEquals(null, evolution.activeVersion())
    }

    @Test
    fun promotesVerifiedManagedSnapshotAndRecordsRecoverablePath() {
        val root = Files.createTempDirectory("evolution-promote").toFile()
        val evolution = SelfEvolution(root)
        val source = root.resolve("Main.kt").apply { writeText("fun main() = Unit\n") }
        val staged = evolution.stageSource(source, "self-repair")

        val result = evolution.promoteSource(
            staged,
            "self-repair",
            VerificationReport(true, emptyList()),
            approvedAction(),
            approvalAt = System.currentTimeMillis()
        )

        assertTrue(result is PromotionResult.Promoted)
        val promoted = (result as PromotionResult.Promoted).version
        assertEquals(promoted.id, evolution.activeVersion())
        assertTrue(promoted.checksum.isNotBlank())
        assertEquals(staged.absolutePath, evolution.history().single().sourcePath)
        assertEquals("Main.kt", evolution.history().single().targetPath)
        assertTrue(evolution.history().single().evaluationPassed)
        assertFalse(evolution.history().single().sourcePath == staged.name)
    }

    @Test
    fun recoveryCandidateRequiresAnIntactManagedSnapshot() {
        val root = Files.createTempDirectory("evolution-candidate").toFile()
        val evolution = SelfEvolution(root)
        val target = root.resolve("src/Main.kt")
        target.parentFile.mkdirs()
        target.writeText("approved source\n")
        val staged = evolution.stageSource(target, "self-repair")
        val promoted = evolution.promoteSource(
            staged,
            "self-repair",
            VerificationReport(true, emptyList()),
            approvedAction(),
            approvalAt = System.currentTimeMillis()
        ) as PromotionResult.Promoted

        val candidate = evolution.prepareRestoreCandidate(promoted.version.id)
        assertTrue(candidate is com.codingagent.agent.SnapshotCandidateResult.Ready)
        assertEquals(target.canonicalFile, (candidate as com.codingagent.agent.SnapshotCandidateResult.Ready).candidate.destination)

        staged.writeText("tampered source\n")
        val corrupted = evolution.prepareRestoreCandidate(promoted.version.id)
        assertTrue(corrupted is com.codingagent.agent.SnapshotCandidateResult.Rejected)
        assertTrue((corrupted as com.codingagent.agent.SnapshotCandidateResult.Rejected).reason.contains("checksum"))
        assertEquals("approved source\n", target.readText())
    }

    @Test
    fun historicalRestoreIsStagedForDualApprovalInsteadOfWrittenDirectly() {
        val root = Files.createTempDirectory("evolution-restore-proposal").toFile()
        val target = root.resolve("Main.kt").apply { writeText("class Main\n") }
        val evolution = SelfEvolution(root)
        val staged = evolution.stageSource(target, "self-repair")
        val promoted = evolution.promoteSource(
            staged,
            "self-repair",
            VerificationReport(true, emptyList()),
            approvedAction(),
            approvalAt = System.currentTimeMillis()
        ) as PromotionResult.Promoted
        target.writeText("class Broken\n")

        val workspace = ProjectWorkspace(root)
        val mutations = MutationCoordinator(workspace)
        val result = mutations.proposeEvolutionRestore(promoted.version.id)

        assertTrue(result is MutationProposeResult.Proposed)
        val proposal = (result as MutationProposeResult.Proposed).proposal
        assertEquals(0, proposal.approvalCount)
        assertEquals("class Main\n", proposal.changeSet.changes.single().after)
        assertEquals("class Broken\n", target.readText())

        val firstApproval = mutations.approve(
            proposal.id,
            OwnerApprovalToken.authenticated(proposal.id)
        )
        assertTrue(firstApproval is MutationApprovalResult.AwaitingSecond)
        assertEquals("class Broken\n", target.readText())

        val secondApproval = mutations.approve(
            proposal.id,
            OwnerApprovalToken.authenticated(proposal.id)
        )
        assertTrue(secondApproval is MutationApprovalResult.Applied)
        assertEquals("class Main\n", target.readText())
    }

    @Test
    fun rejectsSnapshotWithNoSafeRecoveryTarget() {
        val root = Files.createTempDirectory("evolution-outside-target").toFile()
        val evolution = SelfEvolution(root)
        val outsideRoot = Files.createTempDirectory("evolution-outside-source")
            .resolve("Outside.kt").toFile().apply { writeText("class Outside\n") }
        val staged = evolution.stageSource(outsideRoot, "self-repair")

        val result = evolution.promoteSource(
            staged,
            "self-repair",
            VerificationReport(true, emptyList()),
            approvedAction(),
            approvalAt = System.currentTimeMillis()
        )

        assertTrue(result is PromotionResult.Rejected)
        assertTrue((result as PromotionResult.Rejected).reason.contains("safe project-relative recovery target"))
        assertTrue(evolution.history().isEmpty())
        assertEquals(null, evolution.activeVersion())
    }

    @Test
    fun rejectsUnsafeHistoricalRecoveryTargetAndUnknownVersion() {
        val root = Files.createTempDirectory("evolution-unsafe-history-target").toFile()
        val target = root.resolve("Main.kt").apply { writeText("class Main\n") }
        val evolution = SelfEvolution(root)
        val staged = evolution.stageSource(target, "self-repair")
        val promoted = evolution.promoteSource(
            staged,
            "self-repair",
            VerificationReport(true, emptyList()),
            approvedAction(),
            approvalAt = System.currentTimeMillis()
        ) as PromotionResult.Promoted

        val historyFile = root.resolve(".coding-agent/evolution/history.tsv")
        val fields = historyFile.readText().trimEnd().split('\t').toMutableList()
        fields[6] = "../outside.kt"
        historyFile.writeText(fields.joinToString("\t") + "\n")

        val unsafe = evolution.prepareRestoreCandidate(promoted.version.id)
        assertTrue(unsafe is com.codingagent.agent.SnapshotCandidateResult.Rejected)
        assertTrue((unsafe as com.codingagent.agent.SnapshotCandidateResult.Rejected).reason.contains("safe project-relative recovery target"))

        val missing = evolution.prepareRestoreCandidate("not-a-real-version")
        assertTrue(missing is com.codingagent.agent.SnapshotCandidateResult.Rejected)
        assertTrue((missing as com.codingagent.agent.SnapshotCandidateResult.Rejected).reason.contains("not found"))
    }

}
