package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousLoopTest {
    @Test
    fun toolCatalogMatchesExecutor() {
        val names = AgentModelProtocol.tools().map { it.name }.toSet()
        val required = setOf(
            "list_files", "read_file", "search_project", "search_knowledge", "research_web",
            "replace_text", "create_file", "approve_change", "reject_change", "run_command", "verify"
        )
        assertTrue(names.containsAll(required))
    }

    @Test
    fun degenerateDetectorCatchesLineAndCharSpam() {
        val repeated = (1..12).joinToString("\n") { "same line forever" }
        assertTrue(DegenerateOutput.isDegenerate(repeated))
        assertTrue(DegenerateOutput.isDegenerate("a".repeat(120)))
        assertTrue(!DegenerateOutput.isDegenerate("Short normal reply about the project."))
    }

    @Test
    fun mockGatewayCompletesAfterTools() {
        val root = Files.createTempDirectory("agent-loop").toFile()
        root.resolve("src").mkdirs()
        root.resolve("src/Hello.kt").writeText("fun hello() = 1\n")
        val gateway = ScriptedGateway(
            listOf(
                ModelResponse.ToolCall("list_files", """{"path":"src"}"""),
                ModelResponse.Text("Found the source tree under src/.")
            )
        )
        val workspace = ProjectWorkspace(root)
        val knowledge = object : AgentKnowledge {
            override fun search(query: String, limit: Int) = emptyList<KnowledgeHit>()
        }
        val runtime = CodingAgentRuntime(
            workspace,
            knowledge,
            AgentJournal(root),
            modelGateway = gateway
        )
        val agent = AutonomousAgent(root, runtime, knowledge, gateway, AutonomousAgentConfig(maxTurns = 6))
        val events = agent.run("List the source files and summarize")
        assertTrue(events.any { it is AutonomousAgentEvent.ToolFinished })
        assertTrue(events.last() is AutonomousAgentEvent.Completed)
    }

    @Test
    fun cancelBetweenTurnsEmitsStopped() {
        val root = Files.createTempDirectory("agent-cancel").toFile()
        root.resolve("README.md").writeText("# demo\n")
        val gateway = object : ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse =
                ModelResponse.ToolCall("list_files", "{}")

            override fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse =
                complete(request)
        }
        val workspace = ProjectWorkspace(root)
        val knowledge = object : AgentKnowledge {
            override fun search(query: String, limit: Int) = emptyList<KnowledgeHit>()
        }
        val runtime = CodingAgentRuntime(workspace, knowledge, AgentJournal(root), modelGateway = gateway)
        val agent = AutonomousAgent(
            root, runtime, knowledge, gateway,
            AutonomousAgentConfig(maxTurns = 8, maxIdenticalToolRepeats = 99)
        )
        var turns = 0
        val events = agent.run("Keep listing files") { event ->
            if (event is AutonomousAgentEvent.ToolFinished) {
                turns++
                if (turns >= 1) agent.cancel("test-stop")
            }
        }
        assertTrue(events.any { it is AutonomousAgentEvent.Stopped })
        assertEquals("stopped", (events.last() as AutonomousAgentEvent.Stopped).task.status)
    }

    @Test
    fun identicalToolLoopAborts() {
        val root = Files.createTempDirectory("agent-loop-repeat").toFile()
        root.resolve("a.txt").writeText("hello world content enough\n")
        val gateway = ScriptedGateway(
            List(6) { ModelResponse.ToolCall("list_files", "{}") }
        )
        val workspace = ProjectWorkspace(root)
        val knowledge = object : AgentKnowledge {
            override fun search(query: String, limit: Int) = emptyList<KnowledgeHit>()
        }
        val runtime = CodingAgentRuntime(workspace, knowledge, AgentJournal(root), modelGateway = gateway)
        val agent = AutonomousAgent(
            root, runtime, knowledge, gateway,
            AutonomousAgentConfig(maxTurns = 10, maxIdenticalToolRepeats = 3)
        )
        val events = agent.run("List files repeatedly")
        assertTrue(events.last() is AutonomousAgentEvent.Failed)
        assertTrue((events.last() as AutonomousAgentEvent.Failed).message.contains("repeated"))
    }
}

/** Deterministic gateway that returns scripted responses in order. */
private class ScriptedGateway(private val script: List<ModelResponse>) : ModelGateway {
    private var index = 0
    override fun complete(request: ModelRequest): ModelResponse {
        val response = script.getOrElse(index) { ModelResponse.Text("done") }
        index++
        return response
    }

    override fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse {
        val response = complete(request)
        if (response is ModelResponse.Text) onDelta(response.content)
        return response
    }
}
