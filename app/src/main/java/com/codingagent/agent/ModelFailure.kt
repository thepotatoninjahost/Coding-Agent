package com.codingagent.agent

/**
 * ONE JOB: Turn raw model/provider errors into one short owner-facing line.
 */
object ModelFailure {
    fun isCapacity(message: String): Boolean {
        val lower = message.lowercase()
        return "resourceexhausted" in lower ||
            "resource exhausted" in lower ||
            "worker local" in lower ||
            "request limit reached" in lower ||
            "overloaded" in lower ||
            "503" in lower ||
            "service unavailable" in lower ||
            "capacity" in lower
    }

    fun isDailyFreeLimit(message: String): Boolean {
        val lower = message.lowercase()
        return "free-models-per-day" in lower ||
            "openrouter_free_tier_daily" in lower ||
            "requests per day" in lower ||
            "tokens per day" in lower ||
            "daily limit" in lower ||
            "per-day" in lower ||
            ("quota" in lower && "day" in lower)
    }

    fun isAllProvidersExhausted(message: String): Boolean = "ALL_HELPERS_EXHAUSTED" in message

    fun isNoAutoResume(message: String): Boolean = "no-auto-resume" in message.lowercase()

    fun isRejectedKey(message: String): Boolean {
        val lower = message.lowercase()
        return "401" in lower ||
            "unauthorized" in lower ||
            "invalid api key" in lower ||
            "missing authentication" in lower ||
            "no auth credentials" in lower
    }

    fun isRateLimit(message: String): Boolean {
        val lower = message.lowercase()
        return isCapacity(message) ||
            isDailyFreeLimit(message) ||
            "rate_limit" in lower ||
            "rate limit" in lower ||
            "tokens per minute" in lower ||
            "tpm" in lower ||
            "429" in lower ||
            "too many requests" in lower
    }

    fun isEmpty(message: String): Boolean {
        val lower = message.lowercase()
        return "no streamed message content" in lower ||
            "no message content" in lower ||
            "empty response" in lower ||
            "returned no message" in lower ||
            "no usable message content" in lower ||
            "did not contain content or tool" in lower ||
            "returned an empty response" in lower
    }

    /**
     * A rate limit or a daily limit is not retried on the same helper.
     * The provider router switches to the next helper instead.
     * An empty answer is still tried once more.
     */
    fun isRetryable(message: String): Boolean =
        !isDailyFreeLimit(message) && !isAllProvidersExhausted(message) && isEmpty(message)

    fun resumeDelayMillis(message: String, now: Long = System.currentTimeMillis()): Long {
        if (!isAllProvidersExhausted(message) || isNoAutoResume(message)) return 0L
        val match = Regex("resume-at-ms:(\\d+)").find(message) ?: return 0L
        val at = match.groupValues[1].toLongOrNull() ?: return 0L
        return (at - now).coerceAtLeast(0L)
    }

    fun waitSeconds(message: String): Int {
        val match = Regex("try again in ([0-9.]+)", RegexOption.IGNORE_CASE).find(message)
        val parsed = match?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt()
        if (parsed != null && parsed > 0) return parsed
        return if (isCapacity(message)) 12 else 20
    }

    fun humanize(message: String): String {
        val lower = ownerText(message).lowercase()
        return when {
            isAllProvidersExhausted(message) && isNoAutoResume(message) ->
                "Every helper refused the secret password. Tap Setup and paste it again. I will not keep trying until you do."
            isAllProvidersExhausted(message) -> {
                val whenText = resumePhrase(message)
                val credit = if ("openrouter" in message.lowercase() || isDailyFreeLimit(message)) {
                    " Adding \$10 of credit at openrouter.ai raises the OpenRouter free limit to 1,000 requests a day."
                } else {
                    ""
                }
                "Every helper is used up for now. I will start again $whenText. You do not need to send the request again.$credit"
            }
            isDailyFreeLimit(message) -> {
                val whenText = resetPhrase(message)
                val credit = if ("openrouter" in message.lowercase() || "free-models-per-day" in message.lowercase() || "openrouter_free_tier_daily" in message.lowercase()) {
                    " Adding \$10 of credit at openrouter.ai raises that helper's free limit to 1,000 requests a day."
                } else {
                    ""
                }
                "Today's free limit is used up. $whenText$credit I will use another helper if one is connected, and I will not keep calling this one until it resets."
            }
            isCapacity(message) -> {
                val wait = waitSeconds(message)
                "The helper is too busy right now. Wait about $wait seconds, then send your request once. " +
                    "Sending it again and again makes the wait longer. If this keeps happening, tap Setup and try a different helper."
            }
            isRateLimit(message) -> {
                val wait = waitSeconds(message)
                "The helper asked me to slow down. The free limit may be used up. Wait about $wait seconds, then try once. " +
                    "Sending it again and again makes the wait longer. You can pick another free helper in Setup. " +
                    "I can still list files and read a file you name."
            }
            isEmpty(message) ->
                "The helper sent an empty answer. I try again automatically. If this keeps happening, tap Setup and try a different helper."
            "401" in lower || "unauthorized" in lower || "invalid api key" in lower ||
                "missing authentication" in lower || "no auth credentials" in lower ->
                "The secret password was rejected. Tap Setup and paste it again as one line. " +
                    "For OpenRouter, get the key at openrouter.ai/keys. It stays on this phone."
            "403" in lower || "forbidden" in lower ->
                "The helper refused this request. The secret password or the helper name may be wrong. Check Setup."
            "timeout" in lower || "timed out" in lower ->
                "The helper took too long to answer. Try once more. If it keeps happening, ask for something smaller or pick a different helper in Setup."
            "connection" in lower || "unreachable" in lower || "unknownhost" in lower ->
                "I could not reach the helper. Check that the phone has internet, then try again."
            else -> ownerText(message)
        }
    }

    private fun ownerText(message: String): String =
        message.lineSequence()
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.startsWith("X-RateLimit-", ignoreCase = true) ||
                    trimmed.startsWith("Retry-After:", ignoreCase = true) ||
                    trimmed.startsWith("resume-at-ms:") ||
                    trimmed == "ALL_HELPERS_EXHAUSTED" ||
                    trimmed == "no-auto-resume" ||
                    trimmed == "openrouter"
            }
            .joinToString("\n")
            .trim()
            .ifBlank { message }
            .let(::hideAccountAndPassword)

    private fun hideAccountAndPassword(text: String): String =
        text.replace(Regex("""Bearer\s+\S+""", RegexOption.IGNORE_CASE), "Bearer [redacted]")
            .replace(Regex("""/accounts/[a-fA-F0-9]{32}"""), "/accounts/[account]")

    private fun resetPhrase(message: String): String {
        val epoch = com.codingagent.model.QuotaHeaders.resetFromMessage(message, System.currentTimeMillis())
        return if (epoch == null) {
            "I could not read when it resets."
        } else {
            "It resets ${formatWhen(epoch)} (your phone's time)."
        }
    }

    private fun resumePhrase(message: String): String {
        val epoch = Regex("resume-at-ms:(\\d+)").find(message)?.groupValues?.getOrNull(1)?.toLongOrNull()
        return if (epoch == null) "as soon as a helper is free" else formatWhen(epoch) + " (your phone's time)"
    }

    private fun formatWhen(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val zone = java.time.ZoneId.systemDefault()
        val moment = java.time.Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val clock = moment.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
        val day = when (moment.toLocalDate()) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> moment.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
        }
        return "$day at $clock"
    }
}
