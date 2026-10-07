package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.workspace.OpenJobStore

class OpenJobStoreTest {
    @Test
    fun survivesReloadAndKeepsSameGoal() {
        val root = Files.createTempDirectory("open-job").toFile()
        val first = OpenJobStore.openOrKeep(root, "create a autonomous agent")
        val second = OpenJobStore.openOrKeep(root, "try again")
        assertEquals(first.id, second.id)
        assertEquals("create a autonomous agent", second.goal)
        OpenJobStore.markWaiting(root, "proposal-1", listOf("src/AutonomousAgent.kt"), null)
        val loaded = OpenJobStore.load(root)!!
        assertEquals("waiting-approval", loaded.status)
        assertEquals("proposal-1", loaded.proposalId)
        assertTrue(loaded.promptBlock().contains("OPEN JOB"))
        assertTrue(loaded.promptBlock().contains("create a autonomous agent"))
    }

    @Test
    fun startNewReplacesExistingOpenJob() {
        val root = Files.createTempDirectory("open-job-new-goal").toFile()
        val first = OpenJobStore.openOrKeep(root, "fix the parser")
        val second = OpenJobStore.startNew(root, "build the terminal tool")

        assertTrue(first.id != second.id)
        assertEquals("build the terminal tool", second.goal)
        assertEquals("open", second.status)
        assertEquals(null, second.proposalId)
    }

    @Test
    fun saveReplacesJobAtomicallyAndLeavesNoTemporaryFiles() {
        val root = Files.createTempDirectory("open-job-atomic").toFile()
        val first = OpenJobStore.openOrKeep(root, "first goal")
        OpenJobStore.save(
            root,
            first.copy(
                goal = "second goal",
                status = "waiting-approval",
                proposalId = "proposal-2",
                paths = listOf("src/Agent.kt")
            )
        )
        val loaded = OpenJobStore.load(root)!!
        assertEquals("second goal", loaded.goal)
        assertEquals("waiting-approval", loaded.status)
        assertEquals("proposal-2", loaded.proposalId)
        assertEquals(listOf("src/Agent.kt"), loaded.paths)
        val tempFiles = root.resolve(".coding-agent").listFiles()
            ?.filter { it.name.endsWith(".tmp") }
            .orEmpty()
        assertTrue("Atomic save left temporary files: $tempFiles", tempFiles.isEmpty())
    }
}
