package com.codingagent.core

import com.codingagent.agent.UserMemory
import com.codingagent.agent.UserMemoryExtractor
import com.codingagent.agent.UserMemoryIndex
import com.codingagent.model.ModelSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun explicitLongMemoryIsPreservedInFull() {
        val original = "Remember that " + "important owner detail ".repeat(80)
        val expected = original.removePrefix("Remember that ").trim().trimEnd('.', '!', '?')
        val extracted = UserMemoryExtractor.extract(original)
        assertEquals(expected, extracted)
        val index = UserMemoryIndex(maxEntries = 1)
        val saved = index.remember(emptyList(), extracted!!, UserMemory.Kind.WORKFLOW, 80, 1)
        assertEquals(extracted, saved.single().text)
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
            null,
            UserMemoryExtractor.extract("I want you to fix the parser for this task.")
        )
        assertEquals(
            null,
            UserMemoryExtractor.extract("I don't want you to rewrite unrelated files.")
        )
        assertEquals(
            null,
            UserMemoryExtractor.extract("Please always check the current project before editing it.")
        )
        assertEquals(
            null,
            UserMemoryExtractor.extract("Don't ever change the signing configuration in this task.")
        )
        assertEquals(
            "never create sample files",
            UserMemoryExtractor.extract("Remember never create sample files.")
        )
        assertEquals(
            null,
            UserMemoryExtractor.extract("Remember my API key is abc123.")
        )
    }

    @Test
    fun memoryIndexRetainsDistinctMemoriesWithoutDiscardingOlderEntries() {
        val index = UserMemoryIndex(maxEntries = 2)
        var memories = emptyList<UserMemory>()
        memories = index.remember(memories, "I prefer concise explanations", UserMemory.Kind.WORKFLOW, 60, 1)
        val firstId = memories.single().id
        memories = index.remember(memories, "I prefer concise answers", UserMemory.Kind.WORKFLOW, 80, 2)
        assertEquals(2, memories.size)
        assertTrue(memories.any { it.id == firstId && it.text == "I prefer concise explanations" })
        assertTrue(memories.any { it.text == "I prefer concise answers" })

        memories = index.remember(memories, "I use Kotlin for Android work", UserMemory.Kind.FACT, 50, 3)
        memories = index.remember(memories, "My favorite color is blue", UserMemory.Kind.FACT, 50, 4)
        assertEquals(2, memories.size)
        assertTrue(memories.any { it.text == "I prefer concise answers" })
        assertTrue(memories.any { it.text == "I prefer concise explanations" })
    }

    @Test
    fun memoryChoiceIsSavedAndUnknownValuesStayCapped() {
        val forever = ModelSettings(memoryRetention = "Forever")
        val restored = ModelSettings.fromJson(ModelSettings.toJson(forever))
        assertEquals(ModelSettings.MEMORY_KEEP_FOREVER, restored.memoryRetention)
        assertEquals(ModelSettings.MEMORY_KEEP_CAPPED, ModelSettings(memoryRetention = "keep-all").normalized().memoryRetention)
    }

    @Test
    fun foreverRetentionKeepsOlderMemories() {
        val index = UserMemoryIndex(maxEntries = 2)
        var memories = emptyList<UserMemory>()
        memories = index.remember(memories, "I prefer concise explanations", UserMemory.Kind.WORKFLOW, 60, 1, UserMemoryIndex.KEEP_FOREVER)
        memories = index.remember(memories, "I prefer concise answers", UserMemory.Kind.WORKFLOW, 80, 2, UserMemoryIndex.KEEP_FOREVER)
        memories = index.remember(memories, "I use Kotlin for Android work", UserMemory.Kind.FACT, 50, 3, UserMemoryIndex.KEEP_FOREVER)
        memories = index.remember(memories, "My favorite color is blue", UserMemory.Kind.FACT, 50, 4, UserMemoryIndex.KEEP_FOREVER)
        assertEquals(4, memories.size)
    }

    @Test
    fun oldLowImportanceMemoryIsDroppedWhenCapped() {
        val index = UserMemoryIndex(maxEntries = 64)
        val now = 1_000_000_000_000L
        val old = now - 181L * 24L * 60L * 60L * 1000L
        var memories = index.remember(emptyList(), "I like short notes", UserMemory.Kind.FACT, 50, old)
        memories = index.remember(memories, "I prefer dark screens", UserMemory.Kind.PREFERENCE, 50, now)
        assertEquals(1, memories.size)
        assertEquals("I prefer dark screens", memories.single().text)
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

    @Test
    fun corruptMemoryJsonFailsInsteadOfBecomingAnEmptyList() {
        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            UserMemoryJsonCodec.decode("[{not valid json]")
        }
        assertTrue(error.message!!.contains("refusing to overwrite"))
    }

    @Test
    fun partiallyCorruptMemoryListFailsInsteadOfReturningValidPrefix() {
        val raw = """[
            {"id":"saved","text":"I prefer concise answers","kind":"WORKFLOW","importance":80,"createdAt":1,"updatedAt":2},
            {"id":"broken","text":"missing required fields"}
        ]"""
        assertThrows(IllegalStateException::class.java) {
            UserMemoryJsonCodec.decode(raw)
        }
    }

    @Test
    fun validMemoryJsonStillLoadsEveryEntry() {
        val raw = """[{"id":"saved","text":"I prefer concise answers","kind":"WORKFLOW","importance":80,"createdAt":1,"updatedAt":2}]"""
        val memories = UserMemoryJsonCodec.decode(raw)
        assertEquals(1, memories.size)
        assertEquals("saved", memories.single().id)
        assertEquals("I prefer concise answers", memories.single().text)
    }
}
