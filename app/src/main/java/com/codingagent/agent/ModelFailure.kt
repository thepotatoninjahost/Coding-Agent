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
                "Provider worker pool is full (ResourceExhausted / HTTP 503). " +
                    "This is a concurrent-request cap on the model host (Nvidia NIM often 16/16), not a bug in your prompt. " +
                    "Wait ~${wait}s and send the request once. Rapid retries make the cap worse. " +
                    "If it keeps happening, switch model or provider in Model settings."
            }
            isRateLimit(message) -> {
                val wait = waitSeconds(message)
                "Model rate-limited (tokens/minute). Wait ~${wait}s, or switch provider in Model settings. " +
                    "Local file evidence still available via inspect/read."
            }
            isEmpty(message) ->
                "Model returned an empty response. Retrying is automatic; if it keeps happening, switch model in Model settings."
            "401" in lower || "unauthorized" in lower || "invalid api key" in lower ->
                "Model auth failed (check API key in Model settings)."
            "403" in lower || "forbidden" in lower ->
                "Model request forbidden (provider rejected the key or model)."
            "timeout" in lower || "timed out" in lower ->
                "Model request timed out. Retry once; if it keeps happening, shorten the request or switch provider."
            "connection" in lower || "unreachable" in lower || "unknownhost" in lower ->
                "Could not reach the model endpoint (network)."
            message.length > 280 -> message.take(280) + "…"
            else -> message
        }
    }
}
