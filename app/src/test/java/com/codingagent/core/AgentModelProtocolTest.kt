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
        assertTrue(prompt.contains("Be direct and concise"))
        assertTrue(prompt.contains("Do not narrate internal planning"))
    }
}
