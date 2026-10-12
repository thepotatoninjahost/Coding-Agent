package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.AgentKnowledge
import com.codingagent.agent.AgentOfflineStager
import com.codingagent.agent.AgentPlanner
import com.codingagent.intake.CodeSynthesisEngine
import com.codingagent.intake.OperationKind
import com.codingagent.intake.SynthesisResult
import com.codingagent.intake.TaskIntakeParser
import com.codingagent.workspace.KnowledgeHit
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse

class CodeSynthesisTest {
    private val knowledge = object : AgentKnowledge {
        override fun search(query: String, limit: Int): List<KnowledgeHit> = listOf(KnowledgeHit("local", "functions", 3, "Use a small function with a clear return value."))
    }

    @Test fun explicitOperationBecomesProposal() {
        val root = Files.createTempDirectory("synthesis").toFile()
        val intake = TaskIntakeParser(root).parse("create file src/New.kt with class New")
        val result = CodeSynthesisEngine(root, knowledge).synthesize(intake)
        assertTrue(result is SynthesisResult.Ready)
        val proposal = (result as SynthesisResult.Ready).proposal
        assertEquals(OperationKind.CREATE_FILE, proposal.operations.single().kind)
        assertEquals("src/New.kt", proposal.operations.single().path)
        assertTrue(proposal.knowledgeUsed.isNotEmpty())
    }

    @Test fun underspecifiedCreateDoesNotGeneratePassThroughScaffold() {
        val root = Files.createTempDirectory("synthesis").toFile()
        val intake = TaskIntakeParser(root).parse("create a Kotlin helper in src/Helper.kt")
        val result = CodeSynthesisEngine(root, knowledge).synthesize(intake)
        assertTrue(result is SynthesisResult.NeedsInput)
        val question = (result as SynthesisResult.NeedsInput).question
        assertTrue(question.contains("does not define the implementation"))
        assertTrue(question.contains("No generic pass-through scaffold was generated"))
        assertTrue(!root.resolve("src/Helper.kt").exists())
    }

    @Test fun vagueChangeDoesNotInventAFile() {
        val root = Files.createTempDirectory("synthesis").toFile()
        val intake = TaskIntakeParser(root).parse("improve the login flow")
        assertTrue(CodeSynthesisEngine(root, knowledge).synthesize(intake) is SynthesisResult.NeedsInput)
    }
    @Test fun configuredModelDoesNotReceiveAnOfflinePlaceholderProposal() {
        val root = Files.createTempDirectory("synthesis-model-route").toFile()
        val request = "create a Kotlin helper in src/Helper.kt"
        val intake = TaskIntakeParser(root).parse(request)
        val workspace = ProjectWorkspace(root)
        val gateway = object : ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse =
                ModelResponse.Text("Model should be reached by the autonomous loop, not offline staging.")
        }

        val staged = AgentOfflineStager.stage(
            taskId = "task-1",
            request = request,
            intake = intake,
            plan = AgentPlanner(workspace).plan(intake),
            workspace = workspace,
            knowledge = knowledge,
            mutations = MutationCoordinator(workspace),
            gateway = gateway
        )

        assertEquals(null, staged)
        assertFalse("No generic file should be staged before model implementation", root.resolve("src/Helper.kt").exists())
    }

}
