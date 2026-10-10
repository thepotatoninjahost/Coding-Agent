package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import com.codingagent.intake.GoalInterpreter
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskIntakeParser
import com.codingagent.intake.TaskIntent
import com.codingagent.intake.TaskOperation

class GoalInterpreterTest {
    @Test fun extractsContractFromExplicitChange() {
        val root = Files.createTempDirectory("goal").toFile()
        val contract = GoalInterpreter(root).interpret("replace old with new in src/Main.kt", TaskOperation(OperationKind.REPLACE, "src/Main.kt", "old", "new"))
        assertEquals(TaskIntent.CHANGE, contract.intent)
        assertEquals(listOf("src/Main.kt"), contract.targetPaths)
        assertTrue(contract.ready)
        assertTrue(contract.acceptanceCriteria.any { it.contains("present") })
    }

    @Test fun recordsConstraintsAndAcceptanceCriteria() {
        val root = Files.createTempDirectory("goal").toFile()
        val contract = GoalInterpreter(root).interpret("create file src/New.kt with code, keep tests and make it complete without changing existing files")
        assertTrue(contract.constraints.contains("preserve existing behavior and tests"))
        assertTrue(contract.constraints.contains("do not modify project files"))
        assertTrue(contract.acceptanceCriteria.any { it.contains("present") || it.contains("explained") })
        assertTrue(contract.ready)
    }

    @Test fun plainEnglishDebugGoalIsExecutionReady() {
        val root = Files.createTempDirectory("goal").toFile()
        val contract = GoalInterpreter(root).interpret("fix the login bug")
        assertEquals(TaskIntent.DEBUG, contract.intent)
        assertTrue(contract.ready)
        assertTrue(contract.ambiguity.isEmpty())
    }

    @Test fun writingTechnicalReportIsInspectNotCreate() {
        val root = Files.createTempDirectory("goal-report").toFile()
        val intake = TaskIntakeParser(root).parse(
            "Write a deep technical report on how Report works end to end"
        )
        assertEquals(TaskIntent.INSPECT, intake.intent)
        assertTrue(intake.executionReady)
    }

    @Test fun naturalLanguageAuditRequestsAreActionableInspectTasks() {
        val root = Files.createTempDirectory("goal-audit").toFile()
        val intake = TaskIntakeParser(root).parse("run a full audit then give me your thoughts on it")
        assertEquals(TaskIntent.INSPECT, intake.intent)
        assertTrue(intake.executionReady)
    }

    @Test fun naturalLanguageAuditIsAnInspectableRequest() {
        val root = Files.createTempDirectory("goal-audit").toFile()
        val intake = TaskIntakeParser(root).parse("run a full audit then give me your thoughts on it")
        assertEquals(TaskIntent.INSPECT, intake.intent)
        assertTrue(intake.executionReady)
    }

    @Test fun naturalLanguageAnalysisIsNotReportedAsUnclear() {
        val root = Files.createTempDirectory("goal-analysis").toFile()
        val intake = TaskIntakeParser(root).parse("run an analysis on the agent and give me your thoughts")
        assertEquals(TaskIntent.INSPECT, intake.intent)
        assertTrue(intake.executionReady)
    }

    @Test fun greetingIsUnknownNotInspect() {
        val root = Files.createTempDirectory("goal-hi").toFile()
        val intake = TaskIntakeParser(root).parse("hi")
        assertEquals(TaskIntent.UNKNOWN, intake.intent)
        assertTrue(intake.executionReady)
    }

    @Test fun conversationContextWithShowDoesNotForceInspect() {
        val root = Files.createTempDirectory("goal-ctx").toFile()
        val wrapped = """
            Conversation context:
            user: hi
            system: Model is still loading. Wait for the model status to show active before sending coding requests.

            Current request:
            ho
        """.trimIndent()
        val contract = GoalInterpreter(root).interpret(wrapped)
        assertEquals(TaskIntent.UNKNOWN, contract.intent)
        assertTrue(contract.ready)
    }

    @Test fun reviewPlusImproveNamedFileIsChangeWork() {
        val root = Files.createTempDirectory("goal-improve").toFile()
        val intake = TaskIntakeParser(root).parse(
            "review project coding agent. i would like you to improve the selfevolution.kt"
        )
        assertEquals(TaskIntent.CHANGE, intake.intent)
        assertTrue(intake.executionReady)
        assertTrue(intake.contract.targetPaths.any { it.contains("selfevolution.kt", ignoreCase = true) })
    }

    @Test fun improvementsWithoutExactPathIsStillChange() {
        val root = Files.createTempDirectory("goal-improvements").toFile()
        val intake = TaskIntakeParser(root).parse(
            "Review the project coding agent. I would like to make some improvements to it."
        )
        assertEquals(TaskIntent.CHANGE, intake.intent)
        assertTrue(intake.executionReady)
    }

    @Test fun followUpClarificationPreservesActiveJobAndOwnerConstraints() {
        val root = Files.createTempDirectory("goal-followup").toFile()
        val wrapped = """
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

        val contract = GoalInterpreter(root).interpret(wrapped)
        assertEquals(TaskIntent.CREATE, contract.intent)
        assertTrue(contract.goal.contains("Build me a compiler", ignoreCase = true))
        assertTrue(contract.goal.contains("Kotlin and Python", ignoreCase = true))
        assertTrue(contract.constraints.any {
            it.contains("do not ever create a hello world file", ignoreCase = true)
        })
    }

    @Test fun buildMeACompilerIsCreateNotProjectTest() {
        val root = Files.createTempDirectory("goal-compiler").toFile()
        val contract = GoalInterpreter(root).interpret("Build me a compiler")
        assertEquals(TaskIntent.CREATE, contract.intent)
        assertTrue(contract.ready)
    }
}
