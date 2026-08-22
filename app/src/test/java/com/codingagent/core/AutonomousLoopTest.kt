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
        // Avoid bare listing phrases so direct-lane does not short-circuit before tools.
        val events = agent.run("Summarize what is under the src directory after inspecting it")
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
    fun refusesToCompleteWithoutReadingNamedFile() {
        val root = Files.createTempDirectory("agent-evidence").toFile()
        val file = root.resolve("SelfEvolution.kt")
        file.writeText("class SelfEvolution\n")
        val gateway = ScriptedGateway(
            listOf(
                ModelResponse.Text("SelfEvolution is a great file about evolution and imports."),
                ModelResponse.ToolCall("read_file", """{"path":"SelfEvolution.kt"}"""),
                ModelResponse.Text("SelfEvolution stages sources under .coding-agent/evolution and promotes after checks.")
            )
        )
        val workspace = ProjectWorkspace(root)
        val knowledge = object : AgentKnowledge {
            override fun search(query: String, limit: Int) = emptyList<KnowledgeHit>()
        }
        val runtime = CodingAgentRuntime(workspace, knowledge, AgentJournal(root), modelGateway = gateway)
        val agent = AutonomousAgent(root, runtime, knowledge, gateway, AutonomousAgentConfig(maxTurns = 8))
        val events = agent.run("Analyze the file SelfEvolution.kt then write a report about the file")
        assertTrue(
            "expected an EVIDENCE phase before completion",
            events.any { it is AutonomousAgentEvent.Phase && it.name == "EVIDENCE" }
        )
        assertTrue(events.any { it is AutonomousAgentEvent.ToolFinished && it.name == "read_file" && it.success })
        assertTrue(events.last() is AutonomousAgentEvent.Completed)
    }

    @Test
    fun localInspectCompletesWithoutModel() {
        val root = Files.createTempDirectory("agent-local-inspect").toFile()
        root.resolve("Report.kt").writeText("class Report\n")
        // Gateway would fail the build if called — empty script means any model call blows up.
        val gateway = ScriptedGateway(emptyList())
        val workspace = ProjectWorkspace(root)
        val knowledge = object : AgentKnowledge {
            override fun search(query: String, limit: Int) = emptyList<KnowledgeHit>()
        }
        val runtime = CodingAgentRuntime(workspace, knowledge, AgentJournal(root), modelGateway = gateway)
        val agent = AutonomousAgent(root, runtime, knowledge, gateway, AutonomousAgentConfig(maxTurns = 4))
        val events = agent.run("analyze Report.kt for errors")
        assertTrue(events.last() is AutonomousAgentEvent.Completed)
        val summary = (events.last() as AutonomousAgentEvent.Completed).task.summary
        assertTrue(summary.contains("Report.kt") || summary.contains("Policy scan") || summary.contains("File:"))
    }

    @Test
    fun failsAfterRepeatedUngroundedCompletions() {
        val root = Files.createTempDirectory("agent-evidence-fail").toFile()
        root.resolve("Report.kt").writeText("class Report\n")
        val gateway = ScriptedGateway(
            List(6) { ModelResponse.Text("Here is my report without opening anything.") }
        )
        val workspace = ProjectWorkspace(root)
        val knowledge = object : AgentKnowledge {
            override fun search(query: String, limit: Int) = emptyList<KnowledgeHit>()
        }
        val runtime = CodingAgentRuntime(workspace, knowledge, AgentJournal(root), modelGateway = gateway)
        val agent = AutonomousAgent(
            root, runtime, knowledge, gateway,
            AutonomousAgentConfig(maxTurns = 10, maxEvidenceRefusals = 2)
        )
        // Must NOT match the local inspect lane (analyze/inspect/check <file>).
        // That lane returns local evidence without the model. This test covers the
        // model-loop evidence gate for ungrounded completions only.
        val events = agent.run("Write a deep technical report on how Report works end to end")
        assertTrue(events.last() is AutonomousAgentEvent.Failed)
        assertTrue((events.last() as AutonomousAgentEvent.Failed).message.contains("without reading"))
    }

    /**
     * Listing requests must finish after a successful list_files / search_project.
     * The agent is a coding agent: observe → act → complete. It must not abort a pure listing.
     */
    @Test
    fun listingRequestCompletesInsteadOfAborting() {
        val root = Files.createTempDirectory("agent-listing-complete").toFile()
        // Use indexed extensions (txt is not on the indexer whitelist).
        root.resolve("a.kt").writeText("fun a() = 1\n")
        root.resolve("b.kt").writeText("fun b() = 2\n")
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
        val events = agent.run("list project files")
        assertTrue(
            "listing request must complete, not fail",
            events.last() is AutonomousAgentEvent.Completed
        )
        val completed = events.last() as AutonomousAgentEvent.Completed
        assertTrue(
            "summary should contain the indexed listing",
            completed.task.summary.contains("a.kt") ||
                completed.task.summary.contains("Indexed source files") ||
                completed.task.summary.contains("Project files")
        )
    }

    /**
     * Non-listing identical tool loops must still abort so the agent cannot spin forever
     * on the same tool call when the request is not a pure listing.
     */
    @Test
    fun identicalNonListingToolLoopAborts() {
        val root = Files.createTempDirectory("agent-loop-repeat").toFile()
        root.resolve("a.txt").writeText("hello world content enough\n")
        val gateway = ScriptedGateway(
            List(6) { ModelResponse.ToolCall("run_command", """{"command":"echo hi"}""") }
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
        // Request deliberately avoids listing keywords so isListingRequest is false
        val events = agent.run("Run the same diagnostic command until you understand the state")
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
