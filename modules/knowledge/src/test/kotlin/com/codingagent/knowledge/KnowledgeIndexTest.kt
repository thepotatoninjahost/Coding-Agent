package com.codingagent.knowledge

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnowledgeIndexTest {
    @Test
    fun indexesAndSearchesNormalizedText() {
        val root = Files.createTempDirectory("knowledge").toFile()
        val index = KnowledgeIndex(root)
        val result = index.indexText("Kotlin", "manual", "Functions should have one job and clear error handling.")
        assertEquals(1, result.chunkCount)
        val hit = index.search("error handling", 5).single()
        assertTrue(hit.excerpt.contains("error handling"))
    }
}
