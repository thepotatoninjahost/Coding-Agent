package com.codingagent.core

import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertFalse
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

        val service = ProjectFileService(ProjectWorkspace(root))

        assertTrue(service.list().any { it == "Main.kt" })
        assertFalse(service.list().any { it.equals(".coding-agent", ignoreCase = true) })
        assertRejected { service.list(".coding-agent") }
        assertRejected { service.read(".coding-agent/private-state.json") }
        assertRejected { service.read("nested/../.coding-agent/private-state.json") }
        assertRejected { service.read(".CODING-AGENT/private-state.json") }
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
