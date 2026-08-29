package com.codingagent.agent

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

        // Structured listing / local-evidence summaries are never treated as model spam.
        if (trimmed.startsWith("Project files:") ||
            trimmed.startsWith("Source files:") ||
            trimmed.startsWith("Indexed source files") ||
            trimmed.startsWith("Directory listing:") ||
            trimmed.startsWith("File:") ||
            trimmed.startsWith("Inspect:") ||
            trimmed.startsWith("Scope note:") ||
            trimmed.startsWith("Policy scan") ||
            trimmed.startsWith("Hello. Coding Agent is ready")
        ) return false

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
            if (unique <= 3) return true
        }

        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size >= 12) {
            val topToken = tokens.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            if (topToken >= 10 && topToken * 2 >= tokens.size) return true
        }

        val importLike = lines.filter { it.startsWith("import ") }
        if (importLike.size >= 12 && importLike.size * 2 >= lines.size) return true

        // The checks above only catch REPETITIVE spam. A model can also produce non-repeating
        // garbage — corrupted encoding, or "token soup" that isn't a real answer in any
        // language — that would otherwise sail through and get reported as a clean "completed"
        // task. Catch those two shapes without touching the repetition logic above.
        if (hasCorruptedEncoding(trimmed)) return true
        if (looksLikeTokenSoup(tokens)) return true

        return false
    }

    /** Replacement characters / control bytes indicate a mangled response, not prose. */
    private fun hasCorruptedEncoding(text: String): Boolean {
        if (text.length < 20) return false
        var bad = 0
        for (c in text) {
            if (c == '\uFFFD') bad++
            else if (c.code < 32 && c != '\n' && c != '\t' && c != '\r') bad++
        }
        return bad.toDouble() / text.length > 0.02
    }

    /**
     * Non-repeating gibberish: a long run of tokens that mostly don't look like real words,
     * identifiers, numbers, or paths. Deliberately conservative (long tokens only, majority
     * vote, requires a reasonable sample size) so normal prose or code is never misflagged —
     * this only fires on the kind of run-on nonsense that has no vowels, no digits, no path
     * separators, and no punctuation structure at all.
     */
    private fun looksLikeTokenSoup(tokens: List<String>): Boolean {
        if (tokens.size < 12) return false
        val judged = tokens.filter { it.trim { c -> !c.isLetterOrDigit() }.length > 5 }
        if (judged.size < 10) return false
        fun isPlausible(token: String): Boolean {
            val core = token.trim { c -> !c.isLetterOrDigit() }
            if (core.any { it.isDigit() }) return true
            if (token.contains('/') || token.contains('\\') || token.contains('.') ||
                token.contains('_') || token.contains('-')
            ) return true
            return core.any { it.lowercaseChar() in "aeiou" }
        }
        val implausible = judged.count { !isPlausible(it) }
        return implausible * 2 >= judged.size
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
                s.startsWith("Source files:") -> true
                s.startsWith("Indexed source files") -> true
                s.startsWith("Directory listing:") -> true
                s.startsWith("File:") -> true
                s.startsWith("Inspect:") -> true
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
