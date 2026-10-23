package com.codingagent.workspace

/**
 * ONE JOB: Turn a plain-text edit into an exact file change.
 * The model writes the code as text, not as an escaped JSON string.
 */
object SearchReplace {
    data class Block(val path: String, val search: String, val replace: String)

    private val block = Regex(
        """(?m)^([^\n]+)\n<<<<<<< SEARCH\n([\s\S]*?)\n=======\n([\s\S]*?)\n>>>>>>> REPLACE"""
    )

    fun parse(text: String): List<Block> =
        block.findAll(text).map { match ->
            Block(
                path = match.groupValues[1].trim().trim('`'),
                search = match.groupValues[2],
                replace = match.groupValues[3]
            )
        }.toList()

    fun apply(original: String?, search: String, replace: String): ApplyResult {
        val source = original.orEmpty()
        if (search.isEmpty()) {
            val created = if (source.isEmpty()) replace else source + (if (source.endsWith("\n") || replace.startsWith("\n")) "" else "\n") + replace
            return ApplyResult.Applied(created, "Added text.")
        }
        val exact = source.indexOf(search)
        if (exact >= 0 && source.indexOf(search, exact + 1) < 0) {
            return ApplyResult.Applied(source.replaceRange(exact, exact + search.length, replace), "Replaced the matching lines.")
        }
        if (exact >= 0) return ApplyResult.Failed("Those lines appear more than once. Include more surrounding lines so they match once.")
        val loose = looseMatch(source, search)
        if (loose != null) {
            return ApplyResult.Applied(source.replaceRange(loose.first, loose.second, replace), "Replaced the matching lines, ignoring extra spaces.")
        }
        return ApplyResult.Failed(closest(source, search))
    }

    private fun looseMatch(source: String, search: String): Pair<Int, Int>? {
        val wanted = search.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return null
        val lines = source.lines()
        var found: Pair<Int, Int>? = null
        for (start in lines.indices) {
            var cursor = start
            var ok = true
            for (wantedLine in wanted) {
                while (cursor < lines.size && lines[cursor].trim().isEmpty()) cursor++
                if (cursor >= lines.size || lines[cursor].trim() != wantedLine) {
                    ok = false
                    break
                }
                cursor++
            }
            if (!ok) continue
            if (found != null) return null
            val charStart = lines.take(start).sumOf { it.length + 1 }
            val charEnd = lines.take(cursor).sumOf { it.length + 1 }.coerceAtMost(source.length)
            found = charStart to charEnd.coerceAtLeast(charStart)
        }
        return found
    }

    private fun closest(source: String, search: String): String {
        val needle = search.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val hit = source.lines().withIndex().maxByOrNull { (_, line) ->
            if (needle.isEmpty()) 0 else line.trim().commonPrefixWith(needle).length
        }
        val where = if (hit == null || needle.isEmpty()) "I could not find those lines." else "The closest line is ${hit.index + 1}: ${hit.value.trim().take(160)}"
        return "I could not find the lines to replace. $where"
    }

    sealed class ApplyResult {
        data class Applied(val content: String, val note: String) : ApplyResult()
        data class Failed(val reason: String) : ApplyResult()
    }
}
