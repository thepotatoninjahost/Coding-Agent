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

    fun isRateLimit(message: String): Boolean {
        val lower = message.lowercase()
        return isCapacity(message) ||
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

    fun isRetryable(message: String): Boolean = isRateLimit(message) || isEmpty(message)

    fun waitSeconds(message: String): Int {
        val match = Regex("try again in ([0-9.]+)", RegexOption.IGNORE_CASE).find(message)
        val parsed = match?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt()
        if (parsed != null && parsed > 0) return parsed
        return if (isCapacity(message)) 12 else 20
    }

    fun humanize(message: String): String {
        val lower = message.lowercase()
        return when {
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
            else -> message
        }
    }
}
