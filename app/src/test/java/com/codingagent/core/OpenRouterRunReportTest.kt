package com.codingagent.core

import com.codingagent.agent.AgentPlan
import com.codingagent.agent.AutonomousAgentEvent
import com.codingagent.workspace.AgentTask
import com.codingagent.workspace.VerificationReport
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterRunReportTest {
    @Test fun waitingForApprovalIsNotSuccess() {
        val reason = OpenRouterRunReport.failureReason(
            AutonomousAgentEvent.ApprovalRequired(task("needs-approval", "Please say yes."), proposal())
        )
        assertTrue(reason.orEmpty().contains("Waiting for approval"))
        assertTrue(reason.orEmpty().contains("not a finished job"))
    }

    @Test fun aQuestionWithNothingBuiltIsNotSuccess() {
        val reason = OpenRouterRunReport.failureReason(
            AutonomousAgentEvent.Completed(task("needs-input", "Tell me to go ahead."))
        )
        assertTrue(reason.orEmpty().contains("did not finish"))
    }

    @Test fun aRealAnswerIsSuccess() {
        assertNull(OpenRouterRunReport.failureReason(
            AutonomousAgentEvent.Completed(task("completed", "This project is a phone app."))
        ))
    }

    @Test fun aRealProjectFolderIsUsedAndAMissingFolderIsReported() {
        val dir = Files.createTempDirectory("openrouter-project").toFile()
        dir.resolve("README.md").writeText("real project\n")
        val ready = OpenRouterRunReport.projectDirectory(dir.absolutePath)
        assertTrue(ready is OpenRouterRunReport.ProjectDirectory.Ready)
        assertEquals(dir.absolutePath, (ready as OpenRouterRunReport.ProjectDirectory.Ready).root.absolutePath)
        val missing = OpenRouterRunReport.projectDirectory("/tmp/does-not-exist-coding-agent")
        assertTrue(missing is OpenRouterRunReport.ProjectDirectory.Missing)
        assertTrue((missing as OpenRouterRunReport.ProjectDirectory.Missing).message.contains("does not exist"))
    }

    private fun task(status: String, summary: String) = AgentTask(
        id = "t",
        request = "Explain this project",
        status = status,
        plan = AgentPlan("Explain this project", emptyList(), emptyList()),
        changes = emptyList(),
        verification = VerificationReport(true, emptyList()),
        events = emptyList(),
        summary = summary
    )

    private fun proposal() = com.codingagent.workspace.PendingChangeProposal(
        id = "p",
        request = "change",
        changeSet = com.codingagent.workspace.ChangeSet("c", emptyList(), 1L, "reason"),
        verification = VerificationReport(true, emptyList()),
        createdAt = 1L,
        expiresAt = 2L
    )
}
