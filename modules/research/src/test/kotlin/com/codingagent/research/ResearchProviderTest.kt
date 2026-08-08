package com.codingagent.research

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResearchProviderTest {
    @Test fun articleExtractorKeepsTitleTextAndCode() {
        val result = ArticleExtractor.parse("<html><title>Docs</title><main><p>Use this API carefully.</p><pre>fun main() = 1</pre></main></html>")
        assertEquals("Docs", result.title)
        assertTrue(result.text.contains("Use this API"))
        assertEquals(1, result.codeBlocks.size)
    }

    @Test fun modeDetectorSeparatesResearchIntent() {
        assertEquals(ResearchMode.EMPIRICAL, ResearchModeDetector.detect("benchmark latency and compare throughput"))
        assertEquals(ResearchMode.THEORETICAL, ResearchModeDetector.detect("formal model and RFC specification"))
        assertEquals(ResearchMode.EXPERIMENTAL, ResearchModeDetector.detect("novel unconventional prototype"))
        assertEquals(ResearchMode.BROAD, ResearchModeDetector.detect("build a Kotlin parser"))
    }
}
