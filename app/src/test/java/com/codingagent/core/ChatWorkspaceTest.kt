package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWorkspaceTest {
    @Test
    fun persistsUserAndAgentMessages() {
        val store = MemoryChatStore()
        val workspace = ChatWorkspace(store) { FakeExecutor("completed") }

        val turn = workspace.send("Inspect the project")

        assertEquals(ChatRole.AGENT, turn.response.role)
        assertEquals(2, workspace.history().size)
        assertEquals("Inspect the project", workspace.history().first().content)
        assertTrue(workspace.history().last().content.contains("completed"))
    }

    @Test
    fun includesPreviousConversationInFollowUpRequest() {
        val store = MemoryChatStore()
        val requests = mutableListOf<String>()
        val workspace = ChatWorkspace(store) {
            CodingAgentExecutor { request ->
                requests += request
                fakeTaskResult("completed")
            }
        }

        workspace.send("Use Kotlin")
        workspace.send("Continue with that choice")

        assertEquals(2, requests.size)
        assertTrue(requests.last().contains("Use Kotlin"))
        assertTrue(requests.last().contains("Continue with that choice"))
    }

    private class MemoryChatStore : ChatMessageStore {
        private val messages = mutableListOf<ChatMessage>()

        override fun recordChatMessage(message: ChatMessage) {
            messages += message
        }

        override fun recentChatMessages(limit: Int): List<ChatMessage> = messages.takeLast(limit).asReversed()
    }

    private class FakeExecutor(private val status: String) : CodingAgentExecutor {
        override fun execute(request: String): AgentRuntimeResult = fakeTaskResult(status)
    }

    private companion object {
        fun fakeTaskResult(status: String): AgentRuntimeResult = AgentRuntimeResult.Completed(
            AgentTask(
                id = "chat-test-task",
                request = "test",
                status = status,
                plan = AgentPlan("test", emptyList(), emptyList()),
                changes = emptyList(),
                verification = VerificationReport(true, emptyList()),
                events = listOf("chat test"),
                summary = "chat response"
            )
        )
    }
}
