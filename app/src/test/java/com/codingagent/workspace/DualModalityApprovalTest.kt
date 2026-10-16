package com.codingagent.workspace

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.ApprovalModality
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation

/**
 * ONE JOB: Prove test tokens still work without modalities, and modalities are recorded when provided.
 */
class DualModalityApprovalTest {
    @Test
    fun testCapabilityTokensStillApplyWithoutModalities() {
        val root = Files.createTempDirectory("modality-test").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val coordinator = MutationCoordinator(ProjectWorkspace(root))
        val proposed = coordinator.propose(
            "modality gate",
            listOf(TaskOperation(OperationKind.REPLACE, "Main.kt", "fun main() = 1\n", "fun main() = 2\n"))
        )
        assertTrue(proposed is MutationProposeResult.Proposed)
        val proposal = (proposed as MutationProposeResult.Proposed).proposal

        val first = coordinator.approve(proposal.id, OwnerApprovalToken.authenticated(proposal.id))
        assertTrue("Test capability must remain usable: $first", first is MutationApprovalResult.AwaitingSecond)

        val second = coordinator.approve(proposal.id, OwnerApprovalToken.authenticated(proposal.id))
        assertTrue("Test capability second approval must apply: $second", second is MutationApprovalResult.Applied)
        assertEquals("fun main() = 2\n", root.resolve("Main.kt").readText())
    }

    @Test
    fun approvalRecordStoresModalitiesWhenProvided() {
        val root = Files.createTempDirectory("modality-record").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val coordinator = MutationCoordinator(ProjectWorkspace(root))
        val proposed = coordinator.propose(
            "record modalities",
            listOf(TaskOperation(OperationKind.REPLACE, "Main.kt", "fun main() = 1\n", "fun main() = 2\n"))
        )
        val proposal = (proposed as MutationProposeResult.Proposed).proposal
        val mods = setOf(ApprovalModality.SPOKEN, ApprovalModality.TYPED, ApprovalModality.DEVICE_AUTH)
        val first = coordinator.approve(
            proposal.id,
            OwnerApprovalToken.authenticated(proposal.id),
            mods
        )
        assertTrue(first is MutationApprovalResult.AwaitingSecond)
        val recorded = (first as MutationApprovalResult.AwaitingSecond).proposal.approvals.single()
        assertEquals(mods, recorded.modalities)
    }
}
