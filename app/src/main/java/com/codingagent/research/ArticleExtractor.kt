package com.codingagent.research

import java.net.HttpURLConnection
import java.net.URL

/**
 * ONE JOB: URL/HTML → clean text and code blocks for research.
 */
object ResearchUrlSafety {
    fun validatePublicHttpUrl(raw: String): String? {
        val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull()
            ?: return "Invalid research URL"
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "Research URL must use HTTP or HTTPS"
        if (uri.userInfo != null) return "Research URL must not contain embedded credentials"
        val host = uri.host?.trim()?.lowercase()
            ?: return "Research URL has no hostname"
        if (host == "localhost" || host.endsWith(".localhost") || host == "metadata.google.internal") {
            return "Research URL targets a local or metadata hostname"
        }
        val addresses = runCatching { java.net.InetAddress.getAllByName(host).toList() }
            .getOrElse { return "Research hostname could not be resolved safely" }
        if (addresses.isEmpty()) return "Research hostname has no resolved address"
        if (addresses.any { isPrivateOrLocal(it) }) {
            return "Research URL resolves to a private, loopback, link-local, multicast, or unspecified address"
        }
        return null
    }

    private fun isPrivateOrLocal(address: java.net.InetAddress): Boolean {
        val host = address.hostAddress?.lowercase().orEmpty()
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return true
        return host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")
    }
}

object ArticleExtractor {
    data class Extracted(val title: String, val text: String, val wordCount: Int, val codeBlocks: List<String>) {
        val code: List<String> get() = codeBlocks
    }

    fun extract(html: String, fallbackTitle: String = "document"): Extracted = parse(html, fallbackTitle)

    fun fetch(
        url: String,
        connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
        timeoutMillis: Int = 15_000,
        urlValidator: (String) -> String? = ResearchUrlSafety::validatePublicHttpUrl
    ): Extracted? {
        if (urlValidator(url) != null) return null
        val connection = connectionFactory(url).apply {
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            parse(html, url)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(html: String, url: String): Extracted {
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), " ")?.trim()?.take(200) ?: url
        val cleaned = html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<nav[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<footer[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<sup[^>]*class=\"reference\"[\\s\\S]*?</sup>", RegexOption.IGNORE_CASE), " ")
        val codeBlocks = Regex("<pre[^>]*>([\\s\\S]*?)</pre>", RegexOption.IGNORE_CASE).findAll(cleaned)
            .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }
            .filter { it.length in 8..4000 }
            .take(8)
            .toList()
        var text = cleaned.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        text = text
            .replace(Regex("\\{\\{[^}]{0,400}\\}\\}"), " ")
            .replace(Regex("\\[\\[(?:File|Image|Category|Help|Wikipedia|Talk|User):[^\\]]+\\]\\]", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\|\\s*(access-date|archive-url|archive-date|url-status|website|publisher|date|title|url|last|first|ref)\\s*="), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        return Extracted(title, text.take(20_000), words.size, codeBlocks)
    }
}
