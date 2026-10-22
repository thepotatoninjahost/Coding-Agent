package com.codingagent.agent

/**
 * ONE JOB: Refuse a new or edited source file that is cut off or has unmatched brackets.
 * The phone does not have a compiler for every language, so this is a structural check.
 */
object StagedSourceCheck {
    private val codeExtensions = setOf(
        "py", "kt", "kts", "java", "js", "ts", "tsx", "jsx", "go", "rs",
        "c", "cc", "cpp", "h", "hpp", "swift", "rb", "php", "sh"
    )

    fun problem(path: String, content: String): String? {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext !in codeExtensions) return null
        if (content.isBlank()) {
            return "The file $path is empty. I did not save it. Put the program in the file and try again. Do not say it works."
        }
        return scan(content, ext)?.let { reason ->
            "$reason I did not save $path. Fix the file and try again. Do not say it works."
        }
    }

    private fun scan(content: String, ext: String): String? {
        var i = 0
        var paren = 0
        var brace = 0
        var bracket = 0
        var quote: Char? = null
        var triple = false
        while (i < content.length) {
            val c = content[i]
            if (quote != null) {
                if (!triple && c == '\\') {
                    i = (i + 2).coerceAtMost(content.length)
                    continue
                }
                if (triple && i + 2 < content.length && content[i] == quote && content[i + 1] == quote && content[i + 2] == quote) {
                    quote = null
                    triple = false
                    i += 3
                    continue
                }
                if (!triple && c == quote) quote = null
                i++
                continue
            }
            if (ext == "py" && c == '#') {
                while (i < content.length && content[i] != '\n') i++
                continue
            }
            if (ext != "py" && c == '/' && i + 1 < content.length && content[i + 1] == '/') {
                while (i < content.length && content[i] != '\n') i++
                continue
            }
            if (ext == "py" && i + 2 < content.length &&
                ((content[i] == '"' && content[i + 1] == '"' && content[i + 2] == '"') ||
                    (content[i] == '\'' && content[i + 1] == '\'' && content[i + 2] == '\''))
            ) {
                quote = content[i]
                triple = true
                i += 3
                continue
            }
            if (c == '"' || c == '\'') {
                quote = c
                triple = false
                i++
                continue
            }
            when (c) {
                '(' -> paren++
                ')' -> {
                    paren--
                    if (paren < 0) return "The file has a closing parenthesis without a matching opening one."
                }
                '{' -> brace++
                '}' -> {
                    brace--
                    if (brace < 0) return "The file has a closing brace without a matching opening one."
                }
                '[' -> bracket++
                ']' -> {
                    bracket--
                    if (bracket < 0) return "The file has a closing bracket without a matching opening one."
                }
            }
            i++
        }
        if (quote != null) return "The file stops in the middle of a quote."
        if (paren != 0 || brace != 0 || bracket != 0) {
            return "The file has brackets that do not match, so it would not run."
        }
        val last = content.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
        if (last.endsWith("\\") || last.endsWith("(") || last.endsWith("[") || last.endsWith("{") || last.endsWith(",")) {
            return "The file stops in the middle of a line."
        }
        if (ext == "py" && last.endsWith(":") && !last.startsWith("#")) {
            return "The file stops in the middle of a line."
        }
        return null
    }
}
