package com.codingagent.core

import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace
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

    private fun assertRejected(action: () -> Unit) {
        try {
            action()
            fail("Expected internal agent metadata access to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: private agent state is not part of the imported project API.
        }
    }
}
