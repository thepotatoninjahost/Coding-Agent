package com.codingagent.model

import java.util.concurrent.ConcurrentHashMap

/**
 * ONE JOB: Remember how much a helper can write, from the provider's model list.
 * When the list was never fetched, allow 4096 tokens instead of a tiny fixed cap.
 */
object ModelCaps {
    data class Entry(
        val contextLength: Int,
        val maxCompletion: Int,
        val expiresAtMillis: Long? = null
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val cutOffs = ConcurrentHashMap<String, Int>()
    private val tokensLeft = ConcurrentHashMap<String, Int>()
    private val tokenResetAt = ConcurrentHashMap<String, Long>()

    fun clear() {
        entries.clear()
        cutOffs.clear()
        tokensLeft.clear()
        tokenResetAt.clear()
    }

    fun remember(modelId: String, contextLength: Int, maxCompletion: Int, expiresAtMillis: Long? = null) {
        val id = modelId.trim()
        if (id.isBlank()) return
        entries[id.lowercase()] = Entry(contextLength, maxCompletion, expiresAtMillis)
    }

    fun noteCutOff(modelId: String) {
        val id = modelId.lowercase()
        cutOffs[id] = (cutOffs[id] ?: 0) + 1
    }

    fun demoteForLongWrites(modelId: String): Boolean = (cutOffs[modelId.lowercase()] ?: 0) >= 2

    fun noteTokens(modelId: String, remaining: Int?, resetAtMillis: Long?) {
        val id = modelId.lowercase()
        if (remaining != null) tokensLeft[id] = remaining
        if (resetAtMillis != null) tokenResetAt[id] = resetAtMillis
    }

    fun tokensLeft(modelId: String): Int? = tokensLeft[modelId.lowercase()]

    /** Milliseconds to wait when this helper cannot fit another edit. Zero means try it or switch. */
    fun tokenWaitMillis(modelId: String, needed: Int, now: Long): Long {
        val left = tokensLeft[modelId.lowercase()] ?: return 0L
        if (left >= needed) return 0L
        val reset = tokenResetAt[modelId.lowercase()] ?: return 0L
        val wait = reset - now
        return if (wait in 1..90_000) wait else 0L
    }

    fun maxOutput(modelId: String, writingFile: Boolean, promptTokens: Int = 0, expanded: Boolean = false): Int {
        val entry = entries[modelId.lowercase()]
        val providerCap = entry?.maxCompletion?.takeIf { it > 0 } ?: 4096
        val context = entry?.contextLength?.takeIf { it > 0 } ?: 32_768
        val room = (context - promptTokens - 512).coerceAtLeast(256)
        val cap = minOf(providerCap, room)
        val floor = if (writingFile || expanded) 1_500 else 256
        return cap.coerceAtLeast(minOf(floor, cap))
    }

    fun inputBudget(modelId: String, outputTokens: Int): Int {
        val context = entries[modelId.lowercase()]?.contextLength?.takeIf { it > 0 } ?: 32_768
        return (context - outputTokens - 256).coerceAtLeast(2_000)
    }
}
