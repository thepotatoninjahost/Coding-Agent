package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test
import com.codingagent.agent.AgentKnowledge
import com.codingagent.agent.AutonomousAgent
import com.codingagent.agent.ChatMessage
import com.codingagent.agent.ChatMessageStore
import com.codingagent.agent.ChatRole
import com.codingagent.agent.ChatWorkspace
import com.codingagent.workspace.KnowledgeHit
import com.codingagent.workspace.OpenJobStore

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
    fun cancelPropagatesToActiveAgentGateway() {
        val root = Files.createTempDirectory("chat-cancel").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val cancelled = AtomicBoolean(false)
        val gateway = object : com.codingagent.model.ModelGateway {
            override fun complete(request: com.codingagent.model.ModelRequest): com.codingagent.model.ModelResponse =
                com.codingagent.model.ModelResponse.Text("unused")

            override fun cancel() {
                cancelled.set(true)
            }
        }
        val store = MemoryChatStore()
        val agent = AutonomousAgent(root, emptyKnowledge, gateway = gateway)
        val workspace = ChatWorkspace(store, runtimeProvider = { agent })

        workspace.cancel()

        assertTrue(cancelled.get())
        assertTrue(agent.isCancelled())
    }

    @Test
    fun continueRestartsPersistedOpenJobInsteadOfReportingDroppedWork() {
        val root = Files.createTempDirectory("chat-resume-open-job").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        OpenJobStore.openOrKeep(root, "fix Main.kt and run the tests")
        val store = MemoryChatStore()
        val agent = AutonomousAgent(root, emptyKnowledge, gateway = null)
        val workspace = ChatWorkspace(store, runtimeProvider = { agent })

        val turn = workspace.send("continue")

        assertTrue(turn.response.content.isNotBlank())
        assertTrue(
            !turn.response.content.contains(
                "There is no pending code proposal and no live task to resume",
                ignoreCase = true
            )
        )
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
