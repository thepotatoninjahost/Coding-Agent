package com.codingagent.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchModeTest {
    @Test fun researchLanesCoverTheoryExperimentAndEmpiricalEvidence() {
        val lanes = QueryLanes.expand("new compiler architecture for distributed systems").map { it.name }
        assertTrue(lanes.contains("theory focus") || lanes.contains("theoretical foundations") || lanes.any { it.contains("theory") })
        assertTrue(lanes.any { it.contains("experimental") })
        assertTrue(lanes.any { it.contains("empirical") || it.contains("failure") || it.contains("standards") })
        // Long queries may still receive alternatives/criticism
        assertTrue(lanes.any { it.contains("alternatives") || it.contains("criticism") || it.contains("failure") })
    }

    @Test fun shortQueryAvoidsTradeoffsLane() {
        val short = "ch experimental code involvi"
        val lanes = QueryLanes.expand(short, ResearchMode.EXPERIMENTAL)
        val names = lanes.map { it.name }
        val queries = lanes.map { it.query.lowercase() }
        assertFalse("short query must not expand into tradeoffs/criticism lane",
            names.any { it.contains("alternatives") || it.contains("criticism") })
        assertFalse("short query must not inject tradeoffs keyword",
            queries.any { it.contains("tradeoffs") })
        assertTrue("experimental mode should prioritize experimental + github lanes",
            names.any { it.contains("experimental") } && names.any { it.contains("github") || it.contains("community") })
        assertTrue(lanes.size >= 3)
    }

    @Test fun experimentalModePrefersGithubAndCodeHosts() {
        val lanes = QueryLanes.expand("experimental kotlin coroutines scheduler", ResearchMode.EXPERIMENTAL)
        val joined = lanes.joinToString(" ") { it.query.lowercase() }
        assertTrue(joined.contains("site:github.com") || joined.contains("experimental"))
        assertTrue(lanes.first().name.contains("experimental") || lanes[0].query.contains("experimental"))
    }

    @Test fun sourceQualityRejectsChessAndTradeoffNoise() {
        assertFalse(SourceQuality.isAcceptable(
            "https://www.chess.com/news/view/2026-wr-chess-rapid",
            "U.S. Takes 5-3 Lead Vs. Uzbekistan In Miami Rapid Chess",
            "Rapid chess news"
        ))
        assertFalse(SourceQuality.isAcceptable(
            "https://www.slideshare.net/slideshow/tradeoffs-in-professional-practice",
            "TRADEOFFS IN PROFESSIONAL PRACTICE1.pptx - SlideShare",
            "tradeoffs presentation"
        ))
        assertFalse(SourceQuality.isAcceptable(
            "https://www.geeksforgeeks.org/system-design/tradeoffs-in-system-design/",
            "Tradeoffs in System Design - GeeksforGeeks",
            "System Design Tradeoffs"
        ))
        val terms = SourceQuality.queryTerms("ch experimental code involvi")
        assertFalse(SourceQuality.hasQueryRelevance(
            terms,
            "System Design Tradeoffs: How to Think and Explain in Interviews",
            "In System Design, a tradeoff is a deliberate decision",
            "https://example.com/tradeoffs"
        ))
    }

    @Test fun sourceQualityAcceptsRelevantCodeHosts() {
        val terms = SourceQuality.queryTerms("experimental kotlin coroutines")
        assertTrue(SourceQuality.hasQueryRelevance(
            terms,
            "Experimental coroutines API",
            "kotlinx.coroutines experimental builders",
            "https://github.com/Kotlin/kotlinx.coroutines"
        ))
        assertTrue(SourceQuality.rankBoost("https://github.com/Kotlin/kotlinx.coroutines") >= 8)
        assertTrue(SourceQuality.rankBoost("https://developer.android.com/kotlin/coroutines") >= 10)
    }
}
