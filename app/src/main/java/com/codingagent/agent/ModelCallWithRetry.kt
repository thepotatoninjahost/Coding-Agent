package com.codingagent.agent

import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse

/**
 * ONE JOB: Call the model gateway, with automatic wait+retry on a transient failure
 * (provider capacity / rate limit or empty response).
 *
 * Returns null when the caller's cancellation check fires or the wait is interrupted.
 */
object ModelCallWithRetry {
    private const val MAX_RETRIES = 3
    private const val WAIT_SLICE_MILLIS = 250L

    fun call(
        gateway: ModelGateway,
        request: () -> ModelRequest,
        isCancelled: () -> Boolean,
        onPhase: (String) -> Unit,
        onDelta: (String) -> Unit = {}
    ): ModelResponse? {
        if (isCancelled()) {
            gateway.cancel()
            return null
        }
        var response = gateway.stream(request(), onDelta)
        var resumes = 0
        while (
            response is ModelResponse.Failure &&
            ModelFailure.isAllProvidersExhausted(response.message) &&
            !ModelFailure.isNoAutoResume(response.message) &&
            resumes < 3
        ) {
            onPhase(ModelFailure.humanize(response.message))
            val waitMillis = ModelFailure.resumeDelayMillis(response.message)
            if (waitMillis <= 0L) break
            if (!waitCancellable(waitMillis, isCancelled)) return null
            if (isCancelled()) {
                gateway.cancel()
                return null
            }
            resumes++
            response = gateway.stream(request(), onDelta)
        }
        if (response is ModelResponse.Failure && ModelFailure.isAllProvidersExhausted(response.message)) {
            onPhase(ModelFailure.humanize(response.message))
            return response
        }
        var attempt = 0
        while (
            response is ModelResponse.Failure &&
            ModelFailure.isRetryable(response.message) &&
            attempt < MAX_RETRIES
        ) {
            attempt++
            if (ModelFailure.isRateLimit(response.message)) {
                val waitMillis = (ModelFailure.waitSeconds(response.message) * attempt)
                    .coerceIn(1, 45) * 1000L
                val waitSeconds = waitMillis / 1000L
                onPhase(
                    if (ModelFailure.isCapacity(response.message)) {
                        "The helper is busy. Waiting $waitSeconds seconds, then trying again ($attempt of $MAX_RETRIES)."
                    } else {
                        "The helper asked me to slow down. Waiting $waitSeconds seconds, then trying again ($attempt of $MAX_RETRIES)."
                    }
                )
                if (!waitCancellable(waitMillis, isCancelled)) return null
            } else {
                onPhase("The helper sent an empty answer. Trying again ($attempt of $MAX_RETRIES).")
                if (isCancelled()) return null
            }
            if (isCancelled()) {
                gateway.cancel()
                return null
            }
            response = gateway.stream(request(), onDelta)
        }
        return response
    }

    private fun waitCancellable(waitMillis: Long, isCancelled: () -> Boolean): Boolean {
        var remaining = waitMillis
        while (remaining > 0L) {
            if (isCancelled()) return false
            val slice = minOf(WAIT_SLICE_MILLIS, remaining)
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            remaining -= slice
        }
        return !isCancelled()
    }
}
