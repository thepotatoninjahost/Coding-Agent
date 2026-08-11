package com.codingagent.core

import java.io.File
import java.security.MessageDigest

/**
 * ONE JOB: Project tree → indexed file records with symbols and checksums.
 */
class ProjectIndexer {
    private val ignored = setOf(".git", ".gradle", "build", "node_modules", "target", "Trash")
    private val extensions = setOf("kt", "java", "kts", "py", "js", "ts", "tsx", "jsx", "json", "xml", "gradle", "md", "yaml", "yml", "toml", "sh")

    fun index(root: File): List<ProjectFile> = root.walkTopDown()
        .onEnter { it.name !in ignored }
        .filter { it.isFile && (it.extension.lowercase() in extensions || it.name == "Makefile") }
        .map { file ->
            val text = file.readText()
            ProjectFile(
                path = file.relativeTo(root).invariantSeparatorsPath,
                bytes = file.length(),
                language = language(file),
                imports = imports(text),
                symbols = symbols(text),
                lineCount = text.lines().size,
                checksum = sha256(text)
            )
        }.toList()

    fun summarize(root: File): ProjectSummary {
        val files = index(root)
        return ProjectSummary(
            files = files,
            languages = files.groupingBy { it.language }.eachCount(),
            symbols = files.sumOf { it.symbols.size },
            imports = files.sumOf { it.imports.size }
        )
    }

    fun search(root: File, query: String): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        return index(root).flatMap { metadata ->
            val file = File(root, metadata.path)
            file.readLines().mapIndexedNotNull { index, text ->
                if (text.contains(query, ignoreCase = true)) SearchHit(metadata.path, index + 1, text.trim()) else null
            }
        }
    }

    private fun language(file: File): String = when (file.extension.lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py" -> "python"
        "js", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "xml" -> "xml"
        "json" -> "json"
        "md" -> "markdown"
        else -> file.extension.lowercase()
    }

    private fun imports(text: String): List<String> = text.lines()
        .mapNotNull { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("import ") -> trimmed.removePrefix("import ").trim()
                trimmed.startsWith("from ") && " import " in trimmed -> trimmed.substringBefore(" import ").removePrefix("from ").trim()
                else -> null
            }
        }.distinct()

    private fun symbols(text: String): List<String> = text.lines().mapNotNull { line ->
        Regex("\\b(class|interface|object|fun|function|def|const|val|var|public|private|protected|static)\\s+([A-Za-z_][A-Za-z0-9_]*)").find(line)?.groupValues?.getOrNull(2)
    }.distinct()

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
