package com.codingagent.core
import com.codingagent.terminal.TerminalSession

import com.codingagent.research.*

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalAgentTest {
    private val emptyKnowledge = object : AgentKnowledge {
        override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
    }

    private val emptyResearch = object : WebResearchProvider {
        override fun search(query: String, limit: Int): ResearchResult = ResearchResult(query, emptyList())
    }

    private val successfulResearch = object : DeepResearchProvider {
        override fun deepResearch(
            query: String,
            targetSources: Int,
            mode: ResearchMode,
            onProgress: (DeepResearchProgress) -> Unit,
            cancellation: ResearchCancellation
        ): ResearchSession {
            val source = ResearchSource(
                title = "Test documentation",
                url = "https://example.com/test-documentation",
                domain = "example.com",
                lane = "test",
                status = 200,
                wordCount = 80,
                content = "Test documentation for the requested code change. ".repeat(20)
            )
            onProgress(DeepResearchProgress("learned", 1, 1, 1, 0))
            return ResearchSession("test-research", query, 1L, 1, listOf(source), 1, mode = mode.name.lowercase())
        }
    }

    @Test fun editorRoundTripUsesProjectRelativePathsAndTransactions() {
        val root = Files.createTempDirectory("agent-editor").toFile()
        root.resolve("src").mkdirs()
        root.resolve("src/Main.kt").writeText("fun main() = 1\n")
        val workspace = ProjectWorkspace(root)
        val files = ProjectFileService(workspace)
        assertTrue(files.list().contains("src"))
        val document = files.read("src/Main.kt")
        assertEquals("fun main() = 1\n", document.content)
        val coordinator = MutationCoordinator(workspace)
        val proposal = files.save("src/Main.kt", "fun main() = 2\n", coordinator)
        assertEquals(ChangeOperation.REPLACE, proposal.changeSet.changes.single().operation)
        assertEquals("fun main() = 1\n", files.read("src/Main.kt").content)
        assertTrue(coordinator.approve(proposal.id, true, "test") is MutationApprovalResult.AwaitingSecond)
        assertTrue(coordinator.approve(proposal.id, true, "test") is MutationApprovalResult.Applied)
        assertEquals("fun main() = 2\n", files.read("src/Main.kt").content)
    }

    @Test fun orchestratorEmitsPhasesAndCompletesInspectableRequest() {
        val root = Files.createTempDirectory("agent-orchestrator").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val workspace = ProjectWorkspace(root)
        val runtime = CodingAgentRuntime(workspace, emptyKnowledge, AgentJournal(root))
        val events = AgentOrchestrator(runtime, TerminalSession(root), emptyResearch).execute("inspect the project")
        assertTrue(events.any { it is AgentExecutionEvent.Phase && it.name == "INTAKE" })
        assertTrue(events.last() is AgentExecutionEvent.Completed)
    }

    @Test fun plainEnglishDebugWithoutModelNeedsInputOrFailsClosed() {
        val root = Files.createTempDirectory("agent-plain").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val workspace = ProjectWorkspace(root)
        val runtime = CodingAgentRuntime(workspace, emptyKnowledge, AgentJournal(root))
        val events = AgentOrchestrator(runtime, TerminalSession(root), emptyResearch).execute("fix the login bug")
        assertTrue(events.none { it is AgentExecutionEvent.NeedsInput && events.indexOf(it) == 0 })
        assertTrue(events.any { it is AgentExecutionEvent.Phase && it.name == "EXECUTE" })
        // Vague offline edit: NeedsInput (ask for explicit op / model) or Failed — never silent write.
        val terminal = events.last()
        assertTrue(terminal is AgentExecutionEvent.NeedsInput || terminal is AgentExecutionEvent.Failed)
        assertEquals("fun main() = 1\n", root.resolve("Main.kt").readText())
    }

    @Test fun offlineExplicitReplaceStagesProposalWithoutModel() {
        val root = Files.createTempDirectory("agent-offline-replace").toFile()
        root.resolve("src").mkdirs()
        root.resolve("src/Main.kt").writeText("fun main() = 1\n")
        val workspace = ProjectWorkspace(root)
        val runtime = CodingAgentRuntime(workspace, emptyKnowledge, AgentJournal(root))
        val result = runtime.execute("replace = 1 with = 2 in src/Main.kt")
        assertTrue(result is AgentRuntimeResult.NeedsApproval)
        result as AgentRuntimeResult.NeedsApproval
        assertTrue(result.proposalId.isNotBlank())
        assertEquals("fun main() = 1\n", root.resolve("src/Main.kt").readText())

        assertTrue(runtime.approveProposal(result.proposalId, true, "owner") is MutationApprovalResult.AwaitingSecond)
        assertTrue(runtime.approveProposal(result.proposalId, true, "owner") is MutationApprovalResult.Applied)
        assertEquals("fun main() = 2\n", root.resolve("src/Main.kt").readText())
    }

    @Test fun offlineCreateSynthesisStagesProposalWithoutModel() {
        val root = Files.createTempDirectory("agent-offline-create").toFile()
        val workspace = ProjectWorkspace(root)
        val runtime = CodingAgentRuntime(workspace, emptyKnowledge, AgentJournal(root))
        val result = runtime.execute("create a Kotlin helper in src/Helper.kt")
        assertTrue(result is AgentRuntimeResult.NeedsApproval)
        result as AgentRuntimeResult.NeedsApproval
        assertTrue(result.proposalId.isNotBlank())
        assertTrue(!root.resolve("src/Helper.kt").exists())

        assertTrue(runtime.approveProposal(result.proposalId, true, "owner") is MutationApprovalResult.AwaitingSecond)
        assertTrue(runtime.approveProposal(result.proposalId, true, "owner") is MutationApprovalResult.Applied)
        val written = root.resolve("src/Helper.kt").readText()
        assertTrue(written.contains("class Helper"))
        assertTrue(written.contains("fun run"))
    }

    @Test fun offlineExplicitCreateThroughOrchestratorIsApprovalRequired() {
        val root = Files.createTempDirectory("agent-offline-orch").toFile()
        val workspace = ProjectWorkspace(root)
        val runtime = CodingAgentRuntime(workspace, emptyKnowledge, AgentJournal(root))
        val events = AgentOrchestrator(runtime, TerminalSession(root), emptyResearch)
            .execute("create file src/New.kt with fun answer() = 42")
        val terminal = events.last()
        assertTrue(terminal is AgentExecutionEvent.ApprovalRequired)
        terminal as AgentExecutionEvent.ApprovalRequired
        assertTrue(terminal.proposalId.isNotBlank())
        assertTrue(!root.resolve("src/New.kt").exists())
    }

    @Test fun orchestratorSurfacesApprovalRequiredInsteadOfFailed() {
        val root = Files.createTempDirectory("agent-approval").toFile()
        root.resolve("src").mkdirs()
        root.resolve("src/Main.kt").writeText("fun main() = 1\n")
        val workspace = ProjectWorkspace(root)
        val gateway = object : ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse =
                com.codingagent.model.ModelResponse.ToolCall(
                    name = "propose_changes",
                    arguments = """{"reason":"test","operations":[{"kind":"replace","path":"src/Main.kt","oldText":"fun main() = 1\n","newText":"fun main() = 2\n"}]}"""
                )
        }
        val runtime = CodingAgentRuntime(
            workspace,
            emptyKnowledge,
            AgentJournal(root),
            deepResearch = successfulResearch,
            modelGateway = gateway
        )
        val events = AgentOrchestrator(runtime, TerminalSession(root), emptyResearch)
            .execute("replace = 1 with = 2 in src/Main.kt")
        val approval = events.last()
        assertTrue(approval is AgentExecutionEvent.ApprovalRequired)
        approval as AgentExecutionEvent.ApprovalRequired
        assertTrue(approval.proposalId.isNotBlank())
        assertTrue(approval.question.contains("confirm", ignoreCase = true))
        assertEquals("fun main() = 1\n", root.resolve("src/Main.kt").readText())
        // Single proposal only (no double-stage).
        assertEquals(1, runtime.pendingProposals().size)
    }

    @Test fun terminalOrchestratorSurfacesExitCode() {
        val root = Files.createTempDirectory("agent-terminal").toFile()
        val workspace = ProjectWorkspace(root)
        val runtime = CodingAgentRuntime(workspace, emptyKnowledge, AgentJournal(root))
        val output = AgentOrchestrator(runtime, TerminalSession(root), DuckDuckGoResearchProvider()).runTerminal("printf ready; exit 3")
        assertTrue(output.text.contains("exit=3"))
    }
}
