package com.codingagent.workspace

import java.io.File
import java.security.MessageDigest
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.BufferedReader
import java.security.DigestInputStream
import java.nio.charset.StandardCharsets

/**
 * ONE JOB: Project tree → indexed file records with symbols and checksums.
 */
private val SYMBOL_PATTERN = Regex("\\b(class|interface|object|fun|function|def|const|val|var|public|private|protected|static)\\s+([A-Za-z_][A-Za-z0-9_]*)")

class ProjectIndexer {
    private val ignored = setOf(
        ".git", ".gradle", "build", "node_modules", "target", "Trash",
        ".coding-agent", ".idea", "captures"
    )
    private val extensions = setOf("kt", "java", "kts", "py", "js", "ts", "tsx", "jsx", "json", "xml", "gradle", "md", "yaml", "yml", "toml", "sh")

    fun index(root: File): List<ProjectFile> = root.walkTopDown()
        .onEnter { it.name !in ignored && isSafeProjectPath(root, it) }
        .filter {
            it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) &&
                isSafeProjectPath(root, it) &&
                (it.extension.lowercase() in extensions || it.name == "Makefile")
        }
        .map { file ->
            val metadata = analyze(file)
            ProjectFile(
                path = ProjectPaths.relative(root, file),
                bytes = file.length(),
                language = language(file),
                imports = metadata.imports,
                symbols = metadata.symbols,
                lineCount = metadata.lineCount,
                checksum = metadata.checksum
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
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()

        // The model-facing contract describes this as regex-like search. Compile once per
        // request and fall back to literal matching for malformed expressions.
        val matcher = runCatching { Regex(normalized, RegexOption.IGNORE_CASE) }.getOrNull()
        // Search is a line-oriented operation; do not build the full metadata index first.
        // Indexing reads every source file to calculate imports, symbols, line counts, and hashes,
        // which doubled I/O and memory work for every search request.
        return root.walkTopDown()
            .onEnter { it.name !in ignored && isSafeProjectPath(root, it) }
            .filter {
                it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) &&
                    isSafeProjectPath(root, it) &&
                    (it.extension.lowercase() in extensions || it.name == "Makefile")
            }
            .flatMap { file ->
                val path = ProjectPaths.relative(root, file)
                file.useLines { lines ->
                    lines.mapIndexedNotNull { index, text ->
                        val matches = matcher?.containsMatchIn(text) ?: text.contains(normalized, ignoreCase = true)
                        if (matches) SearchHit(path, index + 1, text.trim()) else null
                    }.toList()
                }
            }.toList()
    }
    private fun isSafeProjectPath(root: File, candidate: File): Boolean = runCatching {
        val canonicalRoot = root.canonicalFile.toPath()
        val canonicalCandidate = candidate.canonicalFile.toPath()
        canonicalCandidate.startsWith(canonicalRoot)
    }.getOrDefault(false)

    private data class FileMetadata(
        val imports: List<String>,
        val symbols: List<String>,
        val lineCount: Int,
        val checksum: String
    )

    private fun analyze(file: File): FileMetadata {
        val imports = linkedSetOf<String>()
        val symbols = linkedSetOf<String>()
        var lineCount = 0
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(FileInputStream(file), digest).use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    lineCount++
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("import ") -> imports += trimmed.removePrefix("import ").trim()
                        trimmed.startsWith("from ") && " import " in trimmed ->
                            imports += trimmed.substringBefore(" import ").removePrefix("from ").trim()
                    }
                    SYMBOL_PATTERN.find(line)?.groupValues?.getOrNull(2)?.let(symbols::add)
                }
            }
        }
        val checksum = digest.digest().joinToString("") { "%02x".format(it) }
        return FileMetadata(imports.toList(), symbols.toList(), lineCount, checksum)
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

}
