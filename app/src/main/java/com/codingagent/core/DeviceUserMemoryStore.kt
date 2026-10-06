package com.codingagent.core

import android.content.Context
import com.codingagent.agent.UserMemory
import com.codingagent.agent.UserMemoryStore
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * ONE JOB: Persist and selectively retrieve the local user's durable memories.
 *
 * Memory is deliberately separate from the system prompt, chat history, and project task history.
 * The encrypted payload is bounded so long-term learning cannot consume unbounded storage or context.
 */
internal class DeviceUserMemoryStore(context: Context) : UserMemoryStore {
    private val securePrefs = KeystoreSecretStore(context)
    private val lock = Any()

    override fun remember(
        text: String,
        kind: UserMemory.Kind,
        importance: Int
    ): UserMemory? {
        val clean = text.replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?')
        if (clean.length < 3 || clean.length > MAX_MEMORY_CHARS) return null
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val current = load()
            val match = current.maxByOrNull { similarity(it.text, clean) }
            val next = if (match != null && similarity(match.text, clean) >= MERGE_THRESHOLD) {
                current.map {
                    if (it.id == match.id) {
                        it.copy(
                            text = clean,
                            kind = kind,
                            importance = maxOf(it.importance, importance.coerceIn(1, 99)),
                            updatedAt = now
                        )
                    } else {
                        it
                    }
                }
            } else {
                current + UserMemory(
                    id = UUID.randomUUID().toString(),
                    text = clean,
                    kind = kind,
                    importance = importance.coerceIn(1, 99),
                    createdAt = now,
                    updatedAt = now
                )
            }
            save(compact(next))
            return load().firstOrNull { it.text == clean }
        }
    }

    override fun relevant(query: String, limit: Int): List<UserMemory> {
        if (limit <= 0) return emptyList()
        val queryTokens = tokens(query)
        synchronized(lock) {
            val memories = load()
            return memories
                .map { memory ->
                    val overlap = if (queryTokens.isEmpty()) 0.0 else {
                        val memoryTokens = tokens(memory.text)
                        memoryTokens.intersect(queryTokens).size.toDouble() /
                            queryTokens.union(memoryTokens).size.coerceAtLeast(1)
                    }
                    val stableBonus = memory.importance / 100.0
                    val score = overlap * 100.0 + stableBonus * 12.0
                    memory to score
                }
                .filter { (_, score) ->
                    queryTokens.isNotEmpty() && score >= MIN_RELEVANCE_SCORE
                }
                .sortedWith(
                    compareByDescending<Pair<UserMemory, Double>> { it.second }
                        .thenByDescending { it.first.updatedAt }
                )
                .take(limit)
                .map { it.first }
        }
    }

    override fun all(): List<UserMemory> = synchronized(lock) {
        load().sortedByDescending { it.updatedAt }
    }

    override fun forget(query: String): Int {
        val clean = query.trim()
        if (clean.isBlank()) return 0
        synchronized(lock) {
            val before = load()
            val kept = before.filterNot { memory ->
                memory.text.contains(clean, ignoreCase = true) ||
                    similarity(memory.text, clean) >= FORGET_THRESHOLD
            }
            val removed = before.size - kept.size
            if (removed > 0) save(kept)
            removed
        }
    }

    private fun load(): List<UserMemory> {
        val raw = securePrefs.getString(KeystoreSecretStore.USER_MEMORY) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        UserMemory(
                            id = item.getString("id"),
                            text = item.getString("text"),
                            kind = UserMemory.Kind.valueOf(item.getString("kind")),
                            importance = item.optInt("importance", 50).coerceIn(1, 99),
                            createdAt = item.getLong("createdAt"),
                            updatedAt = item.getLong("updatedAt")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(memories: List<UserMemory>) {
        val array = JSONArray()
        memories.forEach { memory ->
            array.put(
                JSONObject()
                    .put("id", memory.id)
                    .put("text", memory.text)
                    .put("kind", memory.kind.name)
                    .put("importance", memory.importance)
                    .put("createdAt", memory.createdAt)
                    .put("updatedAt", memory.updatedAt)
            )
        }
        securePrefs.putString(KeystoreSecretStore.USER_MEMORY, array.toString())
    }

    private fun compact(memories: List<UserMemory>): List<UserMemory> =
        memories
            .sortedWith(compareByDescending<UserMemory> { it.importance }.thenByDescending { it.updatedAt })
            .take(MAX_MEMORY_ENTRIES)

    private fun similarity(a: String, b: String): Double {
        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.intersect(right).size.toDouble()
        return intersection / left.union(right).size.toDouble()
    }

    private fun tokens(text: String): Set<String> =
        text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()

    companion object {
        private const val MAX_MEMORY_ENTRIES = 64
        private const val MAX_MEMORY_CHARS = 500
        private const val MERGE_THRESHOLD = 0.55
        private const val FORGET_THRESHOLD = 0.75
        private const val MIN_RELEVANCE_SCORE = 28.0
        private val STOP_WORDS = setOf(
            "the", "and", "for", "that", "this", "with", "from", "you",
            "your", "are", "have", "has", "not", "but", "can", "will",
            "should", "would", "could", "please", "want", "like", "prefer"
        )
    }
}
