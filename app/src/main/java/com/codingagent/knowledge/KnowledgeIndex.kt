package com.codingagent.knowledge

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import com.codingagent.workspace.KnowledgeChunk
import com.codingagent.workspace.KnowledgeHit

/**
 * ONE JOB: Index knowledge documents for fast local search.
 * File-backed, unit-testable; chunks append, documents tracked in documents.jsonl.
 */
class KnowledgeIndex(private val root: File) : KnowledgeProvider {
    private val indexFile = File(root, "chunks.jsonl")
    private val documentsFile = File(root, "documents.jsonl")

    init {
        root.mkdirs()
    }

    fun indexText(document: String, source: String, text: String, importedAt: Long = System.currentTimeMillis()): IngestResult {
        val request = DocumentIngester.normalize(document, source, text)
        val chunks = chunk(request.text, request.documentName)
        require(chunks.isNotEmpty()) { "No indexable chunks produced from ${request.documentName}" }
        synchronized(this) {
            removeDocumentLocked(request.documentName)
            appendChunks(chunks, request.source, importedAt)
            appendDocumentMeta(request.documentName, request.source, chunks.size, importedAt)
        }
        return IngestResult(request.documentName, request.source, chunks.size, request.text.length)
    }

    fun indexRequest(request: IngestRequest, importedAt: Long = System.currentTimeMillis()): IngestResult =
        indexText(request.documentName, request.source, request.text, importedAt)

    override fun search(query: String, limit: Int): List<KnowledgeHit> {
        val terms = tokenize(query)
        if (terms.isEmpty() || !indexFile.exists()) return emptyList()
        return indexFile.useLines { lines ->
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
                    KnowledgeHit(item.optString("document"), item.optString("section"), score, excerpt(text, terms))
                }
                .filter { it.score > 0 }
                .sortedByDescending { it.score }
                .take(limit)
                .toList()
        }
    }

    fun listDocuments(): List<IndexedDocument> {
        if (!documentsFile.exists()) return emptyList()
        return documentsFile.useLines { lines ->
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

    fun chunkCount(): Int {
        if (!indexFile.exists()) return 0
        return indexFile.useLines { it.count() }
    }

    fun removeDocument(document: String): Boolean = synchronized(this) { removeDocumentLocked(document) }

    private fun removeDocumentLocked(document: String): Boolean {
        if (!indexFile.exists() && !documentsFile.exists()) return false
        var removed = false
        if (indexFile.exists()) {
            val kept = indexFile.readLines().filter { line ->
                val doc = runCatching { JSONObject(line).optString("document") }.getOrNull()
                val keep = doc != document
                if (!keep) removed = true
                keep
            }
            indexFile.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
        }
        if (documentsFile.exists()) {
            val keptDocs = documentsFile.readLines().filter { line ->
                runCatching { JSONObject(line).optString("name") }.getOrNull() != document
            }
            documentsFile.writeText(if (keptDocs.isEmpty()) "" else keptDocs.joinToString("\n", postfix = "\n"))
        }
        return removed
    }

    private fun appendChunks(chunks: List<KnowledgeChunk>, source: String, importedAt: Long) {
        indexFile.parentFile?.mkdirs()
        indexFile.appendText(chunks.joinToString("\n") { chunk ->
            JSONObject()
                .put("document", chunk.document)
                .put("section", chunk.section)
                .put("text", chunk.text)
                .put("terms", JSONArray(chunk.terms.toList()))
                .put("source", source)
                .put("importedAt", importedAt)
                .toString()
        } + "\n")
    }

    private fun appendDocumentMeta(name: String, source: String, chunkCount: Int, importedAt: Long) {
        documentsFile.parentFile?.mkdirs()
        documentsFile.appendText(
            JSONObject()
                .put("name", name)
                .put("source", source)
                .put("chunkCount", chunkCount)
                .put("importedAt", importedAt)
                .toString() + "\n"
        )
    }

    internal fun chunk(text: String, document: String): List<KnowledgeChunk> {
        val pages = text.split('\u000c')
        return pages.flatMapIndexed { pageIndex, page ->
            val cleaned = page.lines().map { it.trim() }.filter { it.isNotBlank() }
            cleaned.chunked(12).mapIndexedNotNull { chunkIndex, lines ->
                val value = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
                if (value.length < 40) null
                else KnowledgeChunk(
                    document,
                    "page ${pageIndex + 1}, section ${chunkIndex + 1}",
                    value,
                    tokenize(value).toSet()
                )
            }
        }
    }

    private fun excerpt(text: String, terms: List<String>): String {
        val lower = text.lowercase(Locale.US)
        val position = terms.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (position - 120).coerceAtLeast(0)
        return text.substring(start, (start + 360).coerceAtMost(text.length))
    }

    private fun tokenize(value: String): List<String> = value.lowercase(Locale.US)
        .split(Regex("[^a-z0-9_+#.-]+"))
        .filter { it.length >= 2 }
}
