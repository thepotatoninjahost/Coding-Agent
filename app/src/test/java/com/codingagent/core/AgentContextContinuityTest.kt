package com.codingagent.core

import com.codingagent.agent.AgentKnowledge
import com.codingagent.agent.AutonomousAgent
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse
import com.codingagent.workspace.KnowledgeHit
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextContinuityTest {
    @Test
    fun liveAgentIntakeKeepsOpenJobAndPriorOwnerConstraint() {
        val root = Files.createTempDirectory("agent-context-live").toFile()
        root.resolve("Existing.kt").writeText("class Existing")
        val captured = mutableListOf<ModelRequest>()
        val gateway = object : ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse {
                captured += request
                return ModelResponse.Text("I understand the request and can continue.")
            }

            override fun cancel() = Unit
        }
        val knowledge = object : AgentKnowledge {
            override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
        }
        val agent = AutonomousAgent(root, knowledge, gateway = gateway)

        agent.run(
            """
            - status: open
            - goal: Build me a compiler
            - staged paths:
            Conversation so far (oldest first).
            OWNER: Build me a compiler
            AGENT: Which languages should it support?
            OWNER: Do not create sample files.
            Current request:
            Implement compiler support for Kotlin and Python
            """.trimIndent()
        )

        val prompt = captured.joinToString("\n\n") { it.user }
        assertTrue("No model prompt captured: $captured", captured.isNotEmpty())
        assertTrue("Missing active job in prompt: $prompt", prompt.contains("Build me a compiler", ignoreCase = true))
        assertTrue("Missing follow-up in prompt: $prompt", prompt.contains("Kotlin and Python", ignoreCase = true))
        assertTrue("Missing owner constraint in prompt: $prompt", prompt.contains("Do not create sample files", ignoreCase = true))
    }
}
