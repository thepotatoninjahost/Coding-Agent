package com.codingagent.knowledge

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import com.codingagent.workspace.KnowledgeChunk
import com.codingagent.workspace.KnowledgeHit
import com.codingagent.workspace.AtomicFileWriter

/**
 * ONE JOB: Index knowledge documents for fast local search.
 * File-backed, unit-testable; chunks append, documents tracked in documents.jsonl.
 *
 * Updating the chunks and document metadata is a recoverable two-file transaction.
 * The previous files are retained until both replacement files have been committed.
 */
class KnowledgeIndex(private val root: File) : KnowledgeProvider {
    private val indexFile = File(root, "chunks.jsonl")
    private val documentsFile = File(root, "documents.jsonl")
    private val transactionFile = File(root, "index.transaction.json")
    private val previousChunksFile = File(root, "chunks.jsonl.txn-old")
    private val previousDocumentsFile = File(root, "documents.jsonl.txn-old")

    init {
        root.mkdirs()
        synchronized(this) { recoverTransactionLocked() }
    }

    fun indexText(document: String, source: String, text: String, importedAt: Long = System.currentTimeMillis()): IngestResult {
        val request = DocumentIngester.normalize(document, source, text)
        val chunks = chunk(request.text, request.documentName)
        require(chunks.isNotEmpty()) { "No indexable chunks produced from " + request.documentName }
        synchronized(this) {
            recoverTransactionLocked()
            val existingChunks = readLines(indexFile).filterNot { line ->
                runCatching { JSONObject(line).optString("document") == request.documentName }.getOrDefault(false)
            }
            val existingDocuments = readLines(documentsFile).filterNot { line ->
                runCatching { JSONObject(line).optString("name") == request.documentName }.getOrDefault(false)
            }
            val replacementChunks = chunks.map { chunk ->
                JSONObject()
                    .put("document", chunk.document)
                    .put("section", chunk.section)
                    .put("text", chunk.text)
                    .put("terms", JSONArray(chunk.terms.toList()))
                    .put("source", request.source)
                    .put("importedAt", importedAt)
                    .toString()
            }
            val replacementDocument = JSONObject()
                .put("name", request.documentName)
                .put("source", request.source)
                .put("chunkCount", chunks.size)
                .put("importedAt", importedAt)
                .toString()
            writeStateLocked(
                serializeLines(existingChunks + replacementChunks),
                serializeLines(existingDocuments + replacementDocument)
            )
        }
        return IngestResult(request.documentName, request.source, chunks.size, request.text.length)
    }

    fun indexRequest(request: IngestRequest, importedAt: Long = System.currentTimeMillis()): IngestResult =
        indexText(request.documentName, request.source, request.text, importedAt)

    override fun search(query: String, limit: Int): List<KnowledgeHit> = synchronized(this) {
        recoverTransactionLocked()
        val terms = tokenize(query)
        if (terms.isEmpty() || !indexFile.exists()) return@synchronized emptyList()
        indexFile.useLines { lines ->
            lines.mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                .map { item ->
                    val text = item.optString("text")
                    val haystack = tokenize(text)
                    val score = terms.fold(0) { total, term ->
                        when {
                            haystack.contains(term) -> 3
                            haystack.any { it.startsWith(term) } -> 1
                            else -> 0
                        }
                    }
                    KnowledgeHit(item.optString("document"), item.optString("section"), score, text)
                }
                .filter { it.score > 0 }
                .sortedByDescending { it.score }
                .take(limit)
                .toList()
        }
    }

    fun listDocuments(): List<IndexedDocument> = synchronized(this) {
        recoverTransactionLocked()
        if (!documentsFile.exists()) return@synchronized emptyList()
        documentsFile.useLines { lines ->
            lines.mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                .map {
                    IndexedDocument(
                        name = it.optString("name"),
                        source = it.optString("source"),
                        chunkCount = it.optInt("chunkCount"),
                        importedAt = it.optLong("importedAt")
                    )
                }
                .filter { it.name.isNotBlank() }
                .toList()
                .asReversed()
        }
    }

    fun documentCount(): Int = listDocuments().size

    fun chunkCount(): Int = synchronized(this) {
        recoverTransactionLocked()
        if (!indexFile.exists()) 0 else indexFile.useLines { it.count() }
    }

    fun removeDocument(document: String): Boolean = synchronized(this) { removeDocumentLocked(document) }

