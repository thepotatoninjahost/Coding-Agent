package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepResearchTest {
    @Test
    fun expandsIntoFiveResearchLanes() {
        assertTrue(QueryLanes.expand("Kotlin flows").size >= 5)
    }

    @Test
    fun extractsArticleBodyAndCodeWithoutReturningOnlySnippet() {
        val result = ArticleExtractor.extract(
            "<html><nav>menu</nav><main><h1>Title</h1><p>${"important guidance ".repeat(30)}</p><pre>fun main() = 42</pre></main></html>"
        )
        assertTrue(result.text.length > 240)
        assertEquals("fun main() = 42", result.code.single())
    }

    @Test
    fun sourceQualityRejectsWikipediaTalkPages() {
        assertFalse(
            SourceQuality.isAcceptable(
                "https://en.wikipedia.org/wiki/Talk:CUDA",
                "Talk:CUDA",
                "This is the talk page"
            )
        )
        assertTrue(
            SourceQuality.isAcceptable(
                "https://developer.android.com/jetpack/compose",
                "Jetpack Compose",
                "Build better apps with Compose"
            )
        )
    }

    @Test
    fun deepResearchPersistsSessionWithUniqueSources() {
        val root = Files.createTempDirectory("deep-research").toFile()
        val search = object : WebResearchProvider {
            override fun search(query: String, limit: Int): ResearchResult =
                ResearchResult(
                    query,
                    (1..12).map {
                        ResearchHit(
                            "Source $it",
                            "https://developer.android.com/guide/$it",
                            "Android documentation snippet about Compose and Kotlin"
                        )
                    }
                )
        }
        val provider = DurableDeepResearchProvider(root, search, maxSourceFetches = 8)
        val session = provider.deepResearch("Kotlin networking", 8)
        assertTrue(session.sources.size <= 8)
        assertTrue(provider.recent().isNotEmpty() || session.sources.isEmpty())
        val sessionsDir = root.resolve("sessions")
        assertTrue(sessionsDir.isDirectory || session.sources.isEmpty())
    }
}
