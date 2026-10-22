package com.codingagent.model

import com.codingagent.agent.ModelFailure
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Remembers which helpers are used up, and when each one can be called again.
 * The secret passwords are not stored here.
 */
class ProviderQuotaBook(private val file: File? = null) {
    private val lock = Any()
    private val providers = mutableMapOf<String, Gate>()
    private val models = mutableMapOf<String, Gate>()

    private class Gate {
        var remaining: Int? = null
        var exhaustedUntil: Long = 0L
        var keyRejected: Boolean = false
        var tooLargeStrikes: Int = 0
    }

    init {
        load()
    }

    fun isAvailable(providerId: String, modelId: String, now: Long): Boolean = synchronized(lock) {
        val provider = providers[providerId]
        if (provider?.keyRejected == true) return false
        if (provider != null && provider.exhaustedUntil > now) return false
        val model = models[key(providerId, modelId)]
        model == null || model.exhaustedUntil <= now
    }

    fun isKeyRejected(providerId: String): Boolean = synchronized(lock) {
        providers[providerId]?.keyRejected == true
    }

    fun forgetKeyRejections() {
        synchronized(lock) {
            providers.values.forEach { it.keyRejected = false }
            save()
        }
    }

    fun noteSuccess(providerId: String, modelId: String) {
        synchronized(lock) {
            models[key(providerId, modelId)]?.let { gate ->
                gate.exhaustedUntil = 0L
                gate.tooLargeStrikes = 0
            }
            save()
        }
    }

    fun noteHttp(providerId: String, modelId: String, code: Int, remaining: Int?, resetAt: Long?) {
        synchronized(lock) {
            val model = models.getOrPut(key(providerId, modelId)) { Gate() }
            if (remaining != null) model.remaining = remaining
            if (code != 413 && resetAt != null && (code == 429 || remaining == 0)) {
                model.exhaustedUntil = maxOf(model.exhaustedUntil, resetAt)
            }
            save()
        }
    }

    fun noteFailure(providerId: String, modelId: String, message: String, now: Long) {
        val headerReset = QuotaHeaders.resetFromMessage(message, now)
        val reset = headerReset ?: geminiDailyReset(providerId, message, now)
        synchronized(lock) {
            when {
                ModelFailure.isOutOfFunds(message) -> {
                    providers.getOrPut(providerId) { Gate() }.exhaustedUntil = now + FUNDS_WAIT_MS
                }
                ModelFailure.isNetwork(message) -> Unit
                ModelFailure.isRejectedModel(message) -> {
                    val model = models.getOrPut(key(providerId, modelId)) { Gate() }
                    model.exhaustedUntil = maxOf(model.exhaustedUntil, now + MODEL_PARK_MS)
                }
                ModelFailure.isUnavailableModel(message) -> {
                    val model = models.getOrPut(key(providerId, modelId)) { Gate() }
                    model.exhaustedUntil = maxOf(model.exhaustedUntil, now + ERROR_COOLDOWN_MS)
                }
                ModelFailure.isBadTurnOrder(message) -> {
                    val model = models.getOrPut(key(providerId, modelId)) { Gate() }
                    model.exhaustedUntil = maxOf(model.exhaustedUntil, now + ERROR_COOLDOWN_MS)
                }
                ModelFailure.isRequestTooLarge(message) -> {
                    val model = models.getOrPut(key(providerId, modelId)) { Gate() }
                    model.tooLargeStrikes += 1
                    if (model.tooLargeStrikes >= 2) {
                        model.exhaustedUntil = maxOf(model.exhaustedUntil, now + MODEL_PARK_MS)
                    }
                }
                ModelFailure.isRejectedKey(message) -> {
                    providers.getOrPut(providerId) { Gate() }.keyRejected = true
                }
                ModelFailure.isDailyFreeLimit(message) && providerId == "openrouter" -> {
                    providers.getOrPut(providerId) { Gate() }.exhaustedUntil = reset ?: now + UNKNOWN_DAILY_WAIT_MS
                }
                ModelFailure.isDailyFreeLimit(message) -> {
                    models.getOrPut(key(providerId, modelId)) { Gate() }.exhaustedUntil =
                        reset ?: now + UNKNOWN_DAILY_WAIT_MS
                }
                ModelFailure.isRateLimit(message) || ModelFailure.isCapacity(message) -> {
                    val until = reset ?: now + ModelFailure.waitSeconds(message) * 1000L
                    val model = models.getOrPut(key(providerId, modelId)) { Gate() }
                    model.exhaustedUntil = maxOf(model.exhaustedUntil, until)
                }
                else -> {
                    if (message.contains("Model HTTP")) {
                        val model = models.getOrPut(key(providerId, modelId)) { Gate() }
                        model.exhaustedUntil = maxOf(model.exhaustedUntil, now + ERROR_COOLDOWN_MS)
                    }
                }
            }
            save()
        }
    }

