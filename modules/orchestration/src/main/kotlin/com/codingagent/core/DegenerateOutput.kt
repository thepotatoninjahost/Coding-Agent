package com.codingagent.core

/**
 * Shared detectors for model garbage so the agent loop and chat UI agree.
 */
object DegenerateOutput {
    fun isDegenerate(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size >= 5) {
            val top = lines.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            if (top >= 5 && top * 2 >= lines.size) return true
        }
        val compact = text.replace(Regex("\\s+"), "")
        if (compact.length >= 80) {
            val unique = compact.toSet().size
            // Single-character or tiny alphabet spam (e.g. "aaaa…", "ababab…")
            if (unique <= 3) return true
        }
        // Same short token repeated many times in one line
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size >= 12) {
            val topToken = tokens.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            if (topToken >= 10 && topToken * 2 >= tokens.size) return true
        }
        // import-cycle / package-line enumeration spam (many near-duplicate import lines)
        val importLike = lines.filter { it.startsWith("import ") || it.contains("com.codingagent") }
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
}
