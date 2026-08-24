package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import com.codingagent.workspace.ChangeOperation
import com.codingagent.workspace.CommandRunner
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.TerminalSession

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

    @Test fun verifyIgnoresScannerDocumentation() {
        val root = Files.createTempDirectory("coding-agent-docs").toFile()
        root.resolve("README.md").writeText("Always-on static verification (TODO/FIXME/stub scan)\n")
        root.resolve("Notes.kt").writeText("fun ok() = 1 // no markers\n")
        root.resolve("UiCopy.kt").writeText(
            """fun msg() = "Static scan found no TODO/FIXME/stub markers in production sources."\n"""
        )
        val report = ProjectWorkspace(root).verify()
        assertTrue("docs and UI copy about the scanner must not fail verify: " + report.issues, report.passed)
    }

    @Test fun verifyStillCatchesRealTodoComments() {
        val root = Files.createTempDirectory("coding-agent-real-todo").toFile()
        root.resolve("Broken.kt").writeText("fun x() {\n  // TODO finish this\n}\n")
        val report = ProjectWorkspace(root).verify()
        assertFalse(report.passed)
        assertTrue(report.issues.any { it.message.contains("TODO") })
    }

    @Test fun verifyIgnoresStringProseWithoutCommentMarker() {
        val root = Files.createTempDirectory("coding-agent-prose").toFile()
        root.resolve("Copy.kt").writeText(
            "fun msg() = \"markers are scanned by verify\"\n"
        )
        root.resolve("Ok.kt").writeText("fun ok() = 1\n")
        val report = ProjectWorkspace(root).verify()
        assertTrue(report.passed)
    }

    @Test fun verifyCatchesTodoCallForm() {
        val root = Files.createTempDirectory("coding-agent-todo-call").toFile()
        root.resolve("Broken.kt").writeText("fun x() = TODO(\"finish\")\n")
        val report = ProjectWorkspace(root).verify()
        assertFalse(report.passed)
        assertTrue(report.issues.any { it.message.contains("TODO", ignoreCase = true) })
    }
}

class TerminalSessionTest {
    @Test fun terminalCapturesOutputAndExitCode() {
        val root = Files.createTempDirectory("coding-agent-terminal").toFile()
        val entry = TerminalSession(root).execute("printf agent; exit 4")
        assertEquals("agent", entry.stdout)
        assertEquals(4, entry.exitCode)
    }
}
