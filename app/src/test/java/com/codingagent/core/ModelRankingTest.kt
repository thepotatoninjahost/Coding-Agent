package com.codingagent.core

import com.codingagent.model.ModelRanking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class ModelRankingTest {
    @Test
    fun aRealRankingMovesKnownHelpersForwardAndOtherTextDoesNot() {
        val bundled = ModelRanking.bundledIds()
        assertEquals(bundled, ModelRanking.applyResearch(bundled, "the weather is fine and nothing is ranked"))

        val researched = ModelRanking.applyResearch(
            bundled,
            "October coding leaderboard: gemini-3.8-flash then openai/gpt-oss-120b."
        )
        assertEquals("gemini-3.8-flash", researched[0])
        assertEquals("openai/gpt-oss-120b", researched[1])
        assertEquals(bundled.size, researched.size)
    }

    @Test
    fun staleRankingIsRefreshedFromResearchAndAFreshFileIsLeftAlone() {
        val dir = Files.createTempDirectory("model-ranking").toFile()
        var searches = 0
        val first = ModelRanking.refreshIfStale(dir) {
            searches++
            "Best coding ranking: openai/gpt-oss-120b leads gemini-3.8-flash."
        }
        assertEquals(1, searches)
        assertEquals("openai/gpt-oss-120b", first[0])
        ModelRanking.refreshIfStale(dir) {
            searches++
            "leaderboard: gemini-3.8-flash then openai/gpt-oss-120b"
        }
        assertEquals(1, searches)
    }
}
