package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.workspace.AutonomousExecutionGrant
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.MutationProposeResult
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation

class AutonomousExecutionTest {
    @Test
    fun ownerGrantedRunAppliesAndVerifiesMutationWithoutInteractiveApproval() {
        val root = Files.createTempDirectory("autonomous-execution").toFile()
        root.resolve("src").mkdirs()
        root.resolve("src/Main.kt").writeText("fun main() = 1\n")

        val workspace = ProjectWorkspace(root)
        val coordinator = MutationCoordinator(workspace)
        val proposalResult = coordinator.propose(
            request = "autonomously change Main.kt",
            operations = listOf(
                TaskOperation(
                    kind = OperationKind.REPLACE,
                    path = "src/Main.kt",
                    oldText = "fun main() = 1",
                    newText = "fun main() = 2"
                )
            )
        )
        assertTrue(proposalResult is MutationProposeResult.Proposed)
        val proposal = (proposalResult as MutationProposeResult.Proposed).proposal

        val taskId = "autonomous-test-task"
        val grant = AutonomousExecutionGrant.forRun(taskId, root)
        val result = coordinator.applyAutonomous(proposal.id, taskId, grant)

        assertTrue("expected Applied, got $result", result is MutationApprovalResult.Applied)
        assertEquals("fun main() = 2\n", root.resolve("src/Main.kt").readText())
        assertTrue(coordinator.pending().isEmpty())
    }

    @Test
    fun autonomousGrantCannotBeUsedForAnotherTask() {
        val root = Files.createTempDirectory("autonomous-grant").toFile()
        val grant = AutonomousExecutionGrant.forRun("task-a", root)
        assertTrue(!grant.isValid("task-b", root, System.currentTimeMillis()))
    }
}
