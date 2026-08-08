package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProjectWorkspaceTest {
    @Test fun replacementIsUniqueAndBackedUp() {
        val root = Files.createTempDirectory("coding-agent").toFile()
        val file = root.resolve("Example.kt")
        file.writeText("fun one() = 1\n")
        val workspace = ProjectWorkspace(root)
        val changeSet = workspace.replace("Example.kt", "= 1", "= 2", "test")
        assertEquals("Example.kt", changeSet.changes.single().path)
        assertEquals(ChangeOperation.REPLACE, changeSet.changes.single().operation)
        assertTrue(changeSet.changes.single().beforeChecksum.isNotBlank())
        assertTrue(changeSet.changes.single().afterChecksum.isNotBlank())
        assertEquals("fun one() = 2\n", file.readText())
        assertTrue(root.resolve(".coding-agent/transactions").walkTopDown().any { it.isFile })
    }

    @Test fun verifyRejectsUnfinishedMarkers() {
        val root = Files.createTempDirectory("coding-agent").toFile()
        root.resolve("Example.kt").writeText("// TODO finish\n")
        val report = ProjectWorkspace(root).verify()
        assertFalse(report.passed)
        assertTrue(report.issues.single().message.contains("TODO"))
    }

    @Test fun commandRunnerCapturesFailure() {
        val root = Files.createTempDirectory("coding-agent").toFile()
        val result = CommandRunner(root).run(listOf("sh", "-c", "printf out; printf err >&2; exit 3"), 5)
        assertEquals(3, result.exitCode)
        assertEquals("out", result.stdout)
        assertEquals("err", result.stderr)
    }
}

class TerminalSessionTest {
    @Test fun terminalCapturesOutputAndExitCode() {
        val root = Files.createTempDirectory("coding-agent-terminal").toFile()
        val entry = com.codingagent.terminal.TerminalSession(root).execute("printf agent; exit 4")
        assertEquals("agent", entry.result.stdout)
        assertEquals(4, entry.result.exitCode)
    }
}
