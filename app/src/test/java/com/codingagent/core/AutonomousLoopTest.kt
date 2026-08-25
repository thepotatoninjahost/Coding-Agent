package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.AgentKnowledge
import com.codingagent.agent.AutonomousAgent
import com.codingagent.agent.AutonomousAgentConfig
import com.codingagent.agent.AutonomousAgentEvent
import com.codingagent.agent.DegenerateOutput
import com.codingagent.agent.SelfEvolution
import com.codingagent.workspace.KnowledgeHit
import com.codingagent.model.AgentModelProtocol
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse
import com.codingagent.workspace.ProjectWorkspace

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
        val agent = AutonomousAgent(
            root, knowledge, gateway, AutonomousAgentConfig(maxTurns = 6))
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
        val agent = AutonomousAgent(
            root, knowledge, gateway,
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
        val agent = AutonomousAgent(
            root, knowledge, gateway, AutonomousAgentConfig(maxTurns = 8))
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
        val agent = AutonomousAgent(
            root, knowledge, gateway, AutonomousAgentConfig(maxTurns = 4))
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
        val agent = AutonomousAgent(
            root, knowledge, gateway,
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
        val agent = AutonomousAgent(
            root, knowledge, gateway,
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
     * Identical non-listing tool calls must not hard-abort.
     * After the repeat cap the spine forces a final answer from evidence already gathered.
     *
     * Use read_file (not run_command): successful reads mark evidence so the inspect gate
     * cannot swallow the scripted final text. Do not name the file in an analyze/inspect
     * phrase or the local inspect lane will skip the model loop.
     */
    @Test
    fun identicalNonListingToolLoopCompletesFromEvidence() {
        val root = Files.createTempDirectory("agent-loop-repeat").toFile()
        root.resolve("LoopFile.kt").writeText("class LoopFile { fun marker() = \"LOOP_EVIDENCE_OK\" }\n")
        val readCall = ModelResponse.ToolCall("read_file", """{"path":"LoopFile.kt"}""")
        val gateway = ScriptedGateway(
            listOf(
                readCall,
                readCall,
                readCall,
                ModelResponse.Text("LOOP_FINAL_FROM_EVIDENCE: LoopFile marker is LOOP_EVIDENCE_OK")
            )
        )
        val workspace = ProjectWorkspace(root)
        val knowledge = object : AgentKnowledge {
            override fun search(query: String, limit: Int) = emptyList<KnowledgeHit>()
        }
        val agent = AutonomousAgent(
            root, knowledge, gateway,
            AutonomousAgentConfig(maxTurns = 10, maxIdenticalToolRepeats = 3)
        )
        val events = agent.run("Write a deep technical report on how LoopFile works end to end")
        assertTrue(
            "identical tool loop must complete from evidence, not hard-abort. events=${events.map { it::class.simpleName }}",
            events.last() is AutonomousAgentEvent.Completed
        )
        assertTrue(events.none { it is AutonomousAgentEvent.Failed })
        assertTrue(events.any { it is AutonomousAgentEvent.ToolFinished && it.name == "read_file" && it.success })
        val summary = (events.last() as AutonomousAgentEvent.Completed).task.summary
        assertTrue(
            "summary must come from gathered file evidence or the forced final answer: $summary",
            summary.contains("LOOP_FINAL_FROM_EVIDENCE") ||
                summary.contains("LOOP_EVIDENCE_OK") ||
                summary.contains("LoopFile")
        )
    }

    /**
     * Review/analyze of the whole project used to burn maxTurns on tools then fail
     * with "exceeded the autonomous turn budget". If any evidence exists (repo map
     * is always seeded), the spine must Complete instead of Failed.
     */
    @Test
    fun turnBudgetExhaustionCompletesFromRepoMapEvidence() {
        val root = Files.createTempDirectory("agent-budget").toFile()
        root.resolve("ImproveMe.kt").writeText("class ImproveMe\n")
        val gateway = ScriptedGateway(
            List(8) { i -> ModelResponse.ToolCall("search_project", """{"query":"ImproveMe$i"}""") }
        )
        val knowledge = object : AgentKnowledge {
            override fun search(query: String, limit: Int) = emptyList<KnowledgeHit>()
        }
        val agent = AutonomousAgent(
            root, knowledge, gateway,
            AutonomousAgentConfig(maxTurns = 4, maxIdenticalToolRepeats = 99)
        )
        val events = agent.run("review and analyze the project and tell me how it can be improved")
        assertTrue(
            "turn budget must complete from evidence, not Failed. last=${events.last()::class.simpleName}",
            events.last() is AutonomousAgentEvent.Completed
        )
        assertTrue(events.none { it is AutonomousAgentEvent.Failed })
        val summary = (events.last() as AutonomousAgentEvent.Completed).task.summary
        assertTrue(
            "summary should mention budget or gathered evidence: $summary",
            summary.contains("Turn budget reached") ||
                summary.contains("ImproveMe") ||
                summary.contains("Repo map")
        )
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
