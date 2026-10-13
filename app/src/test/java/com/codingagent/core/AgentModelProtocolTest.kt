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
        assertTrue(prompt.contains("Do not invoke git, find, gradle"))
        assertTrue(prompt.contains("There is no arbitrary two-or-three-result stopping limit"))
        assertTrue(prompt.contains("Inspect relevant callers, dependencies, tests, and failure paths"))
        assertTrue(prompt.contains("Do not stop at a plan, a diagnosis, or a superficial edit"))
        assertTrue(prompt.contains("Prefer official documentation and tested reference implementations"))
        assertTrue(prompt.contains("Verification honesty: distinguish static source checks from actual builds and tests"))
        assertTrue(prompt.contains("never claim it passed or imply the app is correct based only on static checks"))
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
        assertTrue(prompt.contains("exactly one tool call per turn"))
        assertTrue(prompt.contains("purpose field"))
    }
}