    private fun removeDocumentLocked(document: String): Boolean {
        recoverTransactionLocked()
        if (!indexFile.exists() && !documentsFile.exists()) return false

        var removed = false
        val keptChunks = readLines(indexFile).filter { line ->
            val doc = runCatching { JSONObject(line).optString("document") }.getOrNull()
            val keep = doc != document
            if (!keep) removed = true
            keep
        }
        val keptDocuments = readLines(documentsFile).filter { line ->
            runCatching { JSONObject(line).optString("name") }.getOrNull() != document
        }
        writeStateLocked(serializeLines(keptChunks), serializeLines(keptDocuments))
        return removed
    }

    /**
     * Journal the old state before changing either file. If the process stops after
     * either replacement, the next access restores both old files before returning data.
     */
    private fun writeStateLocked(chunks: String, documents: String) {
        val oldChunks = readExistingFile(indexFile)
        val oldDocuments = readExistingFile(documentsFile)

        if (oldChunks != null) AtomicFileWriter.write(previousChunksFile, oldChunks)
        else AtomicFileWriter.delete(previousChunksFile)
        if (oldDocuments != null) AtomicFileWriter.write(previousDocumentsFile, oldDocuments)
        else AtomicFileWriter.delete(previousDocumentsFile)

        val journal = JSONObject()
            .put("chunksExisted", oldChunks != null)
            .put("documentsExisted", oldDocuments != null)
        AtomicFileWriter.write(transactionFile, journal.toString())

        try {
            AtomicFileWriter.write(indexFile, chunks)
            AtomicFileWriter.write(documentsFile, documents)
            AtomicFileWriter.delete(transactionFile)
            clearTransactionBackups()
        } catch (error: Exception) {
            try {
                recoverTransactionLocked()
            } catch (recoveryError: Exception) {
                error.addSuppressed(recoveryError)
            }
            throw error
        }
    }

    private fun recoverTransactionLocked() {
        val journalText = AtomicFileWriter.readTextIfExists(transactionFile)
        if (journalText == null) {
            clearTransactionBackups()
            return
        }

        val journal = JSONObject(journalText)
        val chunksExisted = journal.getBoolean("chunksExisted")
        val documentsExisted = journal.getBoolean("documentsExisted")
        restorePreviousFile(indexFile, previousChunksFile, chunksExisted)
        restorePreviousFile(documentsFile, previousDocumentsFile, documentsExisted)
        AtomicFileWriter.delete(transactionFile)
        clearTransactionBackups()
    }

    private fun restorePreviousFile(target: File, backup: File, existed: Boolean) {
        if (!existed) {
            AtomicFileWriter.delete(target)
            return
        }
        val previous = AtomicFileWriter.readTextIfExists(backup)
            ?: throw IOException("Knowledge index recovery is missing its previous file: " + backup.name)
        AtomicFileWriter.write(target, previous)
    }

    private fun clearTransactionBackups() {
        AtomicFileWriter.delete(previousChunksFile)
        AtomicFileWriter.delete(previousDocumentsFile)
    }

    private fun readExistingFile(file: File): String? {
        if (!file.exists()) return null
        if (!file.isFile) throw IOException("Knowledge index path is not a regular file: " + file.name)
        return AtomicFileWriter.readTextIfExists(file)
    }

    private fun readLines(file: File): List<String> {
        val text = AtomicFileWriter.readTextIfExists(file) ?: return emptyList()
        if (text.isEmpty()) return emptyList()
        return text.split('\n').let { lines ->
            if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines
        }
    }

    private fun serializeLines(lines: List<String>): String =
        if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")

    internal fun chunk(text: String, document: String): List<KnowledgeChunk> {
        val pages = text.split('\u000c')
        return pages.flatMapIndexed { pageIndex, page ->
            val cleaned = page.lines().map { it.trim() }.filter { it.isNotBlank() }
            cleaned.chunked(12).mapIndexedNotNull { chunkIndex, lines ->
                val value = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
                KnowledgeChunk(
                    document,
                    "page " + (pageIndex + 1) + ", section " + (chunkIndex + 1),
                    value,
                    tokenize(value).toSet()
                )
            }
        }
    }

    private fun tokenize(value: String): List<String> = value.lowercase(Locale.US)
        .split(Regex("[^a-z0-9_+#.-]+"))
        .filter { it.length >= 2 }
}
