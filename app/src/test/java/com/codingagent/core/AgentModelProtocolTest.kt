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
        assertTrue(prompt.contains("Do not invoke git, find, gradle"))
        assertTrue(prompt.contains("There is no fixed tool-call count"))
        assertTrue(prompt.contains("Inspect relevant callers, dependencies, tests, and failure paths"))
        assertTrue(prompt.contains("Do not stop at a plan, a diagnosis, or a superficial edit"))
        assertTrue(prompt.contains("Prefer official documentation and tested reference implementations"))
    }
}
