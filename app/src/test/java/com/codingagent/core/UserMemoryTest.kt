package com.codingagent.core

import com.codingagent.agent.UserMemory
import com.codingagent.agent.UserMemoryExtractor
import com.codingagent.agent.UserMemoryIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMemoryTest {
    @Test
    fun explicitPreferenceIsExtractedWithoutHardCodingItsContent() {
        val text = UserMemoryExtractor.extract("Remember that I prefer concise explanations.") 
        assertEquals("I prefer concise explanations", text)
        assertEquals(UserMemory.Kind.WORKFLOW, UserMemoryExtractor.kind(text!!))
    }

    @Test
    fun transientTaskInstructionIsNotPromotedToDurableMemory() {
        assertEquals(
            null,
            UserMemoryExtractor.extract("Never use this file for this task.")
        )
        assertEquals(
            null,
            UserMemoryExtractor.extract("Never create sample files.")
        )
        assertEquals(
            "Never create sample files",
            UserMemoryExtractor.extract("Remember never create sample files.")
        )
        assertEquals(
            null,
            UserMemoryExtractor.extract("Remember my API key is abc123.")
        )
    }

    @Test
    fun memoryIndexConsolidatesRelatedUpdatesAndCapsGrowth() {
        val index = UserMemoryIndex(maxEntries = 2)
        var memories = emptyList<UserMemory>()
        memories = index.remember(memories, "I prefer concise explanations", UserMemory.Kind.WORKFLOW, 60, 1)
        val firstId = memories.single().id
        memories = index.remember(memories, "I prefer concise answers", UserMemory.Kind.WORKFLOW, 80, 2)
        assertEquals(1, memories.size)
        assertEquals(firstId, memories.single().id)
        assertEquals("I prefer concise answers", memories.single().text)
        assertEquals(80, memories.single().importance)

        memories = index.remember(memories, "I use Kotlin for Android work", UserMemory.Kind.FACT, 50, 3)
        memories = index.remember(memories, "I use Python for automation", UserMemory.Kind.FACT, 50, 4)
        assertEquals(2, memories.size)
    }

    @Test
    fun retrievalReturnsOnlyRelevantMemories() {
        val index = UserMemoryIndex()
        var memories = emptyList<UserMemory>()
        memories = index.remember(memories, "I prefer concise explanations", UserMemory.Kind.WORKFLOW, 80, 1)
        memories = index.remember(memories, "I use Kotlin for Android work", UserMemory.Kind.FACT, 60, 2)

        val communication = index.relevant(memories, "How should you explain this?", 4)
        assertTrue(communication.any { it.text.contains("concise", ignoreCase = true) })
        assertFalse(communication.any { it.text.contains("Kotlin", ignoreCase = true) })

        val coding = index.relevant(memories, "Kotlin Android code", 4)
        assertTrue(coding.any { it.text.contains("Kotlin", ignoreCase = true) })
    }

    @Test
    fun forgettingRemovesMatchingMemoryOnly() {
        val index = UserMemoryIndex()
        var memories = emptyList<UserMemory>()
        memories = index.remember(memories, "I prefer concise explanations", UserMemory.Kind.WORKFLOW, 80, 1)
        memories = index.remember(memories, "I use Kotlin for Android work", UserMemory.Kind.FACT, 60, 2)

        val remaining = index.forget(memories, "concise explanations")
        assertEquals(1, remaining.size)
        assertTrue(remaining.single().text.contains("Kotlin"))
    }
}
