package com.codingagent.core

import com.codingagent.model.AgentModelProtocol
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelProtocolTest {
    @Test
    fun defaultSystemPromptRequiresPlainDirectUserLanguage() {
        val prompt = AgentModelProtocol.DEFAULT_SYSTEM

        assertTrue(prompt.contains("Use plain, everyday language"))
        assertTrue(prompt.contains("Avoid jargon"))
        assertTrue(prompt.contains("Be direct"))
        assertTrue(prompt.contains("Do not expose hidden chain-of-thought"))
    }

    @Test
    fun defaultSystemPromptSupportsAmbitiousEvidenceDrivenSelfImprovement() {
        val prompt = AgentModelProtocol.DEFAULT_SYSTEM

        assertTrue(prompt.contains("large, complex, multi-file engineering tasks"))
        assertTrue(prompt.contains("There is no arbitrary two-or-three-result stopping limit"))
        assertTrue(prompt.contains("controlled experiments"))
        assertTrue(prompt.contains("Create tools and improve the agent's own code"))
        assertTrue(prompt.contains("accessible personal logs"))
        assertTrue(prompt.contains("static unfinished-work marker scan is not a substitute"))
        assertTrue(prompt.contains("the owner's decision"))
        assertTrue(prompt.contains("Exactly one tool call per turn"))
        assertTrue(prompt.contains("purpose field"))
    }
}
