package com.codingagent.core

import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.OpenJobStore
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class ProjectFileServiceBoundaryTest {
    @Test
    fun internalAgentMetadataIsHiddenFromListingsAndBlockedFromReads() {
        val root = Files.createTempDirectory("project-file-boundary").toFile()
        root.resolve("Main.kt").writeText("fun main() = Unit\n")
        root.resolve(".coding-agent").apply { mkdirs() }
            .resolve("private-state.json").writeText("{\"private\":true}")
        root.resolve(".git").apply { mkdirs() }.resolve("config")
            .writeText("[remote \"origin\"]\\n url = https://user:secret@example.com/repo.git\\n")

        val service = ProjectFileService(ProjectWorkspace(root))

        assertTrue(service.list().any { it == "Main.kt" })
        assertFalse(service.list().any { it.equals(".coding-agent", ignoreCase = true) })
        assertFalse(service.list().any { it.equals(".git", ignoreCase = true) })
        assertRejected { service.list(".coding-agent") }
        assertRejected { service.list(".git") }
        assertRejected { service.read(".coding-agent/private-state.json") }
        assertRejected { service.read(".git/config") }
        assertRejected { service.read("nested/../.coding-agent/private-state.json") }
        assertRejected { service.read(".CODING-AGENT/private-state.json") }
    }

    @Test
    fun workspaceMutationsCannotTargetInternalAgentMetadata() {
        val root = Files.createTempDirectory("project-mutation-boundary").toFile()
        root.resolve("Main.kt").writeText("fun main() = Unit\n")
        OpenJobStore.openOrKeep(root, "active project task")
        val jobFile = OpenJobStore.file(root)
        val before = jobFile.readText()
        val workspace = ProjectWorkspace(root)

        assertRejected {
            workspace.preview(
                listOf(TaskOperation(OperationKind.CREATE_FILE, ".coding-agent/open-job.json", text = "{\"status\":\"open\"}")),
                "attempt to overwrite private agent state"
            )
        }
        assertEquals(before, jobFile.readText())
        root.resolve(".git").mkdirs()
        root.resolve(".git/config").writeText("[core]\\n repositoryformatversion = 0\\n")
        assertRejected {
            workspace.preview(
                listOf(TaskOperation(OperationKind.CREATE_FILE, ".git/config", text = "malicious = true")),
                "attempt to overwrite Git metadata"
            )
        }
    }

    @Test
    fun indexingAndSearchNeverFollowSymlinksOutsideTheProject() {
        val root = Files.createTempDirectory("project-index-symlink").toFile()
        val outside = Files.createTempDirectory("project-index-outside").toFile()
        root.resolve("Main.kt").writeText("fun main() = Unit\n")
        outside.resolve("Outside.kt").writeText("PRIVATE_OUTSIDE_MARKER\n")
        try {
            Files.createSymbolicLink(root.toPath().resolve("Outside.kt"), outside.toPath().resolve("Outside.kt"))
            Files.createSymbolicLink(root.toPath().resolve("escape"), outside.toPath())
        } catch (_: Exception) {
            assumeTrue("Symbolic links are required for this regression test", false)
        }

        val workspace = ProjectWorkspace(root)
        assertFalse(workspace.summary().files.any { it.path == "Outside.kt" || it.path.startsWith("escape/") })
        assertTrue(workspace.search("PRIVATE_OUTSIDE_MARKER").isEmpty())
    }

    private fun assertRejected(action: () -> Unit) {
        try {
            action()
            fail("Expected internal agent metadata access to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: private agent state is not part of the imported project API.
        }
    }
}
