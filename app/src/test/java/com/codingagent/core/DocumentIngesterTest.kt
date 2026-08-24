package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.knowledge.DocumentIngester
import com.codingagent.knowledge.KnowledgeIndex

class DocumentIngesterTest {
    @Test
    fun acceptsMarkdownAndSourceExtensions() {
        assertTrue(DocumentIngester.isSupportedFileName("notes.md"))
        assertTrue(DocumentIngester.isSupportedFileName("Main.kt"))
        assertTrue(DocumentIngester.isSupportedFileName("README"))
        assertFalse(DocumentIngester.isSupportedFileName("photo.png"))
        assertFalse(DocumentIngester.isSupportedFileName("model.bin"))
    }

    @Test
    fun normalizeRejectsTinyOrBlankText() {
        runCatching { DocumentIngester.normalize("x", "s", "   ") }.exceptionOrNull().also {
            assertTrue(it is IllegalArgumentException)
        }
        runCatching { DocumentIngester.normalize("x", "s", "short") }.exceptionOrNull().also {
            assertTrue(it is IllegalArgumentException)
        }
    }

    @Test
    fun extractFromFileIndexesReadableText() {
        val dir = Files.createTempDirectory("ingest").toFile()
        val file = dir.resolve("guide.md")
        file.writeText("# Kotlin coroutines\n\n" + ("Use suspend functions for async work. ".repeat(8)))
        val request = DocumentIngester.extractFromFile(file)
        assertEquals("guide.md", request.documentName)
        assertTrue(request.text.contains("coroutines"))
    }

    @Test
    fun knowledgeIndexRoundTripSearch() {
        val root = Files.createTempDirectory("knowledge-index").toFile()
        val index = KnowledgeIndex(root)
        val body = ("Jetpack Compose builds custom user interfaces for Android apps. ".repeat(6))
        val result = index.indexText("compose-notes.md", "user-import", body)
        assertTrue(result.chunkCount >= 1)
        val hits = index.search("compose user interface", limit = 5)
        assertTrue(hits.isNotEmpty())
        assertEquals("compose-notes.md", hits.first().document)
        assertEquals(1, index.listDocuments().size)
        index.indexText("compose-notes.md", "user-import", body + " Extra material about Canvas drawing.")
        assertEquals(1, index.listDocuments().size)
        assertTrue(index.search("Canvas drawing", 3).isNotEmpty())
    }

    @Test
    fun emptyAndBinaryRejected() {
        val dir = Files.createTempDirectory("ingest-bad").toFile()
        val empty = dir.resolve("empty.txt").apply { writeText("") }
        assertTrue(runCatching { DocumentIngester.extractFromFile(empty) }.isFailure)
        val binary = dir.resolve("blob.bin").apply { writeBytes(ByteArray(64) { 0 }) }
        assertTrue(runCatching { DocumentIngester.extractFromFile(binary) }.isFailure)
    }
}
