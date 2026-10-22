package com.codingagent.research

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.Proxy
import java.net.URL
import java.util.concurrent.TimeUnit

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
        val host = uri.host?.trim()?.trim('[', ']')?.lowercase()
            ?: return "Research URL has no hostname"
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") ||
            host == "metadata.google.internal" || (!host.contains('.') && !host.contains(':'))) {
            return "Research URL targets a local or invalid hostname"
        }
        runCatching { resolvePublicAddresses(host) }
            .getOrElse { return "Research hostname could not be resolved safely" }
        return null
    }

    /**
     * This resolver is invoked by OkHttp during connection establishment. Only the returned,
     * checked addresses are used as socket destinations, closing the DNS check/connect gap.
     */
    internal fun newClient(
        dnsLookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }
    ): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(Dns { hostname -> resolvePublicAddresses(hostname, dnsLookup) })
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val sharedClient: OkHttpClient by lazy { newClient() }

    internal fun clientForTimeout(timeoutMillis: Int): OkHttpClient =
        sharedClient.newBuilder()
            .connectTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .build()

    private fun resolvePublicAddresses(
        host: String,
        dnsLookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }
    ): List<InetAddress> {
        val addresses = dnsLookup(host)
        if (addresses.isEmpty()) throw UnknownHostException("Research hostname has no resolved address")
        if (addresses.any(::isPrivateOrLocal)) {
            throw UnknownHostException("Research hostname resolved to a private or reserved address")
        }
        return addresses
    }

    internal fun isPrivateOrLocal(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()) {
            return isPrivateOrLocal(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        }
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return true

        if (bytes.size == 4) {
            val denied = listOf(
                "0.0.0.0" to 8, "10.0.0.0" to 8, "100.64.0.0" to 10,
                "127.0.0.0" to 8, "169.254.0.0" to 16, "172.16.0.0" to 12,
                "192.0.0.0" to 24, "192.0.2.0" to 24, "192.88.99.0" to 24,
                "192.168.0.0" to 16, "198.18.0.0" to 15, "198.51.100.0" to 24,
                "203.0.113.0" to 24, "224.0.0.0" to 4, "240.0.0.0" to 4
            )
            return denied.any { (network, prefix) -> inCidr(bytes, network, prefix) }
        }
        if (bytes.size == 16) {
            val denied = listOf(
                "fc00::" to 7, "fe80::" to 10, "ff00::" to 8,
                "2001:db8::" to 32, "2001::" to 23, "64:ff9b::" to 96,
                "64:ff9b:1::" to 48
            )
            return denied.any { (network, prefix) -> inCidr(bytes, network, prefix) }
        }
        return true
    }

    private fun inCidr(address: ByteArray, network: String, prefixLength: Int): Boolean {
        val networkBytes = InetAddress.getByName(network).address
        if (address.size != networkBytes.size) return false
        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        for (i in 0 until fullBytes) if (address[i] != networkBytes[i]) return false
        if (remainingBits == 0) return true
        val mask = (0xff shl (8 - remainingBits)) and 0xff
        return (address[fullBytes].toInt() and 0xff and mask) ==
            (networkBytes[fullBytes].toInt() and 0xff and mask)
    }
}

object ArticleExtractor {
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val MAX_REDIRECTS = 3

    data class Extracted(val title: String, val text: String, val wordCount: Int, val codeBlocks: List<String>) {
        val code: List<String> get() = codeBlocks
    }

    fun extract(html: String, fallbackTitle: String = "document"): Extracted = parse(html, fallbackTitle)

    data class FetchAttempt(val page: Extracted?, val error: String?)

    fun fetch(
        url: String,
        connectionFactory: ((String) -> HttpURLConnection)? = null,
        timeoutMillis: Int = 15_000,
        urlValidator: (String) -> String? = ResearchUrlSafety::validatePublicHttpUrl,
        isCancelled: () -> Boolean = { false },
        onConnection: (HttpURLConnection) -> Unit = {},
        onCall: (okhttp3.Call) -> Unit = {}
    ): Extracted? = fetchDetailed(
        url, connectionFactory, timeoutMillis, urlValidator, isCancelled, onConnection, onCall
    ).page

