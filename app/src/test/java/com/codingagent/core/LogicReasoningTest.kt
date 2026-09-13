package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.LogicReasoning

class LogicReasoningTest {
    @Test
    fun stripsThinkBlocksFromDisplay() {
        val raw = "<think>secret plan</think>\nChatWorkspace.kt is the chat spine."
        val verdict = LogicReasoning.inspect(raw, evidence = "ChatWorkspace.kt")
        assertEquals("ChatWorkspace.kt is the chat spine.", verdict.displayText)
        assertTrue(verdict.reasoning.contains("secret plan"))
        assertFalse(verdict.contradicted)
    }

    @Test
    fun reasoningOnlyIsNotAnAnswer() {
        val verdict = LogicReasoning.inspect("<thinking>ponder</thinking>")
        assertTrue(verdict.contradicted)
        assertTrue(verdict.issues.any { it.contains("reasoning-only") })
        assertTrue(verdict.displayText.contains("internal reasoning"))
    }

    @Test
    fun inventedPathAgainstEvidence() {
        val verdict = LogicReasoning.inspect(
            "The bug is in MagicKernel.kt on line 4.",
            evidence = "File: ChatWorkspace.kt\nclass ChatWorkspace"
        )
        assertTrue(verdict.contradicted)
        assertTrue(verdict.issues.any { it.contains("MagicKernel.kt") })
    }

    @Test
    fun knownPathIsNotInvented() {
        val verdict = LogicReasoning.inspect(
            "ChatWorkspace.kt owns the transcript.",
            evidence = "File: app/src/main/java/com/codingagent/agent/ChatWorkspace.kt"
        )
        assertFalse(verdict.contradicted)
    }

    @Test
    fun appliedClaimNeedsAppliedEvidence() {
        val verdict = LogicReasoning.inspect(
            "The change is already applied to disk.",
            evidence = "Proposal staged, awaiting dual approval"
        )
        assertTrue(verdict.issues.any { it.contains("APPLIED") })
    }

    @Test
    fun verifyPassContradictsFailedEvidence() {
        val verdict = LogicReasoning.inspect(
            "Static verification passed.",
            evidence = "Verification: FAILED (2 issue(s))"
        )
        assertTrue(verdict.issues.any { it.contains("verification") })
    }

    @Test
    fun splitReturnsBodyAndReasoning() {
        val (body, reasoning) = LogicReasoning.split("<think>x</think>\nDone.")
        assertEquals("Done.", body)
        assertTrue(reasoning.contains("x"))
    }
}
