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

    data class Extracted(val title: String, val text: String, val wordCount: Int, val codeBlocks: List<String>) {
        val code: List<String> get() = codeBlocks
    }

    fun extract(html: String, fallbackTitle: String = "document"): Extracted = parse(html, fallbackTitle)

    fun fetch(
        url: String,
        connectionFactory: ((String) -> HttpURLConnection)? = null,
        timeoutMillis: Int = 15_000,
        urlValidator: (String) -> String? = ResearchUrlSafety::validatePublicHttpUrl,
        isCancelled: () -> Boolean = { false },
        onConnection: (HttpURLConnection) -> Unit = {},
        onCall: (okhttp3.Call) -> Unit = {}
    ): Extracted? {
        if (urlValidator(url) != null) return null
        return if (connectionFactory == null) {
            fetchWithOkHttp(url, timeoutMillis, isCancelled, onCall)
        } else {
            fetchWithUrlConnection(url, connectionFactory, timeoutMillis, isCancelled, onConnection)
        }
    }

    private fun fetchWithOkHttp(
        url: String,
        timeoutMillis: Int,
        isCancelled: () -> Boolean,
        onCall: (okhttp3.Call) -> Unit
    ): Extracted? {
        val call = ResearchUrlSafety.clientForTimeout(timeoutMillis).newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "Coding-Agent/1.0 (Android; https://github.com/thepotatoninjahost/Coding-Agent)")
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()
        )
        return try {
            onCall(call)
            if (isCancelled()) {
                call.cancel()
                return null
            }
            call.execute().use { response ->
                if (response.code !in 200..299 || isCancelled()) return null
                val body = response.body ?: return null
                val html = body.byteStream().use { readBounded(it, isCancelled) } ?: return null
                parse(html, url)
            }
        } catch (_: Exception) {
            null
        } finally {
            if (isCancelled()) call.cancel()
        }
    }

    private fun fetchWithUrlConnection(
        url: String,
        connectionFactory: (String) -> HttpURLConnection,
        timeoutMillis: Int,
        isCancelled: () -> Boolean,
        onConnection: (HttpURLConnection) -> Unit
    ): Extracted? {
        val connection = connectionFactory(url).apply {
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Coding-Agent/1.0 (Android; https://github.com/thepotatoninjahost/Coding-Agent)")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        return try {
            onConnection(connection)
            if (isCancelled()) return null
            if (connection.responseCode !in 200..299) return null
            val html = connection.inputStream.use { readBounded(it, isCancelled) } ?: return null
            parse(html, url)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
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
