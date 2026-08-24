package com.codingagent.knowledge

import java.io.File
import java.util.Locale

/**
 * ONE JOB: Ingest free text into the local offline knowledge base.
 */
data class IngestRequest(
    val documentName: String,
    val source: String,
    val text: String
)

data class IngestResult(
    val documentName: String,
    val source: String,
    val chunkCount: Int,
    val characterCount: Int
)

data class IndexedDocument(
    val name: String,
    val source: String,
    val chunkCount: Int,
    val importedAt: Long
)

/**
 * Offline document ingestion: normalize plain-text-ish files into knowledge chunks.
 * Does not touch the network; PDF/binary formats are rejected with a clear error.
 */
object DocumentIngester {
    private val textExtensions = setOf(
        "txt", "md", "markdown", "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx",
        "json", "xml", "yml", "yaml", "toml", "gradle", "properties", "csv", "tsv",
        "log", "sh", "bash", "c", "h", "cpp", "hpp", "rs", "go", "rb", "swift",
        "html", "htm", "css", "scss", "sql", "r", "m", "mm"
    )

    fun isSupportedFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        if (lower == "readme" || lower.startsWith("readme.")) return true
        val ext = lower.substringAfterLast('.', missingDelimiterValue = "")
        return ext in textExtensions
    }

    fun extractFromFile(file: File, documentName: String? = null): IngestRequest {
        require(file.isFile) { "Not a file: ${file.path}" }
        require(file.length() > 0L) { "File is empty: ${file.name}" }
        require(file.length() <= 8L * 1024L * 1024L) { "File exceeds 8 MB limit: ${file.name}" }
        val name = documentName?.takeIf { it.isNotBlank() } ?: file.name
        require(isSupportedFileName(name) || looksLikeText(file)) {
            "Unsupported file type for offline knowledge ingest: ${file.name}. Use plain text, Markdown, or source files."
        }
        val bytes = file.readBytes()
        require(!looksBinary(bytes)) { "File appears binary and cannot be indexed as text: ${file.name}" }
        val text = String(bytes, Charsets.UTF_8)
        return normalize(name, file.absolutePath, text)
    }

    fun normalize(documentName: String, source: String, rawText: String): IngestRequest {
        val cleaned = rawText
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\u0000", "")
            .trim()
        require(cleaned.isNotBlank()) { "Document has no readable text after normalization" }
        require(cleaned.length >= 40) { "Document is too short to index (need at least 40 characters)" }
        val name = documentName.trim().ifBlank { "untitled" }
        return IngestRequest(documentName = name, source = source.ifBlank { name }, text = cleaned)
    }

    private fun looksLikeText(file: File): Boolean {
        val sample = file.inputStream().use { input ->
            val buf = ByteArray(512)
            val n = input.read(buf)
            if (n <= 0) return false
            buf.copyOf(n)
        }
        return !looksBinary(sample)
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        var control = 0
        for (b in bytes.take(2048)) {
            val c = b.toInt() and 0xff
            if (c == 0) return true
            if (c < 9 || (c in 14..31)) control++
        }
        return control > bytes.take(2048).size / 8
    }
}
