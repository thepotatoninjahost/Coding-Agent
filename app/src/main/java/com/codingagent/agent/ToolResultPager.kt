package com.codingagent.agent

/** Keeps complete tool output available while returning it in manageable, ordered pages. */
internal class ToolResultPager(pageCharacters: Int) {
    private data class StoredResult(val label: String, val text: String, var nextOffset: Int)
    private val pageSize = pageCharacters.coerceAtLeast(1)
    private val results = linkedMapOf<String, StoredResult>()
    private var nextId = 1

    @Synchronized fun clear() { results.clear(); nextId = 1 }

    @Synchronized
    fun present(label: String, text: String): String {
        if (text.length <= pageSize) return text
        val id = "tool-result-${nextId++}"
        val stored = StoredResult(label, text, 0)
        results[id] = stored
        return render(id, 0, stored)
    }

    @Synchronized
    fun continueResult(id: String, startCharacter: Int): String {
        val stored = results[id] ?: return "ERROR: Complete tool output '$id' is not available in this run. Repeat the original tool call."
        if (startCharacter != stored.nextOffset) return "ERROR: This result must be read in order. The next startCharacter is ${stored.nextOffset}."
        return render(id, startCharacter, stored)
    }

    private fun render(id: String, start: Int, stored: StoredResult): String {
        var end = minOf(start + pageSize, stored.text.length)
        if (end < stored.text.length && end > start && stored.text[end - 1].isHighSurrogate() && stored.text[end].isLowSurrogate()) end--
        if (end == start) end = minOf(start + 2, stored.text.length)
        val chunk = stored.text.substring(start, end)
        val more = end < stored.text.length
        stored.nextOffset = end
        if (!more) results.remove(id)
        return "RESULT_ID=$id\nRESULT_LABEL=${stored.label}\nSTART_CHARACTER=$start\nNEXT_START_CHARACTER=$end\nTOTAL_CHARACTERS=${stored.text.length}\nMORE=$more\nCONTENT_START\n$chunk\nCONTENT_END"
    }
}
