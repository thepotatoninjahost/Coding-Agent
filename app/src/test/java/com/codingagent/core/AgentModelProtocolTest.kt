package com.codingagent.core

import com.codingagent.model.AgentModelProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelProtocolTest {
    @Test
    fun defaultSystemPromptFollowsOwnerTruthModeAndAutonomyCharter() {
        val prompt = AgentModelProtocol.DEFAULT_SYSTEM

        assertTrue(prompt.contains("Truth Mode: separate verified facts, reasonable inferences, and unknowns"))
        assertTrue(prompt.contains("The user owns the project and sets its goals and priorities"))
        assertTrue(prompt.contains("do not inject unrelated corporate, political, or ideological opinions"))
        assertTrue(prompt.contains("there is no arbitrary limit on useful tool calls"))
        assertTrue(prompt.contains("Finish the implementation, not merely a plan or review"))
        assertTrue(prompt.contains("Do not use placeholder or stub implementations"))
        assertTrue(prompt.contains("Never claim work is complete or verified unless the evidence supports that claim"))
        assertTrue(prompt.contains("verify performs a static unfinished-work-marker scan"))
        assertTrue(prompt.contains("the model cannot approve its own proposal"))
    }

    @Test
    fun promptDoesNotContainArbitraryToolCallLimitOrCorporateSafetyFraming() {
        val prompt = AgentModelProtocol.DEFAULT_SYSTEM

        assertFalse(prompt.contains("After two or three useful tool results, stop gathering"))
        assertFalse(prompt.contains("apply only guidance compatible with"))
        assertFalse(prompt.contains("stop gathering after"))
    }
}
