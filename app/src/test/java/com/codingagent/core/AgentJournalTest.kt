package com.codingagent.core

import com.codingagent.agent.AgentJournal
import com.codingagent.agent.AutonomousAgentEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AgentJournalTest {
    @Test
    fun recordsObservableEventsAndReturnsNewestEntriesFirst() {
        val root = Files.createTempDirectory("agent-personal-log").toFile()
        val journal = AgentJournal(root)

        journal.recordEvent("task-1", AutonomousAgentEvent.ToolStarted("read_file", """{"path":"src/Main.kt"}""", "inspect target"))
        journal.recordEvent("task-1", AutonomousAgentEvent.ToolFinished("read_file", "real file contents", true))
        journal.recordEvent("task-1", AutonomousAgentEvent.ModelMessage("Found the implementation and its call sites."))

        val recent = journal.recentEvents(2)

        assertEquals(2, recent.size)
        val newest = JSONObject(recent[0])
        val previous = JSONObject(recent[1])
        assertEquals("ModelMessage", newest.getString("type"))
        assertTrue(newest.getString("details").contains("Found the implementation"))
        assertEquals("ToolFinished", previous.getString("type"))
        assertTrue(previous.getString("details").contains("real file contents"))
        assertTrue(root.resolve(".coding-agent/personal-log.jsonl").isFile)
    }
}
