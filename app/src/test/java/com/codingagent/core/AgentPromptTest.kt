package com.codingagent.core

import com.codingagent.agent.AgentPrompt
import com.codingagent.intake.TaskIntakeParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AgentPromptTest {
    @Test fun promptCarriesFollowUpGoalAndBindingOwnerConstraint() {
        val root = Files.createTempDirectory("agent-prompt").toFile()
        val request = """
            OPEN JOB (do not claim there is no prior task):
            - id: job-1
            - status: open
            - goal: Build me a compiler
            - staged paths:
            Conversation so far (oldest first).
            OWNER: Build me a compiler
            AGENT: I need to know what languages you want.
            OWNER: Do not ever create a hello world file or app.
            Current request:
            Kotlin and Python
        """.trimIndent()

        val intake = TaskIntakeParser(root).parse(request)
        val prompt = AgentPrompt.build(request, intake, "real evidence", 4_000, lessons = "")

        assertTrue(prompt.contains("Build me a compiler", ignoreCase = true))
        assertTrue(prompt.contains("Kotlin and Python", ignoreCase = true))
        assertTrue(prompt.contains("Do not ever create a hello world file or app", ignoreCase = true))
        assertTrue(prompt.contains("Owner constraints — binding requirements:"))
        assertTrue(prompt.contains("Exactly one tool call this turn"))
        assertTrue(prompt.contains("purpose field"))
    }

    @Test fun promptAllowsAmbitiousWorkAndRequiresMilestoneVerification() {
        val root = Files.createTempDirectory("agent-prompt-ambitious").toFile()
        val request = "Build a compiler with a new parser and test suite"
        val intake = TaskIntakeParser(root).parse(request)
        val prompt = AgentPrompt.build(request, intake, "repository evidence", 4_000, lessons = "")

        assertTrue(prompt.contains("coordinated multi-file changes"))
        assertTrue(prompt.contains("new tools"))
        assertTrue(prompt.contains("self-modification"))
        assertTrue(prompt.contains("Verify meaningful milestones"))
        assertTrue(prompt.contains("static marker scanning alone is not proof"))
        assertTrue(prompt.contains("personal logs"))
        assertTrue(prompt.contains("Do not stop merely because a file was read"))
    }
}
