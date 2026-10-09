package com.codingagent.core

import com.codingagent.model.AgentModelProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelProtocolTest {
    @Test
    fun defaultSystemPromptRequiresPlainDirectUserLanguage() {
        val prompt = AgentModelProtocol.DEFAULT_SYSTEM

        assertTrue(prompt.contains("Use plain, everyday language"))
        assertTrue(prompt.contains("Avoid jargon"))
        assertTrue(prompt.contains("Be direct and concise"))
        assertTrue(prompt.contains("Do not narrate internal planning"))
    }

    @Test
    fun defaultSystemPromptUsesOwnerDirectedAutonomousWorkWithoutArbitraryToolLimit() {
        val prompt = AgentModelProtocol.DEFAULT_SYSTEM

        assertTrue(prompt.contains("Follow the user's stated goals and acceptance criteria"))
        assertTrue(prompt.contains("there is no arbitrary limit on useful tool calls"))
        assertTrue(prompt.contains("Do not stop while material dependencies"))
        assertTrue(prompt.contains("If the user asked to improve, change, edit, or implement something, a written review is not the work"))
        assertTrue(prompt.contains("The model cannot approve its own proposal"))
        assertTrue(prompt.contains("When you use research_web or search_knowledge, cite what you found"))
    }

    @Test
    fun defaultSystemPromptDoesNotRequireArbitraryStopAfterFewToolResults() {
        val prompt = AgentModelProtocol.DEFAULT_SYSTEM

        assertFalse(prompt.contains("After two or three useful tool results, stop gathering"))
    }
}
