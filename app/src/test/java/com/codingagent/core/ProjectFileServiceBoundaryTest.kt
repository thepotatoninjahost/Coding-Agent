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
            .writeText("[remote \"origin\"]\n url = https://user:secret@example.com/repo.git\n")

        val service = ProjectFileService(ProjectWorkspace(root))

        assertTrue(service.list().any { it == "Main.kt" })
        assertFalse(service.list().any { it.equals(".coding-agent", ignoreCase = true) })
        assertFalse(service.list().any { it.equals(".git", ignoreCase = true) })
        assertRejected { service.list(".coding-agent") }
        assertRejected { service.list(".git") }
        assertRejected { service.read(".coding-agent/private-state.json") }
        assertRejected { service.read(".git/config") }
        root.resolve("nested/.git").mkdirs()
        root.resolve("nested/.git/config").writeText("secret config")
        assertRejected { service.read("nested/.git/config") }
        assertRejected { service.list("nested/.git") }
        assertFalse(service.list("nested").any { it.equals(".git", ignoreCase = true) })
        root.resolve("nested/.coding-agent").mkdirs()
        root.resolve("nested/.coding-agent/private-state.json").writeText("{\"private\":true}")
        assertRejected { service.read("nested/.coding-agent/private-state.json") }
        assertRejected { service.list("nested/.coding-agent") }
        assertFalse(service.list("nested").any { it.equals(".coding-agent", ignoreCase = true) })
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
        root.resolve(".git/config").writeText("[core]\n repositoryformatversion = 0\n")
        assertRejected {
            workspace.preview(
                listOf(TaskOperation(OperationKind.CREATE_FILE, ".git/config", text = "malicious = true")),
                "attempt to overwrite Git metadata"
            )
        }
        root.resolve("nested/.git").mkdirs()
        root.resolve("nested/.git/config").writeText("[core]\n repositoryformatversion = 0\n")
        assertRejected {
            workspace.preview(
                listOf(TaskOperation(OperationKind.REPLACE, "nested/.git/config", "[core]\n", "[core]\n malicious = true\n")),
                "attempt to overwrite nested Git metadata"
            )
        }
    }

    @Test
    fun indexerExcludesCaseVariantPrivateMetadataDirectories() {
        val root = Files.createTempDirectory("project-index-case-variant-metadata").toFile()
        root.resolve("src").mkdirs()
        root.resolve("src/Main.kt").writeText("fun main() = Unit\n")
        root.resolve(".GIT").mkdirs()
        root.resolve(".GIT/Private.kt").writeText("const val CASE_VARIANT_PRIVATE_MARKER = \"private\"\n")
        root.resolve("nested/.Git").mkdirs()
        root.resolve("nested/.Git/Secret.kt").writeText("const val NESTED_PRIVATE_MARKER = \"private\"\n")

        val workspace = ProjectWorkspace(root)
        val paths = workspace.summary().files.map { it.path }
        assertTrue(paths.any { it == "src/Main.kt" })
        assertFalse(paths.any { it.split('/').any { part -> part.equals(".git", ignoreCase = true) } })
        assertTrue(workspace.search("CASE_VARIANT_PRIVATE_MARKER").isEmpty())
        assertTrue(workspace.search("NESTED_PRIVATE_MARKER").isEmpty())
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


    @Test
    fun rejectsSymlinkedPrivateMetadataDirectoriesBeforeWritingOutsideProject() {
        val root = Files.createTempDirectory("project-private-metadata-link").toFile()
        val outside = Files.createTempDirectory("project-private-metadata-outside").toFile()
        try {
            Files.createSymbolicLink(root.toPath().resolve(".coding-agent"), outside.toPath())
        } catch (_: Exception) {
            assumeTrue("Symbolic links are required for this regression test", false)
        }

        assertRejected { ProjectWorkspace(root) }
        assertRejected { OpenJobStore.openOrKeep(root, "must not write through metadata symlink") }
        assertFalse(outside.resolve("transactions").exists())
        assertFalse(outside.resolve("open-job.json").exists())
    }

    @Test
    fun rejectsSymlinkedTransactionDirectory() {
        val root = Files.createTempDirectory("project-transaction-link").toFile()
        val outside = Files.createTempDirectory("project-transaction-outside").toFile()
        root.resolve(".coding-agent").mkdirs()
        try {
            Files.createSymbolicLink(root.toPath().resolve(".coding-agent/transactions"), outside.toPath())
        } catch (_: Exception) {
            assumeTrue("Symbolic links are required for this regression test", false)
        }

        assertRejected { ProjectWorkspace(root) }
        assertFalse(outside.listFiles()?.isNotEmpty() == true)
    }


    @Test
    fun rejectsSymlinkedResearchStorageBeforeCreatingSessions() {
        val root = Files.createTempDirectory("project-research-link").toFile()
        val outside = Files.createTempDirectory("project-research-outside").toFile()
        root.resolve(".coding-agent").mkdirs()
        try {
            Files.createSymbolicLink(root.toPath().resolve(".coding-agent/research"), outside.toPath())
        } catch (_: Exception) {
            assumeTrue("Symbolic links are required for this regression test", false)
        }

        assertRejected {
            com.codingagent.research.DurableDeepResearchProvider(
                root.resolve(".coding-agent/research")
            )
        }
        assertFalse(outside.resolve("sessions").exists())
    }


    @Test
    fun atomicRecoveryRejectsSymlinkedBackupAndPendingFiles() {
        val root = Files.createTempDirectory("atomic-metadata-link").toFile()
        val outside = Files.createTempDirectory("atomic-metadata-outside").toFile()
        root.resolve(".coding-agent").mkdirs()
        val secret = outside.resolve("secret.txt").apply { writeText("outside data") }

        for (suffix in listOf(".bak", ".new")) {
            val link = root.resolve(".coding-agent/open-job.json$suffix")
            try {
                Files.createSymbolicLink(link.toPath(), secret.toPath())
            } catch (_: Exception) {
                assumeTrue("Symbolic links are required for this regression test", false)
            }

            try {
                OpenJobStore.load(root)
                fail("Atomic recovery must reject symlinked $suffix files")
            } catch (_: IllegalStateException) {
                // Expected: recovery must never read or promote a symlink.
            } finally {
                Files.deleteIfExists(link.toPath())
            }
            assertEquals("outside data", secret.readText())
        }
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
