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
            val id = page.lineSequence().first { it.startsWith("RESULT_ID=") }.substringAfter("=")
            val offset = page.lineSequence().first { it.startsWith("NEXT_START_CHARACTER=") }.substringAfter("=").toInt()
            page = pager.continueResult(id, offset)
        }
        assertTrue(pages > 1)
        assertEquals(original, rebuilt.toString())
    }

    @Test fun largeFileCanBeReadInOrderedChunksWithoutLoss() {
        val root = java.nio.file.Files.createTempDirectory("complete-large-file").toFile()
        val original = "BEGIN\\n" + "x".repeat(2 * 1024 * 1024) + "\\n🌍END-OF-FILE"
        root.resolve("large.txt").writeText(original)
        val tools = com.codingagent.agent.AgentTools(com.codingagent.workspace.ProjectWorkspace(root))
        val rebuilt = StringBuilder()
        var offset = 0
        var pages = 0
        while (true) {
            val part = tools.readChunk("large.txt", offset, 512 * 1024)
            assertEquals(offset, part.startCharacter)
            rebuilt.append(part.content)
            pages++
            if (!part.hasMore) break
            assertTrue(part.nextCharacter > offset)
            offset = part.nextCharacter
        }
        assertTrue(pages > 1)
        assertEquals(original, rebuilt.toString())
        assertEquals(original, com.codingagent.agent.LocalFileEvidence.readComplete(
            "large.txt", com.codingagent.workspace.ProjectFileService(com.codingagent.workspace.ProjectWorkspace(root))
        ))
    }

    @Test fun refusesToSkipUnseenParts() {
        val pager = ToolResultPager(4)
        val first = pager.present("test", "abcdefgh")
        val id = first.lineSequence().first { it.startsWith("RESULT_ID=") }.substringAfter("=")
        assertTrue(pager.continueResult(id, 5).startsWith("ERROR: This result must be read in order."))
    }
}
