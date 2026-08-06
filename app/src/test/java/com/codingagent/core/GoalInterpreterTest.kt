package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

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
}
