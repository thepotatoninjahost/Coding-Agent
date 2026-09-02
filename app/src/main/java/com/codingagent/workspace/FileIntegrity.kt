package com.codingagent.workspace

import java.security.MessageDigest

/**
 * ONE JOB: Structural integrity checks on source file content — placeholder detection,
 * brace balance, mid-expression endings, and SHA-256 checksum verification.
 */
object FileIntegrity {

    /**
     * Inspect [content] for structural defects.
     *
     * @param path         relative path used in [VerificationIssue] messages.
     * @param content      full file text to inspect.
     * @param expectedChecksum  when non-null, a SHA-256 mismatch is also reported.
     */
    fun inspect(
        path: String,
        content: String,
        expectedChecksum: String? = null
    ): List<VerificationIssue> {
        val issues = mutableListOf<VerificationIssue>()

        if (expectedChecksum != null && sha256(content) != expectedChecksum) {
            issues += VerificationIssue(path, 0, "integrity: SHA-256 mismatch for $path")
        }

        checkPlaceholder(path, content)?.let { issues += it }
        checkBraceBalance(path, content)?.let { issues += it }
        checkMidExpression(path, content)?.let { issues += it }

        return issues
    }

    /**
     * Inspect a [ChangeRecord]'s after-content for structural defects.
     * Used by [ProjectWorkspace.verifyProposal].
     */
    fun inspectChange(record: ChangeRecord): List<VerificationIssue> {
        val after = record.after ?: return emptyList()
        return inspect(record.path, after, record.afterChecksum)
    }

    /** Returns true when [sha256](content) == [checksum]. */
    fun matchesChecksum(content: String, checksum: String): Boolean =
        sha256(content) == checksum

    /** SHA-256 hex digest of [content] encoded as UTF-8. */
    fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ── private checks ────────────────────────────────────────────────────────

    /**
     * Detect truncation placeholders left by models: comment lines whose content
     * is (case-insensitively) "rest unchanged", "... rest of file unchanged", etc.
     * Requires a comment prefix (// or #) so prose in string literals is ignored.
     */
    private fun checkPlaceholder(path: String, content: String): VerificationIssue? {
        val placeholderPattern = Regex(
            """(?://|#)\s*(\.\.\.)?\s*(rest|remaining)\s+(of\s+(the\s+)?file\s+)?(unchanged|omitted|same|stays|as before)""",
            RegexOption.IGNORE_CASE
        )
        content.lineSequence().forEachIndexed { index, line ->
            if (placeholderPattern.containsMatchIn(line)) {
                return VerificationIssue(
                    path, index + 1,
                    "integrity: truncation placeholder on line ${index + 1} — write the complete file"
                )
            }
        }
        return null
    }

    /**
     * Count `{` and `}` outside string literals and character literals.
     * Reports an unclosed or extra brace only for Kotlin/Java/similar source.
     */
    private fun checkBraceBalance(path: String, content: String): VerificationIssue? {
        if (!looksLikeStructuredSource(path)) return null

        var depth = 0
        var inString = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = false
        var escape = false
        var i = 0

        while (i < content.length) {
            val c = content[i]
            val next = content.getOrNull(i + 1)

            when {
                escape -> escape = false
                inLineComment -> {
                    if (c == '\n') inLineComment = false
                }
                inBlockComment -> {
                    if (c == '*' && next == '/') { inBlockComment = false; i++ }
                }
                inChar -> {
                    when (c) {
                        '\\' -> escape = true
                        '\'' -> inChar = false
                    }
                }
                inString -> {
                    when (c) {
                        '\\' -> escape = true
                        '"' -> inString = false
                    }
                }
                c == '/' && next == '/' -> inLineComment = true
                c == '/' && next == '*' -> { inBlockComment = true; i++ }
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '{' -> depth++
                c == '}' -> depth--
            }
            i++
        }

        return when {
            depth > 0 -> VerificationIssue(path, 0, "integrity: unclosed '{' in $path ($depth unclosed)")
            depth < 0 -> VerificationIssue(path, 0, "integrity: extra '}' in $path (${-depth} extra)")
            else -> null
        }
    }

    /**
     * Detect files that end mid-expression: last non-empty line ending with `=` or `.`
     * is a sign the model truncated the output.
     */
    private fun checkMidExpression(path: String, content: String): VerificationIssue? {
        if (!looksLikeStructuredSource(path)) return null
        val last = content.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            .orEmpty()
        if (last.endsWith("=") || last.endsWith(".")) {
            return VerificationIssue(
                path, 0,
                "integrity: mid-expression ending in $path (last line ends with '${last.last()}')"
            )
        }
        return null
    }

    private fun looksLikeStructuredSource(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".kt") ||
            lower.endsWith(".java") ||
            lower.endsWith(".kts") ||
            lower.endsWith(".groovy")
    }
}
