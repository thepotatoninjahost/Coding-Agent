package com.codingagent.core

import java.net.HttpURLConnection
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepResearchTest {
    @Test fun expandsIntoFiveResearchLanes() {
        // Short and long queries must produce a usable number of focused lanes
        assertTrue(QueryLanes.expand("Kotlin flows").size >= 5)
        assertTrue(QueryLanes.expand("ch experimental code involvi", ResearchMode.EXPERIMENTAL).size >= 5)
    }

    @Test fun extractsArticleBodyAndCodeWithoutReturningOnlySnippet() {
        val result = ArticleExtractor.extract(
            """
            <html><nav>menu</nav><main><h1>Title</h1>
            <p>${"important guidance ".repeat(30)}</p>
            <pre>fun main() = 42</pre>
            </main></html>
            """.trimIndent()
        )
        assertTrue(result.text.length > 240)
        assertEquals("fun main() = 42", result.code.single())
    }

    @Test fun deepResearchFetchesManyUniqueSourcesAndPersistsLearning() {
        val root = Files.createTempDirectory("deep-research").toFile()
        val search = object : WebResearchProvider {
            override fun search(query: String, limit: Int): ResearchResult =
                ResearchResult(query, (1..12).map {
                    // Distinct domains so selectDiverse does not collapse
                    val host = if (it % 2 == 0) "github.com" else "stackoverflow.com"
                    ResearchHit(
                        "Kotlin networking Source $it",
                        "https://$host/example/net/$it",
                        "kotlin networking experimental code sample implementation"
                    )
                })
        }
        val provider = DurableDeepResearchProvider(
            researchRoot = root,
            pageTimeoutMillis = 1000,
            connectionFactory = { url -> fakeConnection(url) },
            searchProvider = search
        )
        val session = provider.deepResearch("Kotlin networking", 10)
        // Must retrieve and persist a full set of relevant sources
        assertTrue("expected ~10 sources, got ${session.sources.size}", session.sources.size >= 8)
        assertEquals(session.sources.size, session.learnedChunks)
        assertTrue(root.resolve("sessions").isDirectory)
        assertTrue(root.resolve("sessions").listFiles()?.isNotEmpty() == true)
        assertTrue(provider.recent().single().learnedChunks > 0)
    }

    @Test fun shortExperimentalQueryDoesNotSurfaceTradeoffLanes() {
        val lanes = QueryLanes.expand("ch experimental code involvi", ResearchMode.EXPERIMENTAL)
        assertTrue(lanes.none { it.name.contains("criticism") || it.name.contains("alternatives") })
        assertTrue(lanes.none { it.query.contains("tradeoffs") })
    }

    private fun fakeConnection(url: String): HttpURLConnection = object : HttpURLConnection(java.net.URL(url)) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream() =
            "<main><p>${"learned source content ".repeat(30)}</p><pre>val answer = 42</pre></main>".byteInputStream()
    }
}
