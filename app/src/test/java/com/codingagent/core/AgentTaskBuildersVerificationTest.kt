package com.codingagent.core

import com.codingagent.agent.AgentTaskBuilders
import com.codingagent.workspace.AgentPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentTaskBuildersVerificationTest {
    @Test
    fun failedTaskCannotClaimVerificationPassed() {
        val task = AgentTaskBuilders.failed(
            id = "failed-task",
            request = "implement a feature",
            plan = AgentPlan("implement a feature", emptyList(), emptyList()),
            message = "The model failed before implementation.",
            changes = emptyList()
        )

        assertEquals("failed", task.status)
        assertFalse("A failed task must never report verification success", task.verification.passed)
    }

    @Test
    fun stoppedTaskCannotClaimVerificationPassed() {
        val task = AgentTaskBuilders.stopped(
            taskId = "stopped-task",
            request = "implement a feature",
            plan = AgentPlan("implement a feature", emptyList(), emptyList()),
            changes = emptyList(),
            message = "Stopped by owner"
        )

        assertEquals("stopped", task.status)
        assertFalse("A stopped task has not passed verification", task.verification.passed)
    }
}
