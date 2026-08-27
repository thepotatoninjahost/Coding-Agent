package com.codingagent.agent

/**
 * ONE JOB: Cheap brace/paren/bracket balance notes. Not a compiler.
 */
object StructureScan {
    fun notes(content: String): List<String> {
        val notes = mutableListOf<String>()
        fun balance(open: Char, close: Char, label: String) {
            var n = 0
            var inString = false
            var inChar = false
            var escape = false
            var i = 0
            while (i < content.length) {
                val c = content[i]
                if (escape) { escape = false; i++; continue }
                if (c == '\\' && (inString || inChar)) { escape = true; i++; continue }
                if (!inChar && c == '"') { inString = !inString; i++; continue }
                if (!inString && c == '\'') { inChar = !inChar; i++; continue }
                if (inString || inChar) { i++; continue }
                if (c == open) n++
                if (c == close) n--
                i++
            }
            if (n != 0) {
                notes += if (n > 0) "$label imbalance: extra $open ($n)" else "$label imbalance: extra $close (${-n})"
            }
        }
        balance('{', '}', "Brace")
        balance('(', ')', "Paren")
        balance('[', ']', "Bracket")
        val last = content.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
        if (last.endsWith("=") || last.endsWith(".")) {
            notes += "File ends mid-expression (line ends with '${last.last()}')"
        }
        return notes
    }
}
