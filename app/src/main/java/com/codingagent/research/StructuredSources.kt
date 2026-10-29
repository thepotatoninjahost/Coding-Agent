package com.codingagent.research

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ONE JOB: Answer version and package questions from dated public lists.
 * The page text is cleaned so menu labels are not treated as the answer.
 */
object StructuredSources {
    fun answer(query: String, connect: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }): String? {
        val lower = query.lowercase()
        val wantsVersion = "version" in lower || "release" in lower || "latest version" in lower
        if (!wantsVersion) return null
        val product = productFor(query)
        val named = Regex("""\b\d+\.\d+(?:\.\d+)?\b""").find(query)?.value
        if (product != null && named != null) return namedRelease(product, named, connect)
        if (product != null) {
            endOfLife(product, connect)?.let { return it }
            github(product, connect)?.let { return it }
        }
        packageAnswer(query, connect)?.let { return it }
        return null
    }

    fun readableText(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("script, style, nav, footer, header, noscript").remove()
        var text = doc.body()?.text().orEmpty()
        listOf("Skip to content", "Navigation Menu", "Sign in", "Appearance settings").forEach { phrase ->
            text = text.replace(phrase, " ")
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun productFor(query: String): String? {
        val lower = query.lowercase()
        return when {
            "kotlin" in lower -> "kotlin"
            "python" in lower -> "python"
            "node" in lower || "nodejs" in lower -> "nodejs"
            "gradle" in lower -> "gradle"
            else -> null
        }
    }

    private fun namedRelease(product: String, version: String, connect: (String) -> HttpURLConnection): String {
        val body = get("https://endoflife.date/api/$product.json", connect, "application/json")
        val cycles = body?.let { runCatching { JSONArray(it) }.getOrNull() }
        if (cycles != null) {
            for (i in 0 until cycles.length()) {
                val cycle = cycles.optJSONObject(i) ?: continue
                val latest = cycle.optString("latest").trim()
                val cycleName = cycle.optString("cycle").trim()
                if (latest != version && cycleName != version) continue
                if (latest.isBlank() || isPrerelease(latest)) continue
                val date = cycle.optString("releaseDate").ifBlank { cycle.optString("latestReleaseDate") }.trim()
                val name = product.replaceFirstChar { it.uppercase() }
                val shown = if (latest == version) latest else cycleName
                return "$name $shown was published on ${speakDate(date)}. Source: https://endoflife.date/$product Date: ${date.ifBlank { "unknown" }}."
            }
        }
        val name = product.replaceFirstChar { it.uppercase() }
        return "$name $version was not found in the dated release list. Source: https://endoflife.date/$product"
    }

    private fun endOfLife(product: String, connect: (String) -> HttpURLConnection): String? {
        val body = get("https://endoflife.date/api/$product.json", connect, "application/json") ?: return null
        val cycles = runCatching { JSONArray(body) }.getOrNull() ?: return null
        for (i in 0 until cycles.length()) {
            val cycle = cycles.optJSONObject(i) ?: continue
            val latest = cycle.optString("latest").ifBlank { cycle.optString("cycle") }.trim()
            if (latest.isBlank() || isPrerelease(latest)) continue
            val date = cycle.optString("releaseDate").ifBlank { cycle.optString("latestReleaseDate") }.trim()
            val spoken = speakDate(date)
            val name = product.replaceFirstChar { it.uppercase() }
            return "$name $latest was published on $spoken. Source: https://endoflife.date/$product Date: ${date.ifBlank { "unknown" }}."
        }
        return null
    }

    private fun github(product: String, connect: (String) -> HttpURLConnection): String? {
        val repo = when (product) {
            "kotlin" -> "JetBrains/kotlin"
            "gradle" -> "gradle/gradle"
            else -> return null
        }
        val body = get("https://api.github.com/repos/$repo/releases/latest", connect, "application/vnd.github+json") ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val tag = json.optString("tag_name").trim().removePrefix("v")
        if (tag.isBlank() || isPrerelease(tag)) return null
        val date = json.optString("published_at").take(10)
        return "${repo.substringAfter('/')} $tag was published on ${speakDate(date)}. Source: ${json.optString("html_url")} Date: $date."
    }

    private fun packageAnswer(query: String, connect: (String) -> HttpURLConnection): String? {
        val lower = query.lowercase()
        val coordinate = Regex("""([a-z0-9_.]+):([a-z0-9_.-]+)""").find(lower)
        if (coordinate != null) {
            maven(coordinate.groupValues[1], coordinate.groupValues[2], connect)?.let { return it }
        }
        val named = Regex("""(?:pypi|pip|npm|package|version of)\s+([a-z0-9_.@/-]{2,80})""")
            .find(lower)?.groupValues?.get(1)
            ?.takeUnless { it in setOf("the", "this", "that", "kotlin", "python", "node", "nodejs", "gradle") }
            ?: return null
        if ("npm" in lower) return npm(named, connect)
        if ("pypi" in lower || "pip" in lower) return pypi(named, connect)
        return pypi(named, connect) ?: npm(named, connect)
    }

    private fun maven(group: String, artifact: String, connect: (String) -> HttpURLConnection): String? {
        val path = group.replace('.', '/') + "/" + artifact
        val body = get("https://repo1.maven.org/maven2/$path/maven-metadata.xml", connect, "application/xml") ?: return null
        val versions = Regex("<version>([^<]+)</version>").findAll(body).map { it.groupValues[1].trim() }.toList()
        val stable = versions.lastOrNull { it.isNotBlank() && !isPrerelease(it) } ?: return null
        return "$artifact $stable was published on Maven Central. Source: https://repo1.maven.org/maven2/$path/ Date: unknown."
    }

    private fun pypi(name: String, connect: (String) -> HttpURLConnection): String? {
        val body = get("https://pypi.org/pypi/$name/json", connect, "application/json") ?: return null
        val info = runCatching { JSONObject(body).optJSONObject("info") }.getOrNull() ?: return null
        val version = info.optString("version").trim()
        if (version.isBlank() || isPrerelease(version)) return null
        return "$name $version was published on PyPI. Source: https://pypi.org/project/$name/ Date: unknown."
    }

    private fun npm(name: String, connect: (String) -> HttpURLConnection): String? {
        val body = get("https://registry.npmjs.org/$name", connect, "application/json") ?: return null
        val version = runCatching { JSONObject(body).optJSONObject("dist-tags")?.optString("latest") }.getOrNull()?.trim().orEmpty()
        if (version.isBlank() || isPrerelease(version)) return null
        return "$name $version was published on npm. Source: https://www.npmjs.com/package/$name Date: unknown."
    }

    private fun isPrerelease(version: String): Boolean {
        val lower = version.lowercase()
        return listOf("-beta", "-rc", "-m", "-alpha", "-snapshot", ".preview").any { it in lower }
    }

    private fun speakDate(iso: String): String {
        val day = iso.take(10)
        val parsed = runCatching { LocalDate.parse(day) }.getOrNull() ?: return day.ifBlank { "an unknown date" }
        return parsed.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
    }

    private fun get(url: String, connect: (String) -> HttpURLConnection, accept: String): String? {
        val connection = connect(url)
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Coding-Agent/1.0 (Android; https://github.com/thepotatoninjahost/Coding-Agent)")
            connection.setRequestProperty("Accept", accept)
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
