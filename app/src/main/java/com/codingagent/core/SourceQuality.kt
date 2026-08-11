package com.codingagent.core

import java.util.regex.Pattern

/**
 * ONE JOB: Accept/reject/score research sources by domain, title, and relevance.
 */
object SourceQuality {
    // In Kotlin raw strings, a single backslash is literal — use \b, \{, \[ for Java regex metacharacters.
    // Doubling them (\\[) leaves an unescaped '[' and blows up Pattern.compile in the static initializer.
    private val junkTitle = Pattern.compile(
        """\b(talk|disambiguation|user talk|wikiProject|sandbox|article talk|wikipedia search)\b""",
        Pattern.CASE_INSENSITIVE
    )
    private val junkExcerpt = Pattern.compile(
        """(please help improve|this article needs|not a guidebook|learn how and when to remove|for other uses, see|\{\{cite|cite web\||Article Talk|\[\[Category:)""",
        Pattern.CASE_INSENSITIVE
    )
    private val wikiNoise = Pattern.compile(
        """(\{\{cite|\|access-date=|\|archive-url=|\|url-status=|Article Talk|\[\[Category:|Help improve this article)""",
        Pattern.CASE_INSENSITIVE
    )
    private val blockedDomains = listOf(
        "chess.com", "slideshare.net", "slideshare.com", "pinterest.com",
        "facebook.com", "twitter.com", "x.com", "instagram.com",
        "reddit.com/r/chess", "espn.com", "cnn.com", "bbc.com",
        "nytimes.com", "forbes.com", "medium.com/tag", "quora.com",
        "fullframeinitiative.org", "thisvsthat.io", "geeksforgeeks.org/system-design",
        "educative.io", "wellbeing", "wordvice.com", "aje.com",
        "adalo.com", "knack.com", "zoho.com/creator", "bubble.io", "glideapps.com",
        "thunkable.com", "appgyver.com", "mendix.com", "outsystems.com",
        "powerapps.microsoft.com", "make.com", "zapier.com"
    )
    private val blockedTitleTokens = listOf(
        "tradeoffs in system design", "system design tradeoffs", "tradeoffs in professional",
        "criticism vs. critique", "u.s. takes", "rapid chess", "wellbeing",
        "making change is hard", "investor relations", "mastodon (social", "k-meleon",
        "windows installer", "microsoft copilot", "career opportunities",
        "elements of a metaverse", "learning through play", "limitations of the study",
        "how to write limitations", "mbti-in-thoughts", "leaky thoughts",
        "ftc sdk", "ftc robot", "first tech challenge", "ftcrobotcontroller",
        "no-code", "no code", "low-code", "low code", "app builder",
        "power apps", "powerapps", "adalo", "knack", "bubble.io"
    )
    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "for", "on", "with",
        "is", "are", "be", "by", "at", "from", "as", "it", "this", "that",
        "code", "experimental", "involving", "how", "what", "when", "where",
        "can", "do", "does", "using", "use", "used", "into", "about", "your",
        "my", "me", "we", "our", "their", "them", "than", "then", "also"
    )
    private val keepShortTerms = setOf("ui", "ux", "api", "ios", "js", "ts", "c#", "c++")
    val genericResearchTerms = setOf(
        "research", "study", "paper", "article", "review", "analysis",
        "independent", "thought", "thinking", "idea", "theory", "concept"
    )
    private val uiSignals = listOf(
        "user interface", "custom ui", "custom view", "custom component",
        "jetpack compose", "swiftui", "react native", "compose ui",
        "android ui", "ios ui", "layout", "widget", "viewgroup",
        "uikit", "swift ui", "xml layout", "constraint layout"
    )

    fun isCodeHost(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("github.com") ||
            u.contains("stackoverflow.com") ||
            u.contains("developer.android.com") ||
            u.contains("kotlinlang.org") ||
            u.contains("developer.apple.com") ||
            u.contains("docs.oracle.com") ||
            u.contains("developer.mozilla.org")
    }

    fun queryTerms(query: String): Set<String> =
        query.lowercase()
            .split(Regex("[^a-z0-9+#]+"))
            .filter { token ->
                token.isNotBlank() &&
                    token !in stopWords &&
                    (token.length >= 3 || token in keepShortTerms)
            }
            .toSet()

    fun isUiQuery(terms: Set<String>): Boolean {
        val t = terms.map { it.lowercase() }.toSet()
        return t.contains("ui") || t.contains("ux") ||
            t.contains("interface") || t.contains("compose") ||
            t.contains("layout") || t.contains("widget") ||
            t.contains("view") || t.contains("swiftui")
    }

    fun hasQueryRelevance(terms: Set<String>, title: String, excerpt: String, url: String): Boolean {
        if (terms.isEmpty()) return false
        val hay = "$title $excerpt $url".lowercase()
        val hits = terms.count { hay.contains(it) }
        if (hits == 0) return false
        val nonGeneric = terms.filter { it !in genericResearchTerms }
        if (nonGeneric.isEmpty() && !isCodeHost(url)) return false
        if (isUiQuery(terms)) {
            val hasUiSignal = uiSignals.any { hay.contains(it) } ||
                hay.contains(" compose") || hay.contains("custom view") ||
                hay.contains("user interface") || hay.contains("swiftui") ||
                hay.contains("jetpack")
            if (!hasUiSignal && !isCodeHost(url)) return false
            if (hits < 2 && !hasUiSignal) return false
        }
        return when {
            terms.size <= 2 -> hits >= 1 && (isCodeHost(url) || nonGeneric.isNotEmpty())
            terms.size <= 4 -> hits >= 2 || (hits.toDouble() / terms.size >= 0.5 && isCodeHost(url))
            else -> hits >= 3 || hits.toDouble() / terms.size >= 0.4
        }
    }

    fun contentRelevant(terms: Set<String>, title: String, content: String): Boolean {
        if (terms.isEmpty()) return false
        if (wikiNoise.matcher(content.take(800)).find()) return false
        val hay = "$title ${content.take(4_000)}".lowercase()
        val hits = terms.count { hay.contains(it) }
        if (isUiQuery(terms)) {
            val hasUiSignal = uiSignals.any { hay.contains(it) } ||
                hay.contains("custom view") || hay.contains("user interface") ||
                hay.contains("jetpack compose") || hay.contains("swiftui")
            if (!hasUiSignal && hits < 3) return false
        }
        return when {
            terms.size <= 2 -> hits >= 1
            terms.size <= 4 -> hits >= 2
            else -> hits >= 3
        }
    }

    fun relevanceScore(terms: Set<String>, hit: ResearchHit): Int {
        if (terms.isEmpty()) return 0
        val hay = "${hit.title} ${hit.excerpt} ${hit.url}".lowercase()
        var score = terms.count { hay.contains(it) } * 5
        if (hay.contains("user interface") || hay.contains("custom view") || hay.contains("custom ui")) score += 8
        if (hay.contains("jetpack compose") || hay.contains("swiftui") || hay.contains("react native")) score += 6
        if (isCodeHost(hit.url)) score += 4
        if (hay.contains("ftc") || hay.contains("robot controller") || hay.contains("first tech")) score -= 12
        if (hay.contains("no-code") || hay.contains("no code") || hay.contains("app builder") ||
            hay.contains("power apps") || hay.contains("adalo") || hay.contains("knack")
        ) score -= 12
        return score
    }

    fun isAcceptable(url: String, title: String, excerpt: String): Boolean {
        val u = url.lowercase()
        val t = title.lowercase()
        val e = excerpt.lowercase()
        if (u.contains("wikipedia.org")) {
            if (t.contains("talk") || u.contains("talk:") || u.contains("disambiguation")) return false
            if (u.contains("/wiki/talk:") || u.contains("/wiki/user:") || u.contains("/wiki/wikipedia:")) return false
            if (wikiNoise.matcher(excerpt).find()) return false
            if (t.contains("article talk") || e.contains("article talk")) return false
        }
        if (junkTitle.matcher(title).find()) return false
        if (junkExcerpt.matcher(excerpt).find()) return false
        if (wikiNoise.matcher(excerpt).find()) return false
        if (blockedDomains.any { u.contains(it) || t.contains(it) }) return false
        if (blockedTitleTokens.any { t.contains(it) }) return false
        if (t.contains("chess") || t.contains("wellbeing")) return false
        if (t.contains("tradeoff") && (t.contains("interview") || t.contains("system design"))) return false
        if (t.contains("metaverse") || t.contains("career opportunit") || t.contains("mbti")) return false
        if (t.contains("limitations of") && t.contains("study")) return false
        if (t.contains("ftc") || t.contains("first tech challenge") || u.contains("ftcrobot")) return false
        if (t.contains("no-code") || t.contains("no code") || t.contains("low-code") || t.contains("app builder")) return false
        if (t.contains("power apps") || t.contains("powerapps") || u.contains("power-apps") || u.contains("powerapps")) return false
        return true
    }

    fun rankBoost(url: String): Int {
        val u = url.lowercase()
        return when {
            u.contains("developer.android.com") || u.contains("kotlinlang.org") -> 14
            u.contains("developer.apple.com") -> 12
            u.contains("github.com") -> 11
            u.contains("stackoverflow.com") -> 10
            u.contains("android.com") -> 9
            u.contains("docs.") || u.contains("developer.") -> 8
            u.contains("medium.com") || u.contains("dev.to") -> 2
            u.contains("wikipedia.org") -> -6
            else -> 3
        }
    }
}
