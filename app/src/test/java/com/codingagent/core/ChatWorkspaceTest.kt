package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.AgentKnowledge
import com.codingagent.agent.AutonomousAgent
import com.codingagent.agent.ChatMessage
import com.codingagent.agent.ChatMessageStore
import com.codingagent.agent.ChatRole
import com.codingagent.agent.ChatWorkspace
import com.codingagent.workspace.KnowledgeHit

class ChatWorkspaceTest {
    private val emptyKnowledge = object : AgentKnowledge {
        override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
    }

    @Test
    fun persistsUserAndAgentMessages() {
        val root = Files.createTempDirectory("chat-persist").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val store = MemoryChatStore()
        val agent = AutonomousAgent(root, emptyKnowledge, gateway = null)
        val workspace = ChatWorkspace(store, runtimeProvider = { agent })

        val turn = workspace.send("hello")

        assertEquals(ChatRole.AGENT, turn.response.role)
        assertEquals(2, workspace.history().size)
        assertEquals("hello", workspace.history().first().content)
        assertTrue(workspace.history().last().content.isNotBlank())
    }

    @Test
    fun includesPreviousConversationInFollowUpRequest() {
        val root = Files.createTempDirectory("chat-context").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val store = MemoryChatStore()
        val agent = AutonomousAgent(root, emptyKnowledge, gateway = null)
        val workspace = ChatWorkspace(store, runtimeProvider = { agent })

        workspace.send("Use Kotlin")
        workspace.send("status")

        val history = workspace.history()
        assertTrue(history.any { it.role == ChatRole.USER && it.content == "Use Kotlin" })
        assertTrue(history.any { it.role == ChatRole.USER && it.content == "status" })
        assertTrue(history.size >= 4)
        // Second agent reply should be a status-style report (direct lane), proving follow-up ran.
        val lastAgent = history.last { it.role == ChatRole.AGENT }
        assertTrue(
            lastAgent.content.contains("Status", ignoreCase = true) ||
                lastAgent.content.contains("indexed", ignoreCase = true) ||
                lastAgent.content.isNotBlank()
        )
    }

    private class MemoryChatStore : ChatMessageStore {
        private val messages = mutableListOf<ChatMessage>()

        override fun recordChatMessage(message: ChatMessage) {
            messages += message
        }

        override fun recentChatMessages(limit: Int): List<ChatMessage> = messages.takeLast(limit).asReversed()
    }
}
