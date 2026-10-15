package com.codingagent.core

import com.codingagent.agent.ToolResultPager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultPagerTest {
    @Test fun everyPageReassemblesTheExactOriginalOutputInOrder() {
        val original = (0 until 40).joinToString("|") { "item-$it-" + "x".repeat(9) } + "END-SENTINEL"
        val pager = ToolResultPager(31)
        var page = pager.present("test-output", original)
        val rebuilt = StringBuilder()
        var pages = 0
        while (true) {
            pages++
            val start = page.indexOf("CONTENT_START\n")
            val end = page.lastIndexOf("\nCONTENT_END")
            assertTrue(start >= 0 && end > start)
            rebuilt.append(page.substring(start + "CONTENT_START\n".length, end))
            if (Regex("MORE=false").containsMatchIn(page)) break
            val id = Regex("RESULT_ID=([^\n]+)").find(page)!!.groupValues[1]
            val offset = Regex("NEXT_START_CHARACTER=(\d+)").find(page)!!.groupValues[1].toInt()
            page = pager.continueResult(id, offset)
        }
        assertTrue(pages > 1)
        assertEquals(original, rebuilt.toString())
    }

    @Test fun refusesToSkipUnseenParts() {
        val pager = ToolResultPager(4)
        val first = pager.present("test", "abcdefgh")
        val id = Regex("RESULT_ID=([^\n]+)").find(first)!!.groupValues[1]
        assertTrue(pager.continueResult(id, 5).startsWith("ERROR: This result must be read in order."))
    }
}
