package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.AgentKnowledge
import com.codingagent.intake.CodeSynthesisEngine
import com.codingagent.intake.OperationKind
import com.codingagent.intake.SynthesisResult
import com.codingagent.intake.TaskIntakeParser
import com.codingagent.workspace.KnowledgeHit

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

    @Test fun createGoalGeneratesLanguageSpecificFile() {
        val root = Files.createTempDirectory("synthesis").toFile()
        val intake = TaskIntakeParser(root).parse("create a Kotlin helper in src/Helper.kt")
        val result = CodeSynthesisEngine(root, knowledge).synthesize(intake)
        assertTrue(result is SynthesisResult.Ready)
        val text = (result as SynthesisResult.Ready).proposal.operations.single().text.orEmpty()
        assertTrue(text.contains("class Helper"))
        assertTrue(text.contains("fun run"))
    }

    @Test fun vagueChangeDoesNotInventAFile() {
        val root = Files.createTempDirectory("synthesis").toFile()
        val intake = TaskIntakeParser(root).parse("improve the login flow")
        assertTrue(CodeSynthesisEngine(root, knowledge).synthesize(intake) is SynthesisResult.NeedsInput)
    }
}
