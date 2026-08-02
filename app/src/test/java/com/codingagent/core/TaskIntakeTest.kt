package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TaskIntakeTest {
    @Test fun replaceRequestIsExecutionReadyAndClassified() {
        val root = Files.createTempDirectory("task-intake").toFile()
        root.resolve("src").mkdirs()
        root.resolve("src/Main.kt").writeText("fun main() = 1\n")
        val intake = TaskIntakeParser(root).parse("replace = 1 with = 2 in src/Main.kt")
        assertEquals(TaskIntent.CHANGE, intake.intent)
        assertEquals(OperationKind.REPLACE, intake.operation.kind)
        assertTrue(intake.executionReady)
        assertEquals("src/Main.kt", intake.operation.path)
    }

    @Test fun createRequestIsExecutionReady() {
        val root = Files.createTempDirectory("task-intake").toFile()
        val intake = TaskIntakeParser(root).parse("create file src/New.kt with fun answer() = 42")
        assertEquals(TaskIntent.CREATE, intake.intent)
        assertEquals(OperationKind.CREATE_FILE, intake.operation.kind)
        assertTrue(intake.executionReady)
    }

    @Test fun plainEnglishDebugGoalIsExecutionReady() {
        // Plain English is enough. Missing exact file/target is non-blocking;
        // the model inspects the project and proposes changes through tools.
        val root = Files.createTempDirectory("task-intake").toFile()
        val intake = TaskIntakeParser(root).parse("fix the login bug")
        assertEquals(TaskIntent.DEBUG, intake.intent)
        assertTrue(intake.executionReady)
        assertEquals(null, intake.clarificationQuestion)
        assertTrue(intake.contract.ambiguity.isEmpty())
    }

    @Test fun detectsGradleVerification() {
        val root = Files.createTempDirectory("task-intake").toFile()
        root.resolve("gradlew").writeText("#!/bin/sh\n")
        val intake = TaskIntakeParser(root).parse("run the tests")
        assertEquals(TaskIntent.TEST, intake.intent)
        assertEquals(listOf("sh", "-c", "./gradlew test --no-daemon"), intake.verificationCommands.single())
    }
}
