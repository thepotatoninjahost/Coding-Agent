package com.codingagent.agent

/**
 * ONE JOB: Catch specialist words in text the owner will read, and rewrite them once.
 */
object PlainEnglish {
    private val banned = listOf(
        "agentic loop",
        "agentic",
        "checksum-backed rollback",
        "checksum-backed",
        "checksum",
        "rollback"
    )

    fun hasJargon(text: String): Boolean {
        val visible = stripCode(text).lowercase()
        if (banned.any { it in visible }) return true
        return Regex("\\b(parsers?|lexers?|tokenizers?|bytecodes?|recursive[- ]descent)\\b").containsMatchIn(visible)
    }

    fun soften(text: String): String {
        val hidden = mutableListOf<String>()
        fun hide(match: MatchResult): String {
            hidden += match.value
            return "\u0001${hidden.lastIndex}\u0001"
        }
        var out = text
        out = Regex("```[\\s\\S]*?```").replace(out, ::hide)
        out = Regex("`[^`\\n]+`").replace(out, ::hide)
        val replacements = listOf(
            Regex("(?i)checksum-backed rollback") to "a saved copy you can go back to",
            Regex("(?i)checksum-backed") to "saved-copy",
            Regex("(?i)agentic loop") to "step-by-step work",
            Regex("(?i)\\bagentic\\b") to "step by step",
            Regex("(?i)\\bchecksums?\\b") to "safety check",
            Regex("(?i)\\brollback\\b") to "going back to a saved copy",
            Regex("(?i)recursive[- ]descent parsers?") to "reader that walks through the text from the top",
            Regex("(?i)\\btokenizers?\\b") to "splitter",
            Regex("(?i)\\blexers?\\b") to "splitter",
            Regex("(?i)\\bparsers?\\b") to "reader",
            Regex("(?i)\\bbytecodes?\\b") to "runnable instructions"
        )
        replacements.forEach { (pattern, replacement) ->
            out = out.replace(pattern, replacement)
        }
        hidden.forEachIndexed { index, original ->
            out = out.replace("\u0001$index\u0001", original)
        }
        return out
    }

    private fun stripCode(text: String): String =
        text.replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("`[^`\\n]+`"), " ")
}
