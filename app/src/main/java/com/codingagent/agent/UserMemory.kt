package com.codingagent.agent

import java.util.Locale

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
        "i hate ",
        "i want you to ",
        "i don't want you to ",
        "i do not want you to ",
        "please always ",
        "always ",
        "never ",
        "do not ever ",
        "don't ever "
    )

    private val transientScope = Regex(
        "\b(for now|for this task|for this request|in this project|in this conversation|this time|today only|just this once)\b",
        RegexOption.IGNORE_CASE
    )

    private val secretPattern = Regex(
        "\b(api[ _-]?key|access[ _-]?token|password|passphrase|secret|private[ _-]?key|credit[ _-]?card)\b",
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
                lower.contains("explain") -> UserMemory.Kind.WORKFLOW
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
