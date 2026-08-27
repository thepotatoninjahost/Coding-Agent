package com.codingagent.core

import com.codingagent.agent.ApprovalChannel
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.AgentKnowledge
import com.codingagent.agent.AgentRuntimeResult
import com.codingagent.agent.AutonomousAgent
import com.codingagent.agent.AutonomousAgentEvent
import com.codingagent.workspace.KnowledgeHit
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse
import com.codingagent.workspace.ChangeOperation
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.TerminalSession

/**
 * Operational checks against the single spine ([AutonomousAgent]).
 */
class OperationalAgentTest {
    private val emptyKnowledge = object : AgentKnowledge {
        override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
    }

    private fun agent(root: java.io.File, gateway: ModelGateway? = null): AutonomousAgent =
        AutonomousAgent(root, emptyKnowledge, gateway)

    @Test
    fun editorRoundTripUsesProjectRelativePathsAndTransactions() {
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
        assertTrue(coordinator.approve(proposal.id, true, "test", ApprovalChannel.BIOMETRIC) is MutationApprovalResult.AwaitingSecond)
        assertTrue(coordinator.approve(proposal.id, true, "test", ApprovalChannel.SPOKEN_PASSWORD) is MutationApprovalResult.Applied)
        assertEquals("fun main() = 2\n", files.read("src/Main.kt").content)
    }

    @Test
    fun singleSpineCompletesInspectableRequest() {
        val root = Files.createTempDirectory("agent-inspect").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val events = agent(root).run("inspect the project")
        assertTrue(events.any { it is AutonomousAgentEvent.Phase && it.name == "INTAKE" })
        assertTrue(events.last() is AutonomousAgentEvent.Completed)
    }

    @Test
    fun plainEnglishDebugWithoutModelNeedsInputOrFailsClosed() {
        val root = Files.createTempDirectory("agent-plain").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val events = agent(root).run("fix the login bug")
        val terminal = events.last()
        assertTrue(
            terminal is AutonomousAgentEvent.Completed ||
                terminal is AutonomousAgentEvent.Failed
        )
        assertEquals("fun main() = 1\n", root.resolve("Main.kt").readText())
    }

    @Test
    fun offlineExplicitReplaceStagesProposalWithoutModel() {
        val root = Files.createTempDirectory("agent-offline-replace").toFile()
        root.resolve("src").mkdirs()
        root.resolve("src/Main.kt").writeText("fun main() = 1\n")
        val spine = agent(root)
        val result = spine.execute("replace = 1 with = 2 in src/Main.kt")
        assertTrue(result is AgentRuntimeResult.NeedsApproval)
        result as AgentRuntimeResult.NeedsApproval
        assertTrue(result.proposalId.isNotBlank())
        assertEquals("fun main() = 1\n", root.resolve("src/Main.kt").readText())

        assertTrue(spine.approveProposal(result.proposalId, true, "owner", ApprovalChannel.BIOMETRIC) is MutationApprovalResult.AwaitingSecond)
        assertTrue(spine.approveProposal(result.proposalId, true, "owner", ApprovalChannel.SPOKEN_PASSWORD) is MutationApprovalResult.Applied)
        assertEquals("fun main() = 2\n", root.resolve("src/Main.kt").readText())
    }

    @Test
    fun offlineCreateSynthesisStagesProposalWithoutModel() {
        val root = Files.createTempDirectory("agent-offline-create").toFile()
        val spine = agent(root)
        val result = spine.execute("create file src/Helper.kt with class Helper { fun run() = 1 }")
        assertTrue("expected NeedsApproval, got $result", result is AgentRuntimeResult.NeedsApproval)
        result as AgentRuntimeResult.NeedsApproval
        assertTrue(result.proposalId.isNotBlank())
        assertTrue(!root.resolve("src/Helper.kt").exists())

        assertTrue(spine.approveProposal(result.proposalId, true, "owner", ApprovalChannel.BIOMETRIC) is MutationApprovalResult.AwaitingSecond)
        assertTrue(spine.approveProposal(result.proposalId, true, "owner", ApprovalChannel.SPOKEN_PASSWORD) is MutationApprovalResult.Applied)
        val written = root.resolve("src/Helper.kt").readText()
        assertTrue(written.contains("class Helper"))
        assertTrue(written.contains("fun run"))
    }

    @Test
    fun offlineExplicitCreateThroughSpineIsApprovalRequired() {
        val root = Files.createTempDirectory("agent-offline-orch").toFile()
        val events = agent(root).run("create file src/New.kt with fun answer() = 42")
        val terminal = events.last()
        assertTrue(terminal is AutonomousAgentEvent.ApprovalRequired)
        terminal as AutonomousAgentEvent.ApprovalRequired
        assertTrue(terminal.proposal.id.isNotBlank())
        assertTrue(!root.resolve("src/New.kt").exists())
    }

    @Test
    fun spineSurfacesApprovalRequiredInsteadOfFailed() {
        val root = Files.createTempDirectory("agent-approval").toFile()
        root.resolve("src").mkdirs()
        root.resolve("src/Main.kt").writeText("fun main() = 1\n")
        val gateway = object : ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse =
                ModelResponse.ToolCall(
                    name = "replace_text",
                    arguments = """{"path":"src/Main.kt","oldText":"fun main() = 1\n","newText":"fun main() = 2\n","reason":"test"}"""
                )
        }
        val spine = agent(root, gateway)
        val events = spine.run("replace = 1 with = 2 in src/Main.kt")
        val approval = events.last()
        assertTrue(approval is AutonomousAgentEvent.ApprovalRequired)
        approval as AutonomousAgentEvent.ApprovalRequired
        assertTrue(approval.proposal.id.isNotBlank())
        assertEquals("fun main() = 1\n", root.resolve("src/Main.kt").readText())
        assertEquals(1, spine.pendingProposals().size)
    }

    @Test
    fun terminalSurfacesExitCode() {
        val root = Files.createTempDirectory("agent-terminal").toFile()
        val result = TerminalSession(root).execute("printf ready; exit 3")
        val text = buildString {
            append("$ ").append(result.command).append('\n')
            append(result.stdout)
            if (result.stderr.isNotBlank()) append(result.stderr)
            append("\nexit=").append(result.exitCode)
        }
        assertTrue(text.contains("exit=3"))
    }
}
