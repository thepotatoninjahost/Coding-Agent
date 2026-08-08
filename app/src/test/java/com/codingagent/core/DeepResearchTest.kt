package com.codingagent.core

import com.codingagent.research.*

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
        // EXPERIMENTAL exercises the multi-lane durable path (BROAD delegates to personal).
        val session = provider.deepResearch("Kotlin networking", 10, ResearchMode.EXPERIMENTAL)
        // Must retrieve and persist a full set of relevant sources
        assertTrue("expected ~10 sources, got ${session.sources.size}", session.sources.size >= 8)
        assertEquals(session.sources.size, session.learnedChunks)
        assertTrue(root.resolve("sessions").isDirectory)
        assertTrue(root.resolve("sessions").listFiles()?.isNotEmpty() == true)
        assertTrue(provider.recent().single().learnedChunks > 0)
    }

    @Test fun broadPersonalResearchRejectsOffTopicPageBodies() {
        val root = Files.createTempDirectory("personal-research").toFile()
        val search = object : WebResearchProvider {
            override fun search(query: String, limit: Int): ResearchResult =
                ResearchResult(
                    query,
                    listOf(
                        ResearchHit(
                            "Kotlin networking guide",
                            "https://github.com/example/kotlin-net",
                            "kotlin networking sample"
                        ),
                        ResearchHit(
                            "Unrelated chemistry notes",
                            "https://example.com/chemistry",
                            "organic synthesis reactions"
                        )
                    )
                )
        }
        val provider = PersonalResearchProvider(
            researchRoot = root,
            pageTimeoutMillis = 1000,
            connectionFactory = { url ->
                if (url.contains("chemistry")) {
                    fakeConnectionWithBody("<main><p>${"organic chemistry reactions ".repeat(40)}</p></main>")
                } else {
                    fakeConnection(url)
                }
            },
            searchProvider = search
        )
        val session = provider.deepResearch("Kotlin networking", 6, ResearchMode.BROAD)
        assertTrue(session.sources.isNotEmpty())
        assertTrue(session.sources.all { it.url.contains("kotlin-net") || it.title.contains("Kotlin", ignoreCase = true) })
        assertTrue(session.sources.none { it.url.contains("chemistry") })
    }

    @Test fun shortExperimentalQueryDoesNotSurfaceTradeoffLanes() {
        val lanes = QueryLanes.expand("ch experimental code involvi", ResearchMode.EXPERIMENTAL)
        assertTrue(lanes.none { it.name.contains("criticism") || it.name.contains("alternatives") })
        assertTrue(lanes.none { it.query.contains("tradeoffs") })
    }

    /** Page body must mention query terms so SourceQuality.contentRelevant accepts it. */
    private fun fakeConnection(url: String): HttpURLConnection =
        fakeConnectionWithBody(
            "<main><p>${"kotlin networking learned source content ".repeat(30)}</p>" +
                "<pre>val answer = 42</pre></main>"
        )

    private fun fakeConnectionWithBody(body: String): HttpURLConnection =
        object : HttpURLConnection(java.net.URL("https://example.com/mock")) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getInputStream() = body.byteInputStream()
        }
}
