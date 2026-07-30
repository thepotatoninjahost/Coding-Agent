package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalAgentTest {
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
        val runtime = CodingAgentRuntime(workspace, object : AgentKnowledge {
            override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
        }, AgentJournal(root))
        val events = AgentOrchestrator(runtime, TerminalSession(root), object : WebResearchProvider {
            override fun search(query: String, limit: Int): ResearchResult = ResearchResult(query, emptyList())
        }).execute("inspect the project")
        assertTrue(events.any { it is AgentExecutionEvent.Phase && it.name == "INTAKE" })
        assertTrue(events.last() is AgentExecutionEvent.Completed)
    }

    @Test fun terminalOrchestratorSurfacesExitCode() {
        val root = Files.createTempDirectory("agent-terminal").toFile()
        val workspace = ProjectWorkspace(root)
        val runtime = CodingAgentRuntime(workspace, object : AgentKnowledge {
            override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
        }, AgentJournal(root))
        val output = AgentOrchestrator(runtime, TerminalSession(root), DuckDuckGoResearchProvider()).runTerminal("printf ready; exit 3")
        assertTrue(output.text.contains("exit=3"))
    }
}
