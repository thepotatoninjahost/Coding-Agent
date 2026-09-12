package com.codingagent.agent

import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse

/**
 * ONE JOB: Call the model gateway, with automatic wait+retry on a transient failure
 * (provider capacity / rate limit or empty response).
 *
 * Returns null only when the caller's cancellation check fires between the wait and the
 * retry; the caller is expected to stop the run in that case.
 */
object ModelCallWithRetry {
    fun call(
        gateway: ModelGateway,
        request: () -> ModelRequest,
        isCancelled: () -> Boolean,
        onPhase: (String) -> Unit
    ): ModelResponse? {
        var response = gateway.complete(request())
        var attempt = 0
        while (
            response is ModelResponse.Failure &&
            ModelFailure.isRetryable(response.message) &&
            attempt < 3
        ) {
            attempt++
            if (ModelFailure.isRateLimit(response.message)) {
                val waitSec = (ModelFailure.waitSeconds(response.message) * attempt).coerceIn(1, 45)
                onPhase(
                    if (ModelFailure.isCapacity(response.message)) {
                        "Provider at capacity — waiting ${waitSec}s then retry $attempt/3"
                    } else {
                        "Rate limited — waiting ${waitSec}s then retry $attempt/3"
                    }
                )
                try {
                    Thread.sleep(waitSec * 1000L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            } else {
                onPhase("Empty model response — retry $attempt/3")
            }
            if (isCancelled()) return null
            response = gateway.complete(request())
        }
        return response
    }
}
