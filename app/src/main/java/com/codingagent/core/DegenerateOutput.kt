package com.codingagent.core

/**
 * ONE JOB: Detect repetitive/garbage model output so the agent and UI agree.
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
            if (unique <= 6) return true
        }
        val tokens = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (tokens.size >= 12) {
            val top = tokens.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            if (top >= 8 && top * 2 >= tokens.size) return true
        }
        return false
    }
}
