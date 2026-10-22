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

    fun gaveUpResume(message: String): Boolean = "resume-gave-up" in message.lowercase()

    fun isRejectedKey(message: String): Boolean {
        val lower = message.lowercase()
        return "401" in lower ||
            "unauthorized" in lower ||
            "invalid api key" in lower ||
            "missing authentication" in lower ||
            "no auth credentials" in lower
    }

    fun isRequestTooLarge(message: String): Boolean {
        val lower = message.lowercase()
        return "413" in lower ||
            "request too large" in lower ||
            "context_length_exceeded" in lower ||
            "context length" in lower ||
            "maximum context" in lower ||
            "reduce the length" in lower
    }

    fun isOutOfFunds(message: String): Boolean {
        val lower = message.lowercase()
        return "out of funds" in lower ||
            "insufficient_quota" in lower ||
            "insufficient quota" in lower ||
            "insufficient balance" in lower ||
            (("402" in lower || "403" in lower) &&
                ("fund" in lower || "credit" in lower || "balance" in lower || "quota" in lower))
    }

    fun isRateLimit(message: String): Boolean {
        if (isRequestTooLarge(message) || isOutOfFunds(message)) return false
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
     * A daily limit, a busy helper, a request that is too big, or an empty account
     * is not retried on the same call. A short "slow down" (429) is, because a
     * single custom address has no other helper to switch to. An empty answer is too.
     */
    fun isRetryable(message: String): Boolean {
        if (isDailyFreeLimit(message) || isAllProvidersExhausted(message) || isCapacity(message)) return false
        if (isRequestTooLarge(message) || isOutOfFunds(message) || isRejectedKey(message)) return false
        return isEmpty(message) || isShortRateLimit(message)
    }

    fun isShortRateLimit(message: String): Boolean {
        if (isCapacity(message) || isDailyFreeLimit(message) || isRequestTooLarge(message)) return false
        if (isOutOfFunds(message) || isRejectedKey(message)) return false
        val lower = message.lowercase()
        return "429" in lower ||
            "too many requests" in lower ||
            "rate_limit" in lower ||
            "rate limit" in lower ||
            "tokens per minute" in lower ||
            "tpm" in lower
    }

    fun resumeDelayMillis(message: String, now: Long = System.currentTimeMillis()): Long {
        if (!isAllProvidersExhausted(message) || isNoAutoResume(message)) return 0L
        val match = Regex("resume-at-ms:(\\d+)").find(message) ?: return 0L
        val at = match.groupValues[1].toLongOrNull() ?: return 0L
        return (at - now).coerceAtLeast(0L)
    }

    fun waitSeconds(message: String): Int {
        val match = Regex("try again in ([0-9.]+)", RegexOption.IGNORE_CASE).find(message)
        val parsed = match?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt()
        if (parsed != null && parsed > 0) return parsed.coerceAtMost(120)
        if (isRequestTooLarge(message) || isOutOfFunds(message)) return 0
        if (isCapacity(message)) return 12
        if (isRateLimit(message)) return 60
        return 20
    }

    fun humanize(message: String): String {
        val lower = ownerText(message).lowercase()
        return when {
            isAllProvidersExhausted(message) && isNoAutoResume(message) ->
                "Every helper refused the secret password. Tap Setup and paste it again. I will not keep trying until you do."
            isAllProvidersExhausted(message) && gaveUpResume(message) -> {
                val whenText = resumePhrase(message)
                val credit = creditNote(message)
                "Every helper is used up until $whenText. I stopped this task. Send the request again then.$credit"
            }
            isAllProvidersExhausted(message) -> {
                val whenText = resumePhrase(message)
                val credit = creditNote(message)
                "Every helper is used up for now. I will start again $whenText. You can leave this open. You do not need to send the request again.$credit"
            }
            isOutOfFunds(message) ->
                "This helper is out of funds. I will skip it until tomorrow and use another helper if one is connected."
            isRequestTooLarge(message) ->
                "This request is too big for that helper. I will shorten it, or try a helper that can take more."
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
                    trimmed == "resume-gave-up" ||
                    trimmed == "openrouter"
            }
            .joinToString("\n")
            .trim()
            .ifBlank { message }
            .let(::hideAccountAndPassword)

    private fun hideAccountAndPassword(text: String): String =
        text.replace(Regex("""Bearer\s+\S+""", RegexOption.IGNORE_CASE), "Bearer [redacted]")
            .replace(Regex("""/accounts/[a-fA-F0-9]{32}"""), "/accounts/[account]")

    private fun creditNote(message: String): String =
        if ("openrouter" in message.lowercase() || isDailyFreeLimit(message)) {
            " Adding \$10 of credit at openrouter.ai raises the OpenRouter free limit to 1,000 requests a day."
        } else {
            ""
        }

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
