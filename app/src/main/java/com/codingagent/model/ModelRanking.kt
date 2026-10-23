package com.codingagent.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Built-in coding order, plus a saved order the phone can refresh from web research.
 *
 * The built-in order was taken from public coding boards on 22 October 2026.
 * A research page replaces that order only when it reads like a ranking and names
 * at least two helpers this app already knows. Otherwise the built-in order stays.
 */
object ModelRanking {
    const val STALE_MS = 7L * 24 * 60 * 60 * 1000
    private const val FILE_NAME = "model-ranking.json"

    fun bundledIds(): List<String> = ProviderCatalog.bundled.map { it.modelId }

    fun load(dir: File?): List<String> {
        if (dir == null) return bundledIds()
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return bundledIds()
        return runCatching {
            val array = JSONObject(file.readText()).optJSONArray("models") ?: return bundledIds()
            val ids = buildList {
                for (i in 0 until array.length()) {
                    val id = array.optString(i).trim()
                    if (id.isNotEmpty()) add(id)
                }
            }
            if (ids.isEmpty()) bundledIds() else ids
        }.getOrDefault(bundledIds())
    }

    fun needsRefresh(dir: File, now: Long): Boolean {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return true
        val updated = runCatching { JSONObject(file.readText()).optLong("updatedAt", 0L) }.getOrDefault(0L)
        return updated <= 0L || now - updated >= STALE_MS
    }

    fun save(dir: File, ids: List<String>, now: Long) {
        dir.mkdirs()
        val array = JSONArray()
        ids.distinct().forEach { array.put(it) }
        File(dir, FILE_NAME).writeText(
            JSONObject().put("updatedAt", now).put("models", array).toString()
        )
    }

    fun applyResearch(current: List<String>, researchText: String, knownIds: Set<String> = bundledIds().toSet()): List<String> {
        val lower = researchText.lowercase()
        val ranked = listOf("leaderboard", "ranked", "ranking", "best").any { it in lower }
        if (!ranked) return current
        val hits = knownIds.mapNotNull { id ->
            val index = lower.indexOf(id.lowercase())
            if (index >= 0) index to id else null
        }.sortedBy { it.first }
        if (hits.size < 2) return current
        val promoted = hits.map { it.second }.distinct()
        return (promoted + current.filter { it !in promoted }).distinct()
    }

    fun refreshIfStale(dir: File, now: Long = System.currentTimeMillis(), search: (String) -> String): List<String> {
        val current = load(dir)
        if (!needsRefresh(dir, now)) return current
        val text = runCatching { search("best free coding models leaderboard") }.getOrDefault("")
        val next = applyResearch(current, text)
        save(dir, next, now)
        return next
    }
}
