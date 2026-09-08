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
}
