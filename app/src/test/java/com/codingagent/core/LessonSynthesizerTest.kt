package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.EvolutionVersion
import com.codingagent.agent.LessonSynthesizer

class LessonSynthesizerTest {
    @Test
    fun emptyInputIsBlank() {
        assertEquals("", LessonSynthesizer.synthesize(emptyList()))
    }

    @Test
    fun splitsFailuresAndSuccesses() {
        val lines = listOf(
            "1\tfalse\twrite broken loop\tfailed\tmissing dual approval\tAutonomousAgent.kt",
            "2\ttrue\tread ChatWorkspace.kt\tcompleted\tok\tChatWorkspace.kt"
        )
        val text = LessonSynthesizer.synthesize(lines)
        assertTrue(text.contains("Patterns that failed recently"))
        assertTrue(text.contains("write broken loop"))
        assertTrue(text.contains("Patterns that succeeded recently"))
        assertTrue(text.contains("[completed] read ChatWorkspace.kt"))
    }

    @Test
    fun includesPromotedEvolution() {
        val evolution = listOf(
            EvolutionVersion(
                id = "abc",
                kind = "self-repair",
                sourcePath = "/tmp/ChatWorkspace.kt",
                checksum = "deadbeef",
                evaluationPassed = true,
                createdAt = 1L
            )
        )
        val text = LessonSynthesizer.synthesize(emptyList(), evolution)
        assertTrue(text.contains("Recently promoted self-changes"))
        assertTrue(text.contains("[self-repair] ChatWorkspace.kt verified"))
        assertFalse(text.contains("Patterns that failed"))
    }
}
