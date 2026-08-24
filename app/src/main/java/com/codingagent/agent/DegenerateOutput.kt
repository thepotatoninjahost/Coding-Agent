package com.codingagent.agent

/**
 * ONE JOB: Detect and sanitize degenerate model output (repetition / spam).
 */
object DegenerateOutput {
    fun isDegenerate(text: String): Boolean {
        if (text.isBlank()) return false
        val trimmed = text.trim()
        if (trimmed.length >= 80 && trimmed.toSet().size <= 3) return true
        val lines = trimmed.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size >= 8) {
            val counts = lines.groupingBy { it }.eachCount()
            val dominant = counts.values.maxOrNull() ?: 0
            if (dominant >= 6 && dominant * 2 >= lines.size) return true
        }
        return false
    }

    fun sanitize(text: String): String {
        if (!isDegenerate(text)) return text
        return "(Model output looked repetitive or empty; ignored.)"
    }
}
