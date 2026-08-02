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

    @Test fun howToUiQueryStaysFocusedOnCodingLanes() {
        val lanes = QueryLanes.expand("how creating custom user interface for apps")
        val names = lanes.map { it.name.lowercase() }
        assertTrue(names.any { it.contains("howto") || it.contains("ui") || it.contains("implementation") })
        assertFalse(names.any { it.contains("criticism") || it.contains("theoretical foundations") })
        assertFalse(lanes.any { it.query.contains("tradeoffs") })
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

    @Test fun sourceQualityRejectsUnrelatedWikipediaNoiseForUiQuery() {
        val terms = SourceQuality.queryTerms("how creating custom user interface for apps")
        assertFalse(SourceQuality.hasQueryRelevance(
            terms,
            "Microsoft Copilot",
            "Microsoft Copilot is a generative artificial intelligence chatbot",
            "https://en.wikipedia.org/wiki/Microsoft_Copilot"
        ))
        assertFalse(SourceQuality.hasQueryRelevance(
            terms,
            "Mastodon (social network)",
            "Mastodon is free and open-source software for running self-hosted social networking services",
            "https://en.wikipedia.org/wiki/Mastodon_(social_network)"
        ))
        assertFalse(SourceQuality.isAcceptable(
            "https://en.wikipedia.org/wiki/Talk:IOS_11",
            "Article Talk iOS 11",
            "{{cite web|url=https://example.com}}"
        ))
        assertFalse(SourceQuality.contentRelevant(
            terms,
            "K-Meleon",
            "{{cite web|last=Lekach|title=The coder who built Mastodon}} K-Meleon is a web browser"
        ))
    }

    @Test fun sourceQualityAcceptsRelevantUiSources() {
        val terms = SourceQuality.queryTerms("how creating custom user interface for apps")
        assertTrue(SourceQuality.hasQueryRelevance(
            terms,
            "Build a custom UI with Jetpack Compose",
            "Create custom user interface components for Android apps",
            "https://developer.android.com/jetpack/compose"
        ))
        assertTrue(SourceQuality.contentRelevant(
            terms,
            "Custom Views and ViewGroups",
            "Learn how to create custom user interface widgets for Android apps by extending View."
        ))
        assertTrue(SourceQuality.rankBoost("https://developer.android.com/develop/ui") >= 10)
        assertTrue(SourceQuality.rankBoost("https://en.wikipedia.org/wiki/Microsoft_Copilot") < 0)
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
