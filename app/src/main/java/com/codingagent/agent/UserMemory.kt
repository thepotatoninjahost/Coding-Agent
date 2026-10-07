package com.codingagent.agent

import java.util.Locale
import java.util.UUID

/**
 * ONE JOB: Define durable user memory as user-scoped facts/preferences separate from system instructions.
 */
data class UserMemory(
    val id: String,
    val text: String,
    val kind: Kind,
    val importance: Int,
    val createdAt: Long,
    val updatedAt: Long
) {
    enum class Kind {
        PREFERENCE,
        FACT,
        WORKFLOW
    }
}

interface UserMemoryStore {
    fun remember(text: String, kind: UserMemory.Kind = UserMemory.Kind.PREFERENCE, importance: Int = 50): UserMemory?
    fun relevant(query: String, limit: Int = 6): List<UserMemory>
    fun all(): List<UserMemory>
    fun forget(query: String): Int
}

object UserMemoryExtractor {
    private val durablePrefixes = listOf(
        "remember that ",
        "remember ",
        "don't forget that ",
        "do not forget that ",
        "from now on ",
        "i prefer ",
        "i like ",
        "i don't like ",
        "i do not like ",
        "i hate "
    )

    private val transientScope = Regex(
        "\\b(for now|for this task|for this request|in this project|in this conversation|this time|today only|just this once)\\b",
        RegexOption.IGNORE_CASE
    )

    private val secretPattern = Regex(
        "\\b(api[ _-]?key|access[ _-]?token|password|passphrase|secret|private[ _-]?key|credit[ _-]?card)\\b",
        RegexOption.IGNORE_CASE
    )

    fun extract(message: String): String? {
        val normalized = message.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank() || transientScope.containsMatchIn(normalized)) return null
        if (secretPattern.containsMatchIn(normalized)) return null

        val lower = normalized.lowercase(Locale.ROOT)
        val prefix = durablePrefixes.firstOrNull { lower.startsWith(it) } ?: return null
        val body = normalized.substring(prefix.length).trim().trimEnd('.', '!', '?')
        if (body.length < 3 || body.length > 500) return null
        if (body.split(Regex("\\s+")).size < 2 && prefix != "remember ") return null
        return body
    }

    fun kind(text: String): UserMemory.Kind {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("how you work") ||
                lower.contains("workflow") ||
                lower.contains("when you") ||
                lower.contains("format") ||
                lower.contains("style") ||
                lower.contains("tone") ||
                (lower.contains("explain") || lower.contains("explanation")) -> UserMemory.Kind.WORKFLOW
            lower.startsWith("my ") ||
                lower.startsWith("i use ") ||
                lower.startsWith("i work ") -> UserMemory.Kind.FACT
            else -> UserMemory.Kind.PREFERENCE
        }
    }

    fun importance(text: String): Int {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("from now on") ||
                lower.startsWith("always ") ||
                lower.startsWith("never ") ||
                lower.startsWith("do not ever ") ||
                lower.startsWith("don't ever ") -> 90
            lower.startsWith("remember ") ||
                lower.startsWith("remember that ") ||
                lower.startsWith("don't forget ") ||
                lower.startsWith("do not forget ") -> 80
            else -> 60
        }
    }
}


/**
 * ONE JOB: Apply deterministic memory consolidation and relevance rules without Android dependencies.
 */
class UserMemoryIndex(
    private val maxEntries: Int = 64
) {
    fun remember(
        existing: List<UserMemory>,
        text: String,
        kind: UserMemory.Kind,
        importance: Int,
        now: Long
    ): List<UserMemory> {
        val clean = text.replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?')
        if (clean.length < 3 || clean.length > 500) return existing
        val match = existing.maxByOrNull { similarity(it.text, clean) }
        val next = if (match != null && (
            similarity(match.text, clean) >= 0.55 ||
                sameDirectiveTopic(match.text, clean)
            )) {
            existing.map {
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
            existing + UserMemory(
                id = UUID.randomUUID().toString(),
                text = clean,
                kind = kind,
                importance = importance.coerceIn(1, 99),
                createdAt = now,
                updatedAt = now
            )
        }
        return compact(next)
    }

    fun relevant(existing: List<UserMemory>, query: String, limit: Int): List<UserMemory> {
        if (limit <= 0) return emptyList()
        val queryTokens = tokens(query)
        val stable = existing
            .filter { it.kind == UserMemory.Kind.WORKFLOW && it.importance >= 80 }
            .sortedWith(compareByDescending<UserMemory> { it.importance }.thenByDescending { it.updatedAt })
            .take(2)

        val scored = if (queryTokens.isEmpty()) {
            emptyList()
        } else {
            existing
                .filterNot { it.id in stable.map(UserMemory::id).toSet() }
                .map { memory ->
                    val memoryTokens = tokens(memory.text)
                    val overlap = memoryTokens.intersect(queryTokens).size.toDouble() /
                        queryTokens.union(memoryTokens).size.coerceAtLeast(1)
                    val score = overlap * 100.0 + memory.importance / 100.0 * 12.0
                    memory to score
                }
                .filter { (_, score) -> score >= 28.0 }
                .sortedWith(
                    compareByDescending<Pair<UserMemory, Double>> { it.second }
                        .thenByDescending { it.first.updatedAt }
                )
                .map { it.first }
        }
        return (stable + scored).distinctBy(UserMemory::id).take(limit)
    }

    fun forget(existing: List<UserMemory>, query: String): List<UserMemory> {
        val clean = query.trim()
        if (clean.isBlank()) return existing
        return existing.filterNot { memory ->
            memory.text.contains(clean, ignoreCase = true) || similarity(memory.text, clean) >= 0.75
        }
    }

    private fun compact(memories: List<UserMemory>): List<UserMemory> =
        memories
            .sortedWith(compareByDescending<UserMemory> { it.importance }.thenByDescending { it.updatedAt })
            .take(maxEntries)

    private fun sameDirectiveTopic(a: String, b: String): Boolean {
        val left = directiveTopic(a)
        val right = directiveTopic(b)
        return left.isNotBlank() && left == right
    }

    private fun directiveTopic(text: String): String {
        val stripped = text.lowercase(Locale.ROOT)
            .replace(
                Regex(
                    "^(remember that|remember|from now on|i prefer|i like|i don't like|i do not like|i hate|i want you to|i don't want you to|i do not want you to|please always|always|never|do not ever|don't ever)\\s+"
                ),
                ""
            )
        return tokens(stripped).firstOrNull().orEmpty()
    }

    private fun similarity(a: String, b: String): Double {
        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.union(right).size.toDouble()
    }

    private fun tokens(text: String): Set<String> =
        text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()

    companion object {
        private val STOP_WORDS = setOf(
            "the", "and", "for", "that", "this", "with", "from", "you",
            "your", "are", "have", "has", "not", "but", "can", "will",
            "should", "would", "could", "please", "want", "like", "prefer"
        )
    }
}
