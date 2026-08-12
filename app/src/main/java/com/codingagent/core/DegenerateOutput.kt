package com.codingagent.core

/**
 * ONE JOB: Detect repetitive/garbage model output so the agent and UI agree.
 *
 * Must not false-positive on legitimate tool evidence:
 * - file path listings (list_files / formatListingSummary)
 * - search hits under the project's own package tree
 */
object DegenerateOutput {
    fun isDegenerate(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        // Structured listing summaries are never treated as model spam.
        if (trimmed.startsWith("Project files:")) return false

        val lines = trimmed.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return false

        // Majority path-like lines = real inventory, not token spam.
        if (isMostlyFilePaths(lines)) return false

        if (lines.size >= 5) {
            val top = lines.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            if (top >= 5 && top * 2 >= lines.size) return true
        }

        val compact = trimmed.replace(Regex("\\s+"), "")
        if (compact.length >= 80) {
            val unique = compact.toSet().size
            // Single-character or tiny alphabet spam (e.g. "aaaa…", "ababab…")
            if (unique <= 3) return true
        }

        // Same short token repeated many times
        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size >= 12) {
            val topToken = tokens.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            if (topToken >= 10 && topToken * 2 >= tokens.size) return true
        }

        // True import-cycle spam only (model dumping the same import block).
        // Do NOT use package-name contains checks — those fire on normal path listings.
        val importLike = lines.filter { it.startsWith("import ") }
        if (importLike.size >= 12 && importLike.size * 2 >= lines.size) return true

        return false
    }

    fun sanitize(text: String, fallbackPrefix: String = "Model output was degenerate"): String {
        if (!isDegenerate(text)) return text.take(2_000)
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val top = lines.groupingBy { it }.eachCount().maxByOrNull { it.value }
        return if (top != null && top.value >= 5) {
            "$fallbackPrefix (repeated \"${top.key.take(80)}\" ×${top.value})."
        } else {
            "$fallbackPrefix (low-entropy or token spam, ${text.length} chars)."
        }
    }

    private fun isMostlyFilePaths(lines: List<String>): Boolean {
        if (lines.size < 4) return false
        val pathLike = lines.count { line ->
            val s = line.trim()
            when {
                s.startsWith("Project files:") -> true
                s.startsWith("Verification:") -> true
                s.contains('/') && !s.contains(' ') -> true
                s.contains('\\') && !s.contains(' ') -> true
                s.endsWith(".kt") || s.endsWith(".java") || s.endsWith(".kts") ||
                    s.endsWith(".xml") || s.endsWith(".md") || s.endsWith(".txt") ||
                    s.endsWith(".gradle") || s.endsWith(".properties") -> true
                else -> false
            }
        }
        return pathLike * 2 >= lines.size
    }
}
