package com.codingagent.workspace

import java.security.MessageDigest

/**
 * ONE JOB: Detect incomplete, truncated, or checksum-mismatched source.
 * A pass means the bytes were inspected. It is not a compiler.
 */
object FileIntegrity {
    private val SOURCE_EXTENSIONS = setOf(
        "kt", "kts", "java", "js", "ts", "tsx", "jsx", "py", "gradle"
    )

    private val PLACEHOLDER = Regex(
        """(?i)^\s*(?://|#|/\*|\*|<!--)?\s*(?:\.\.\.|…)?\s*(?:""" +
            "rest unchanged|existing code(?: here)?|code unchanged|""" +
            "remainder (?:of (?:the )?file )?omitted|rest of (?:the )?file|""" +
            "unchanged below|insert(?: the)? rest|snip(?:ped)?)\b"""
    )

    fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun sha256Bytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun matchesChecksum(content: String, expected: String): Boolean {
        if (expected.isBlank() || expected == "<missing>") return false
        return sha256(content) == expected
    }

    fun isSourcePath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in SOURCE_EXTENSIONS
    }

    /**
     * Inspect one file's content. Empty list = no integrity defects found.
     * Does not scan TODO/FIXME (that is [ProjectWorkspace.verify]).
     */
    fun inspect(path: String, content: String, expectedChecksum: String? = null): List<VerificationIssue> {
        val issues = mutableListOf<VerificationIssue>()
        if (content.indexOf('\u0000') >= 0) {
            issues += VerificationIssue(path, 0, "integrity: NUL byte in file")
        }
        if (expectedChecksum != null && expectedChecksum != "<missing>") {
            val actual = sha256(content)
            if (actual != expectedChecksum) {
                issues += VerificationIssue(
                    path,
                    0,
                    "integrity: SHA-256 mismatch expected=${expectedChecksum.take(12)}… actual=${actual.take(12)}…"
                )
            }
        }
        content.lineSequence().forEachIndexed { index, line ->
            if (PLACEHOLDER.containsMatchIn(line)) {
                issues += VerificationIssue(
                    path,
                    index + 1,
                    "integrity: truncation placeholder (incomplete file)"
                )
            }
        }
        if (isSourcePath(path)) {
            issues += structureIssues(path, content)
        }
        return issues
    }

    fun inspectChange(record: ChangeRecord): List<VerificationIssue> {
        val after = record.after ?: return emptyList()
        val issues = inspect(record.path, after, record.afterChecksum).toMutableList()
        if (record.operation == ChangeOperation.CREATE || record.operation == ChangeOperation.REPLACE) {
            if (isSourcePath(record.path) && after.isBlank()) {
                issues += VerificationIssue(record.path, 0, "integrity: staged source file is empty")
            }
        }
        return issues
    }

    private fun structureIssues(path: String, content: String): List<VerificationIssue> {
        val issues = mutableListOf<VerificationIssue>()
        val braces = balance(content, '{', '}')
        val parens = balance(content, '(', ')')
        val brackets = balance(content, '[', ']')
        if (braces != 0) {
            issues += VerificationIssue(
                path,
                lastNonEmptyLine(content),
                if (braces > 0) "integrity: unclosed '{' (count=$braces)"
                else "integrity: extra '}' (count=${-braces})"
            )
        }
        if (parens != 0) {
            issues += VerificationIssue(
                path,
                lastNonEmptyLine(content),
                if (parens > 0) "integrity: unclosed '(' (count=$parens)"
                else "integrity: extra ')' (count=${-parens})"
            )
        }
        if (brackets != 0) {
            issues += VerificationIssue(
                path,
                lastNonEmptyLine(content),
                if (brackets > 0) "integrity: unclosed '[' (count=$brackets)"
                else "integrity: extra ']' (count=${-brackets})"
            )
        }
        val last = content.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
        if (last.endsWith("=") || last.endsWith(".") || last.endsWith(",")) {
            issues += VerificationIssue(
                path,
                lastNonEmptyLine(content),
                "integrity: file ends mid-expression ('${last.last()}')"
            )
        }
        if (unclosedString(content)) {
            issues += VerificationIssue(path, lastNonEmptyLine(content), "integrity: unclosed string")
        }
        return issues
    }

    private fun lastNonEmptyLine(content: String): Int {
        var n = 0
        var last = 1
        content.lineSequence().forEach { line ->
            n++
            if (line.isNotBlank()) last = n
        }
        return last
    }

    /**
     * Brace/paren/bracket balance that skips strings, chars, and comments.
     * Not a parser. False negatives possible; false "pass" is the failure mode we refuse.
     */
    private fun balance(content: String, open: Char, close: Char): Int {
        var n = 0
        var i = 0
        var inTripleString = false
        var inString = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = false
        var escape = false
        val len = content.length
        while (i < len) {
            val c = content[i]
            val next = if (i + 1 < len) content[i + 1] else '\u0000'
            val next2 = if (i + 2 < len) content[i + 2] else '\u0000'
            if (inLineComment) {
                if (c == '\n') inLineComment = false
                i++
                continue
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false
                    i += 2
                    continue
                }
                i++
                continue
            }
            // Kotlin raw/triple-quoted strings ("""...""") do not process backslash
            // escapes and can legally contain lone " or ' characters (e.g. regex
            // literals like ["']). They must be tracked as their own state so an
            // embedded quote can't be mistaken for the string terminator.
            if (inTripleString) {
                if (c == '"' && next == '"' && next2 == '"') {
                    inTripleString = false
                    i += 3
                    continue
                }
                i++
                continue
            }
            if (escape) {
                escape = false
                i++
                continue
            }
            if (inString || inChar) {
                if (c == '\\') {
                    escape = true
                    i++
                    continue
                }
                if (inString && c == '"') inString = false
                if (inChar && c == '\'') inChar = false
                i++
                continue
            }
            if (c == '/' && next == '/') {
                inLineComment = true
                i += 2
                continue
            }
            if (c == '/' && next == '*') {
                inBlockComment = true
                i += 2
                continue
            }
            if (c == '"' && next == '"' && next2 == '"') {
                inTripleString = true
                i += 3
                continue
            }
            if (c == '"') {
                inString = true
                i++
                continue
            }
            if (c == '\'') {
                inChar = true
                i++
                continue
            }
            if (c == open) n++
            if (c == close) n--
            i++
        }
        return n
    }

    private fun unclosedString(content: String): Boolean {
        var i = 0
        var inTripleString = false
        var inString = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = false
        var escape = false
        val len = content.length
        while (i < len) {
            val c = content[i]
            val next = if (i + 1 < len) content[i + 1] else '\u0000'
            val next2 = if (i + 2 < len) content[i + 2] else '\u0000'
            if (inLineComment) {
                if (c == '\n') inLineComment = false
                i++
                continue
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false
                    i += 2
                    continue
                }
                i++
                continue
            }
            if (inTripleString) {
                if (c == '"' && next == '"' && next2 == '"') {
                    inTripleString = false
                    i += 3
                    continue
                }
                i++
                continue
            }
            if (escape) {
                escape = false
                i++
                continue
            }
            if (inString || inChar) {
                if (c == '\\') {
                    escape = true
                    i++
                    continue
                }
                if (inString && c == '"') inString = false
                if (inChar && c == '\'') inChar = false
                i++
                continue
            }
            if (c == '/' && next == '/') {
                inLineComment = true
                i += 2
                continue
            }
            if (c == '/' && next == '*') {
                inBlockComment = true
                i += 2
                continue
            }
            if (c == '"' && next == '"' && next2 == '"') {
                inTripleString = true
                i += 3
                continue
            }
            if (c == '"') {
                inString = true
                i++
                continue
            }
            if (c == '\'') {
                inChar = true
                i++
                continue
            }
            i++
        }
        return inString || inChar || inBlockComment || inTripleString
    }
}