    /**
     * Reads a public page and follows a few redirects only when the next address
     * still passes the public-URL check. A redirect to a private or local address is refused.
     * The error text is kept so a failed lookup is visible instead of a blank result.
     */
    fun fetchDetailed(
        url: String,
        connectionFactory: ((String) -> HttpURLConnection)? = null,
        timeoutMillis: Int = 15_000,
        urlValidator: (String) -> String? = ResearchUrlSafety::validatePublicHttpUrl,
        isCancelled: () -> Boolean = { false },
        onConnection: (HttpURLConnection) -> Unit = {},
        onCall: (okhttp3.Call) -> Unit = {}
    ): FetchAttempt {
        val target = releaseApiUrl(url) ?: url
        return if (connectionFactory == null) {
            fetchWithOkHttp(target, timeoutMillis, urlValidator, isCancelled, onCall)
        } else {
            fetchWithUrlConnection(target, connectionFactory, timeoutMillis, urlValidator, isCancelled, onConnection)
        }
    }

    /** GitHub release pages are menu-heavy. The releases API returns the tag and the date. */
    internal fun releaseApiUrl(url: String): String? {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
        if (uri.host?.lowercase() != "github.com") return null
        val parts = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 3 || parts[2] != "releases") return null
        val owner = parts[0]
        val repo = parts[1]
        return when {
            parts.size >= 5 && parts[3] == "tag" ->
                "https://api.github.com/repos/$owner/$repo/releases/tags/${parts[4]}"
            parts.getOrNull(3) == null || parts[3] == "latest" ->
                "https://api.github.com/repos/$owner/$repo/releases/latest"
            else -> null
        }
    }

    internal fun resolveRedirect(current: String, location: String): String {
        val target = location.trim()
        if (target.isEmpty()) return target
        return runCatching { java.net.URI(current).resolve(target).toString() }.getOrDefault(target)
    }

    private fun fetchWithOkHttp(
        url: String,
        timeoutMillis: Int,
        urlValidator: (String) -> String?,
        isCancelled: () -> Boolean,
        onCall: (okhttp3.Call) -> Unit
    ): FetchAttempt {
        var current = url
        repeat(MAX_REDIRECTS + 1) { hop ->
            urlValidator(current)?.let { return FetchAttempt(null, it) }
            val call = ResearchUrlSafety.clientForTimeout(timeoutMillis).newCall(
                Request.Builder()
                    .url(current)
                    .header("User-Agent", "Coding-Agent/1.0 (Android; https://github.com/thepotatoninjahost/Coding-Agent)")
                    .header("Accept", acceptFor(current))
                    .get()
                    .build()
            )
            try {
                onCall(call)
                if (isCancelled()) {
                    call.cancel()
                    return FetchAttempt(null, "Research cancelled")
                }
                call.execute().use { response ->
                    if (isCancelled()) return FetchAttempt(null, "Research cancelled")
                    if (response.code in 300..399) {
                        val next = response.header("Location")?.let { resolveRedirect(current, it) }.orEmpty()
                        if (next.isBlank()) return FetchAttempt(null, "The page moved (HTTP ${response.code}) but gave no next address.")
                        if (hop == MAX_REDIRECTS) return FetchAttempt(null, "The page moved too many times.")
                        current = next
                        return@use
                    }
                    if (response.code !in 200..299) {
                        return FetchAttempt(null, "The page refused the lookup (HTTP ${response.code}).")
                    }
                    val body = response.body ?: return FetchAttempt(null, "The page was empty.")
                    val html = body.byteStream().use { readBounded(it, isCancelled) }
                        ?: return FetchAttempt(null, "The page was empty, too large, or the lookup was cancelled.")
                    return FetchAttempt(parse(html, current), null)
                }
            } catch (error: Exception) {
                return FetchAttempt(null, error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
            } finally {
                if (isCancelled()) call.cancel()
            }
        }
        return FetchAttempt(null, "The page moved too many times.")
    }

    private fun fetchWithUrlConnection(
        url: String,
        connectionFactory: (String) -> HttpURLConnection,
        timeoutMillis: Int,
        urlValidator: (String) -> String?,
        isCancelled: () -> Boolean,
        onConnection: (HttpURLConnection) -> Unit
    ): FetchAttempt {
        var current = url
        var hop = 0
        while (hop <= MAX_REDIRECTS) {
            urlValidator(current)?.let { return FetchAttempt(null, it) }
            val connection = connectionFactory(current).apply {
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Coding-Agent/1.0 (Android; https://github.com/thepotatoninjahost/Coding-Agent)")
                setRequestProperty("Accept", acceptFor(current))
            }
            val moved = try {
                onConnection(connection)
                if (isCancelled()) return FetchAttempt(null, "Research cancelled")
                val code = connection.responseCode
                if (code in 300..399) {
                    val next = connection.getHeaderField("Location")?.let { resolveRedirect(current, it) }.orEmpty()
                    if (next.isBlank()) return FetchAttempt(null, "The page moved (HTTP $code) but gave no next address.")
                    if (hop == MAX_REDIRECTS) return FetchAttempt(null, "The page moved too many times.")
                    current = next
                    true
                } else {
                    if (code !in 200..299) return FetchAttempt(null, "The page refused the lookup (HTTP $code).")
                    val html = connection.inputStream.use { readBounded(it, isCancelled) }
                        ?: return FetchAttempt(null, "The page was empty, too large, or the lookup was cancelled.")
                    return FetchAttempt(parse(html, current), null)
                }
            } catch (error: Exception) {
                return FetchAttempt(null, error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
            } finally {
                connection.disconnect()
            }
            if (moved) hop++
        }
        return FetchAttempt(null, "The page moved too many times.")
    }

    private fun readBounded(input: java.io.InputStream, isCancelled: () -> Boolean): String? {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            if (isCancelled()) return null
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) return null
            bytes.write(buffer, 0, count)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun acceptFor(url: String): String =
        if (url.contains("api.github.com")) "application/vnd.github+json" else "text/html,application/xhtml+xml"

    private fun parse(html: String, url: String): Extracted {
        releaseFromJson(html)?.let { return it }
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), " ")?.trim() ?: url
        val cleaned = html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<nav[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<footer[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<sup[^>]*class=\"reference\"[\\s\\S]*?</sup>", RegexOption.IGNORE_CASE), " ")
        val codeBlocks = Regex("<pre[^>]*>([\\s\\S]*?)</pre>", RegexOption.IGNORE_CASE).findAll(cleaned)
            .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }
            .filter { it.length >= 8 }
            .toList()
        var text = cleaned.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        listOf("Skip to content", "Navigation Menu", "Sign in", "Appearance settings").forEach { phrase ->
            text = text.replace(phrase, " ")
        }
        text = text.replace(Regex("\\s+"), " ").trim()
        text = text
            .replace(Regex("\\{\\{[^}]{0,400}\\}\\}"), " ")
            .replace(Regex("\\[\\[(?:File|Image|Category|Help|Wikipedia|Talk|User):[^\\]]+\\]\\]", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\|\\s*(access-date|archive-url|archive-date|url-status|website|publisher|date|title|url|last|first|ref)\\s*="), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        return Extracted(title, text, words.size, codeBlocks)
    }

    private fun releaseFromJson(body: String): Extracted? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return null
        val json = runCatching { org.json.JSONObject(trimmed) }.getOrNull() ?: return null
        val tag = json.optString("tag_name").trim()
        if (tag.isBlank()) return null
        val date = json.optString("published_at").take(10)
        val notes = json.optString("body").replace(Regex("\\s+"), " ").trim().take(1500)
        val text = "Release $tag was published on $date. $notes".trim()
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        return Extracted(json.optString("name").ifBlank { tag }, text, words.size, emptyList())
    }
}
