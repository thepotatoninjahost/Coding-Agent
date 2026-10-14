package com.codingagent.core

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import com.codingagent.knowledge.KnowledgeIndex

class KnowledgeIndexTest {
    @Test
    fun replacingDocumentKeepsUnrelatedDocumentsAndRemovesOldChunks() {
        val root = Files.createTempDirectory("knowledge-replace").toFile()
        val index = KnowledgeIndex(root)

        index.indexText("Guide", "first-source", "The zebra guide contains enough searchable text to create a complete index chunk.")
        index.indexText("Other", "other-source", "The orchid reference contains enough searchable text to create a complete index chunk.")
        index.indexText("Guide", "replacement-source", "The giraffe guide contains enough searchable text to create a complete index chunk.")

        assertEquals(2, index.documentCount())
        assertEquals("replacement-source", index.listDocuments().first { it.name == "Guide" }.source)
        assertTrue(index.search("giraffe", 10).any { it.document == "Guide" })
        assertFalse(index.search("zebra", 10).any { it.document == "Guide" })
        assertTrue(index.search("orchid", 10).any { it.document == "Other" })
    }

    @Test
    fun constructorRollsBackBothFilesAfterInterruptedReplacement() {
        val root = Files.createTempDirectory("knowledge-recovery").toFile()
        val index = KnowledgeIndex(root)
        index.indexText("Guide", "source", "The zebra guide contains enough searchable text to create a complete index chunk.")
        index.indexText("Other", "source", "The orchid reference contains enough searchable text to create a complete index chunk.")

        val chunks = File(root, "chunks.jsonl")
        val documents = File(root, "documents.jsonl")
        val previousChunks = File(root, "chunks.jsonl.txn-old")
        val previousDocuments = File(root, "documents.jsonl.txn-old")
        val transaction = File(root, "index.transaction.json")
        previousChunks.writeText(chunks.readText())
        previousDocuments.writeText(documents.readText())
        transaction.writeText(
            JSONObject()
                .put("chunksExisted", true)
                .put("documentsExisted", true)
                .toString()
        )

        chunks.writeText("{ partial replacement")
        documents.writeText("{ partial metadata")

        val recovered = KnowledgeIndex(root)

        assertEquals(2, recovered.documentCount())
        assertTrue(recovered.search("zebra", 10).any { it.document == "Guide" })
        assertTrue(recovered.search("orchid", 10).any { it.document == "Other" })
        assertFalse(recovered.search("giraffe", 10).any())
        assertFalse(transaction.exists())
        assertFalse(previousChunks.exists())
        assertFalse(previousDocuments.exists())
    }

    @Test
    fun interruptedFirstIndexWriteRemovesUncommittedFiles() {
        val root = Files.createTempDirectory("knowledge-first-write-recovery").toFile()
        File(root, "chunks.jsonl").writeText("{ partial chunks")
        File(root, "documents.jsonl").writeText("{ partial metadata")
        File(root, "index.transaction.json").writeText(
            JSONObject()
                .put("chunksExisted", false)
                .put("documentsExisted", false)
                .toString()
        )

        val recovered = KnowledgeIndex(root)

        assertEquals(0, recovered.documentCount())
        assertEquals(0, recovered.chunkCount())
        assertFalse(File(root, "chunks.jsonl").exists())
        assertFalse(File(root, "documents.jsonl").exists())
    }

    @Test
    fun removingDocumentPreservesOtherIndexedDocuments() {
        val root = Files.createTempDirectory("knowledge-remove").toFile()
        val index = KnowledgeIndex(root)
        index.indexText("Guide", "source", "The zebra guide contains enough searchable text to create a complete index chunk.")
        index.indexText("Other", "source", "The orchid reference contains enough searchable text to create a complete index chunk.")

        assertTrue(index.removeDocument("Guide"))

        assertEquals(1, index.documentCount())
        assertFalse(index.search("zebra", 10).any { it.document == "Guide" })
        assertTrue(index.search("orchid", 10).any { it.document == "Other" })
        assertFalse(index.removeDocument("Missing"))
    }
}
