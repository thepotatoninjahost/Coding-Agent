package com.codingagent.agent

import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse

/**
 * ONE JOB: Call the model gateway, with one automatic wait+retry on a transient failure
 * (provider rate limit or empty response) — extracted verbatim out of AutonomousAgent.kt's
 * turn loop, no behavior change.
 *
 * Returns null only when the caller's cancellation check fires between the wait and the
 * retry; the caller is expected to stop the run in that case (same as before extraction).
 */
object ModelCallWithRetry {
    fun call(
        gateway: ModelGateway,
        request: () -> ModelRequest,
        isCancelled: () -> Boolean,
        onPhase: (String) -> Unit
    ): ModelResponse? {
        var response = gateway.complete(request())
        // Retry must keep the same tool policy as this turn — the caller's `request` lambda
        // is responsible for that; this function only decides whether/how long to wait.
        if (response is ModelResponse.Failure && (ModelFailure.isRateLimit(response.message) || ModelFailure.isEmpty(response.message))) {
            if (ModelFailure.isRateLimit(response.message)) {
                val waitSec = ModelFailure.waitSeconds(response.message).coerceIn(1, 45)
                onPhase("Rate limited — waiting ${waitSec}s then retrying once")
                try {
                    Thread.sleep(waitSec * 1000L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            } else {
                onPhase("Empty model response — retrying once")
            }
            if (isCancelled()) return null
            response = gateway.complete(request())
        }
        return response
    }
}