    fun earliestResumeAt(endpoints: List<ProviderEndpoint>, now: Long): Long? = synchronized(lock) {
        if (endpoints.any { isAvailableUnlocked(it.providerId, it.modelId, now) }) return null
        if (endpoints.all { providers[it.providerId]?.keyRejected == true }) return null
        endpoints.mapNotNull { blockedUntil(it.providerId, it.modelId) }
            .filter { it > now }
            .minOrNull()
    }

    fun remaining(providerId: String, modelId: String): Int? = synchronized(lock) {
        models[key(providerId, modelId)]?.remaining
    }

    private fun isAvailableUnlocked(providerId: String, modelId: String, now: Long): Boolean {
        val provider = providers[providerId]
        if (provider?.keyRejected == true) return false
        if (provider != null && provider.exhaustedUntil > now) return false
        val model = models[key(providerId, modelId)]
        return model == null || model.exhaustedUntil <= now
    }

    private fun blockedUntil(providerId: String, modelId: String): Long? {
        val providerUntil = providers[providerId]?.exhaustedUntil ?: 0L
        val modelUntil = models[key(providerId, modelId)]?.exhaustedUntil ?: 0L
        val until = maxOf(providerUntil, modelUntil)
        return until.takeIf { it > 0L }
    }

    private fun geminiDailyReset(providerId: String, message: String, now: Long): Long? {
        if (providerId != "gemini" || !ModelFailure.isDailyFreeLimit(message)) return null
        return QuotaHeaders.nextMidnightPacific(now)
    }

    private fun key(providerId: String, modelId: String) = "$providerId\n$modelId"

    private fun load() {
        val source = file ?: return
        if (!source.isFile) return
        runCatching {
            val root = JSONObject(source.readText())
            readGates(root.optJSONArray("providers"), providers)
            readGates(root.optJSONArray("models"), models)
        }
    }

    private fun readGates(array: JSONArray?, into: MutableMap<String, Gate>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            into[id] = Gate().apply {
                remaining = if (item.has("remaining")) item.optInt("remaining") else null
                exhaustedUntil = item.optLong("exhaustedUntil", 0L)
                keyRejected = item.optBoolean("keyRejected", false)
                tooLargeStrikes = item.optInt("tooLargeStrikes", 0)
            }
        }
    }

    private fun save() {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val root = JSONObject()
                .put("providers", writeGates(providers))
                .put("models", writeGates(models))
            target.writeText(root.toString())
        }
    }

    private fun writeGates(source: Map<String, Gate>): JSONArray {
        val array = JSONArray()
        source.forEach { (id, gate) ->
            val item = JSONObject()
                .put("id", id)
                .put("exhaustedUntil", gate.exhaustedUntil)
                .put("keyRejected", gate.keyRejected)
            if (gate.remaining != null) item.put("remaining", gate.remaining)
            if (gate.tooLargeStrikes > 0) item.put("tooLargeStrikes", gate.tooLargeStrikes)
            array.put(item)
        }
        return array
    }

    companion object {
        const val UNKNOWN_DAILY_WAIT_MS = 6L * 60L * 60L * 1000L
        const val ERROR_COOLDOWN_MS = 60_000L
        const val FUNDS_WAIT_MS = 24L * 60L * 60L * 1000L
        const val MODEL_PARK_MS = 6L * 60L * 60L * 1000L
    }
}

internal object QuotaHeaders {
    fun resetFromMessage(message: String, now: Long): Long? {
        val reset = Regex("(?i)X-RateLimit-Reset:\\s*([^\\s]+)").find(message)?.groupValues?.getOrNull(1)
        val retry = Regex("(?i)Retry-After:\\s*([^\\s]+)").find(message)?.groupValues?.getOrNull(1)
        return parseReset(reset, now) ?: parseReset(retry, now)
    }

    fun parseReset(raw: String?, now: Long): Long? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val numeric = trimmed.toLongOrNull() ?: return null
        return when {
            numeric > 10_000_000_000L -> numeric
            numeric > 1_000_000_000L -> numeric * 1000L
            else -> now + numeric * 1000L
        }
    }

    fun nextMidnightPacific(now: Long): Long {
        val zone = ZoneId.of("America/Los_Angeles")
        val local = Instant.ofEpochMilli(now).atZone(zone)
        return local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
