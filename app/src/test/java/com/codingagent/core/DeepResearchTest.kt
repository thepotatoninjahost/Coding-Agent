package com.codingagent.core

import java.net.HttpURLConnection
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepResearchTest {
    @Test fun expandsIntoFiveResearchLanes() {
        assertTrue(QueryLanes.expand("Kotlin flows").size >= 5)
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
                ResearchResult(query, (1..10).map { ResearchHit("Source $it", "https://example.com/$it", "snippet") })
        }
        val provider = DurableDeepResearchProvider(
            researchRoot = root,
            pageTimeoutMillis = 1000,
            connectionFactory = { url -> fakeConnection(url) },
            searchProvider = search
        )
        val session = provider.deepResearch("Kotlin networking", 10)
        assertEquals(10, session.sources.size)
        assertTrue(root.resolve("sessions").isDirectory)
        assertTrue(root.resolve("sessions").listFiles()?.isNotEmpty() == true)
        assertTrue(provider.recent().single().learnedChunks > 0)
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
