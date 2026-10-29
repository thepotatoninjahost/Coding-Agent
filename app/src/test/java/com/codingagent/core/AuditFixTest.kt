package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.AgentRequestKind
import com.codingagent.research.ResearchBriefBuilder
import com.codingagent.ui.mapAgentPhase
import com.codingagent.ui.AgentStatus
import com.codingagent.workspace.ResearchSession
import com.codingagent.workspace.ResearchSource

class AuditFixTest {
    @Test
    fun statusPhasesMatchTheAgentEvents() {
        assertEquals(AgentStatus.RESEARCHING, mapAgentPhase("RESEARCH"))
        assertEquals(AgentStatus.PLANNING, mapAgentPhase("PLANNING"))
        assertEquals(AgentStatus.MODEL, mapAgentPhase("MODEL"))
        assertEquals(AgentStatus.TOOL, mapAgentPhase("TOOL"))
        assertEquals(AgentStatus.STOPPED, mapAgentPhase("STOPPED"))
        assertEquals(AgentStatus.FAILED, mapAgentPhase("FAILED"))
    }

    @Test
    fun researchBriefSaysWhenTheTurnTextIsCut() {
        val source = ResearchSource(
            title = "Example",
            url = "https://example.com/doc",
            domain = "example.com",
            lane = "primary",
            status = 200,
            wordCount = 40,
            content = "word ".repeat(80),
            codeExamples = emptyList()
        )
        val session = ResearchSession(
            id = "s",
            query = "kotlin",
            createdAt = 0L,
            requestedSources = 1,
            sources = listOf(source),
            learnedChunks = 1
        )
        val brief = ResearchBriefBuilder.build(session, maxCharacters = 120)
        assertTrue(brief.truncated)
        assertEquals(40, brief.wordCount)
        assertTrue(brief.evidence.contains("truncated to 120 characters"))
        assertTrue(brief.evidence.contains("40 words"))
        assertFalse(brief.evidence.contains(source.content))
    }

    @Test
    fun helpMeFixIsNotTreatedAsAStatusQuestion() {
        assertFalse(AgentRequestKind.isStatus("help me fix the bug"))
        assertTrue(AgentRequestKind.isStatus("help"))
        assertTrue(AgentRequestKind.isStatus("what can you do"))
    }

}
