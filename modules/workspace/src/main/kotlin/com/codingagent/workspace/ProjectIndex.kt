package com.codingagent.workspace

import com.codingagent.domain.*
import java.io.File
import java.security.MessageDigest

class ProjectIndex {
    private val ignored = setOf(".git", ".gradle", "build", "node_modules", "target", "Trash", ".coding-agent")
    private val extensions = setOf("kt", "java", "kts", "py", "js", "ts", "tsx", "jsx", "json", "xml", "gradle", "md", "yaml", "yml", "toml", "sh")

    fun index(root: File): List<ProjectFile> = root.walkTopDown()
        .onEnter { it.name !in ignored }
        .filter { it.isFile && (it.extension.lowercase() in extensions || it.name == "Makefile") }
        .map { file ->
            val text = file.readText()
            ProjectFile(file.relativeTo(root).invariantSeparatorsPath, file.length(), language(file), imports(text), symbols(text), text.lines().size, checksum(text))
        }.toList()

    fun summarize(root: File): ProjectSummary {
        val files = index(root)
        return ProjectSummary(files, files.groupingBy { it.language }.eachCount(), files.sumOf { it.symbols.size }, files.sumOf { it.imports.size })
    }

    fun search(root: File, query: String): List<SearchHit> = if (query.isBlank()) emptyList() else index(root).flatMap { metadata ->
        File(root, metadata.path).readLines().mapIndexedNotNull { index, line ->
            line.takeIf { it.contains(query, ignoreCase = true) }?.let { SearchHit(metadata.path, index + 1, it.trim()) }
        }
    }

    private fun language(file: File): String = when (file.extension.lowercase()) {
        "kt", "kts" -> "kotlin"; "java" -> "java"; "py" -> "python"; "js", "jsx" -> "javascript"; "ts", "tsx" -> "typescript"; "xml" -> "xml"; "json" -> "json"; "md" -> "markdown"; else -> file.extension.lowercase()
    }

    private fun imports(text: String): List<String> = text.lines().mapNotNull { line ->
        val value = line.trim()
        when { value.startsWith("import ") -> value.removePrefix("import ").trim(); value.startsWith("from ") && " import " in value -> value.substringBefore(" import ").removePrefix("from ").trim(); else -> null }
    }.distinct()

    private fun symbols(text: String): List<String> = text.lines().mapNotNull { line -> Regex("\\b(class|interface|object|fun|function|def|const|val|var|public|private|protected|static)\\s+([A-Za-z_][A-Za-z0-9_]*)").find(line)?.groupValues?.getOrNull(2) }.distinct()

    private fun checksum(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
