package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.AgentPlan
import com.codingagent.agent.AgentStep
import com.codingagent.agent.CompilerTestRepairCycle
import com.codingagent.agent.RepairCycleConfig
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskIntakeParser
import com.codingagent.intake.TaskOperation
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.RollbackResult

class RepairCycleTest {
    @Test fun failedVerificationRevertsChanges() {
        val root = Files.createTempDirectory("repair-cycle").toFile()
        val file = root.resolve("Main.kt").apply { writeText("class Main") }
        val workspace = ProjectWorkspace(root)
        val changeSet = workspace.append("Main.kt", "\nTODO", "test")
        val reverted = workspace.rollback(changeSet)
        assertTrue(reverted == RollbackResult.Restored)
        assertTrue(file.readText() == "class Main")
    }

    @Test
    fun failedRepairCycleRollsBackEveryAppliedRepairAttempt() {
        val root = Files.createTempDirectory("repair-cycle-revert-all").toFile()
        val file = root.resolve("Main.kt").apply { writeText("class Main\n") }
        val workspace = ProjectWorkspace(root)
        val plan = AgentPlan(
            "repair failing check",
            listOf(AgentStep("verify", "run a deliberately failing check")),
            listOf(listOf("sh", "-c", "exit 1"))
        )
        val cycle = CompilerTestRepairCycle(workspace, RepairCycleConfig(maxRepairAttempts = 1, commandTimeoutSeconds = 5))
        val result = cycle.run(
            plan = plan,
            _intake = TaskIntakeParser(root).parse("repair failing check"),
            existingChangeSets = emptyList(),
            repair = { _, _ ->
                workspace.preview(
                    listOf(TaskOperation(kind = OperationKind.APPEND, path = "Main.kt", text = "// attempted repair")),
                    "repair attempt"
                )
            },
            applyApprovedRepair = workspace::applyApproved
        )

        assertTrue(!result.passed)
        assertTrue(result.reverted)
        assertTrue(result.rollback == RollbackResult.Restored)
        assertTrue(file.readText() == "class Main\n")
    }

}
