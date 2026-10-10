package com.codingagent.core

import com.codingagent.agent.AgentJournal
import com.codingagent.agent.AgentKnowledge
import com.codingagent.agent.AutonomousAgent
import com.codingagent.agent.AutonomousAgentEvent
import com.codingagent.agent.ChatMessage
import com.codingagent.agent.ChatMessageStore
import com.codingagent.agent.ChatWorkspace
import com.codingagent.workspace.KnowledgeHit
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
        journal.recordEvent("task-1", AutonomousAgentEvent.ModelDelta("partial "))
        journal.recordEvent("task-1", AutonomousAgentEvent.ModelDelta("stream"))
        journal.recordEvent("task-1", AutonomousAgentEvent.ModelMessage("Found the implementation and its call sites."))

        val recent = journal.recentEvents(3)

        assertEquals(3, recent.size)
        val newest = JSONObject(recent[0])
        val stream = JSONObject(recent[1])
        val previous = JSONObject(recent[2])
        assertEquals("ModelMessage", newest.getString("type"))
        assertTrue(newest.getString("details").contains("Found the implementation"))
        assertEquals("ModelStream", stream.getString("type"))
        assertEquals("partial stream", stream.getString("details"))
        assertEquals("ToolFinished", previous.getString("type"))
        assertTrue(previous.getString("details").contains("real file contents"))
        assertTrue(root.resolve(".coding-agent/personal-log.jsonl").isFile)
    }

    @Test
    fun ownerCanReadRecentPersonalLogsThroughChat() {
        val root = Files.createTempDirectory("agent-personal-log-command").toFile()
        val knowledge = object : AgentKnowledge {
            override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
        }
        val agent = AutonomousAgent(root, knowledge, gateway = null)
        agent.run("hello")
        val messages = mutableListOf<ChatMessage>()
        val workspace = ChatWorkspace(
            store = object : ChatMessageStore {
                override fun recordChatMessage(message: ChatMessage) {
                    messages += message
                }

                override fun recentChatMessages(limit: Int): List<ChatMessage> = messages.takeLast(limit)
            },
            runtimeProvider = { agent }
        )

        val turn = workspace.send("#logs")

        assertTrue(turn.response.content.contains("Personal logs"))
        assertTrue(turn.response.content.contains("Started"))
        assertTrue(turn.response.content.contains("hello"))
    }
}
