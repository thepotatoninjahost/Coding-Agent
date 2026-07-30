package com.codingagent.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class KnowledgeBase(context: Context) : KnowledgeProvider {
    private val root = File(context.filesDir, "coding-agent/knowledge").apply { mkdirs() }
    private val indexFile = File(root, "chunks.jsonl")

    fun importAsset(context: Context, assetPath: String, document: String): Int {
        val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        val chunks = chunk(text, document)
        indexFile.writeText(chunks.joinToString("\n") { chunk ->
            JSONObject().put("document", chunk.document).put("section", chunk.section)
                .put("text", chunk.text).put("terms", JSONArray(chunk.terms.toList())).toString()
        })
        return chunks.size
    }

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

    private fun chunk(text: String, document: String): List<KnowledgeChunk> {
        val pages = text.split('\u000c')
        return pages.flatMapIndexed { pageIndex, page ->
            val cleaned = page.lines().map { it.trim() }.filter { it.isNotBlank() }
            cleaned.chunked(12).mapIndexedNotNull { chunkIndex, lines ->
                val value = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
                if (value.length < 40) null else KnowledgeChunk(document, "page ${pageIndex + 1}, section ${chunkIndex + 1}", value, tokenize(value).toSet())
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
