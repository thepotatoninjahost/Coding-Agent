package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation
import com.codingagent.ui.ReviewBinder
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.MutationProposeResult
import com.codingagent.workspace.ProjectWorkspace

class ReviewBinderTest {
    @Test
    fun emptyCoordinatorHasNoReview() {
        val bound = ReviewBinder.bind(null)
        assertFalse(bound.pendingApproval)
        assertTrue(bound.reason.contains("No staged file"))
    }

    @Test
    fun staleRequestedProposalIdNeverFallsBackToAnotherProposal() {
        val root = Files.createTempDirectory("review-binder-stale").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val mutations = MutationCoordinator(ProjectWorkspace(root))
        val result = mutations.propose(
            "change main",
            listOf(TaskOperation(OperationKind.REPLACE, "Main.kt", "fun main() = 1\n", "fun main() = 2\n"))
        )
        assertTrue(result is MutationProposeResult.Proposed)

        val bound = ReviewBinder.bind(mutations, wantedId = "stale-proposal-id")
        assertFalse(bound.pendingApproval)
        assertEquals(null, bound.proposal)
        assertEquals(null, bound.proposalId)
    }

    @Test
    fun stagedProposalFillsReview() {
        val root = Files.createTempDirectory("review-binder").toFile()
        root.resolve("src").mkdirs()
        val workspace = ProjectWorkspace(root)
        val mutations = MutationCoordinator(workspace)
        val result = mutations.propose(
            "add helper",
            listOf(TaskOperation(OperationKind.CREATE_FILE, path = "src/Helper.kt", text = "class Helper\n")),
            "review-binder-test"
        )
        assertTrue(result is MutationProposeResult.Proposed)
        val bound = ReviewBinder.bind(mutations)
        assertTrue(bound.pendingApproval)
        assertEquals("src/Helper.kt", bound.proposal?.changeSet?.changes?.single()?.path)
        assertTrue(bound.reason.contains("PROPOSED CHANGES"))
        assertTrue(bound.reason.contains("src/Helper.kt"))
    }
}
