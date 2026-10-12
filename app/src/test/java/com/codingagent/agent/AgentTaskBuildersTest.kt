package com.codingagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentTaskBuildersTest {
    @Test
    fun failedTaskDoesNotClaimVerificationPassed() {
        val task = AgentTaskBuilders.failed(
            id = "task-1",
            request = "inspect project",
            plan = AgentPlan("inspect project", emptyList(), emptyList()),
            message = "Aborted after repeated tool failures",
            changes = emptyList()
        )

        assertEquals("failed", task.status)
        assertFalse("A failed task must not claim verification passed", task.verification.passed)
        assertTrueNoVerificationCommands(task.verification.commands.isEmpty())
    }

    private fun assertTrueNoVerificationCommands(noCommands: Boolean) {
        org.junit.Assert.assertTrue("Failure outcome has no verification commands", noCommands)
    }
}
