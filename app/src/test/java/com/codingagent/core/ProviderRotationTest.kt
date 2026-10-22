package com.codingagent.core

import com.codingagent.agent.ModelCallWithRetry
import com.codingagent.agent.ModelFailure
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse
import com.codingagent.model.ModelToolDefinition
import com.codingagent.model.ProviderEndpoint
import com.codingagent.model.ProviderQuotaBook
import com.codingagent.model.ProviderRotatingGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ProviderRotationTest {
    private val request = ModelRequest("system", "user", emptyList<ModelToolDefinition>())

    @Test
    fun openRouterDailyLimitSkipsThatProviderAndKeepsGoing() {
        val clock = AtomicLong(1_700_000_000_000L)
        val firstOpenRouter = AtomicInteger()
        val secondOpenRouter = AtomicInteger()
        val groq = AtomicInteger()
        val gateway = gateway(
            clock,
            ranked("minimax/minimax-m3.1-flash:free", "cohere/north-mini-code:free", "openai/gpt-oss-120b"),
            endpoint("openrouter", "minimax/minimax-m3.1-flash:free", "or-secret") to fake {
                firstOpenRouter.incrementAndGet()
                ModelResponse.Failure("Model HTTP 429: free-models-per-day\nX-RateLimit-Reset: ${clock.get() / 1000 + 3600}")
            },
            endpoint("openrouter", "cohere/north-mini-code:free", "or-secret") to fake {
                secondOpenRouter.incrementAndGet()
                ModelResponse.Text("should not run")
            },
            endpoint("groq", "openai/gpt-oss-120b", "groq-secret") to fake {
                groq.incrementAndGet()
                ModelResponse.Text("kept going")
            }
        )

        val first = gateway.complete(request) as ModelResponse.Text
        assertEquals("kept going", first.content)
        assertEquals(1, firstOpenRouter.get())
        assertEquals(0, secondOpenRouter.get())
        assertEquals(1, groq.get())
        assertFalse(first.content.contains("or-secret"))

        val second = gateway.complete(request) as ModelResponse.Text
        assertEquals("kept going", second.content)
        assertEquals(1, firstOpenRouter.get())
        assertEquals(0, secondOpenRouter.get())
        assertEquals(2, groq.get())

        clock.addAndGet(3_600_000L + 1)
        gateway.complete(request)
        assertEquals(2, firstOpenRouter.get())
        assertEquals(0, secondOpenRouter.get())
        assertEquals(3, groq.get())
    }

    @Test
    fun everyHelperUsedUpSaysWhenWorkResumesAndABadKeyDoesNot() {
        val clock = AtomicLong(1_700_000_000_000L)
        val calls = AtomicInteger()
        val resetSeconds = clock.get() / 1000 + 7200
        val usedUp = gateway(
            clock,
            ranked("minimax/minimax-m3.1-flash:free"),
            endpoint("openrouter", "minimax/minimax-m3.1-flash:free", "or-secret") to fake {
                calls.incrementAndGet()
                ModelResponse.Failure("Model HTTP 429: free-models-per-day\nX-RateLimit-Reset: $resetSeconds")
            }
        )
        val exhausted = usedUp.complete(request) as ModelResponse.Failure
        assertTrue(exhausted.message.contains("ALL_HELPERS_EXHAUSTED"))
        assertTrue(exhausted.message.contains("resume-at-ms:"))
        val plain = ModelFailure.humanize(exhausted.message)
        assertTrue(plain.contains("used up"))
        assertTrue(plain.contains("1,000"))
        assertTrue(plain.contains("$10"))
        assertFalse(plain.contains("X-RateLimit"))
        assertFalse(plain.contains("or-secret"))

        val rejectedCalls = AtomicInteger()
        val rejected = gateway(
            clock,
            ranked("openai/gpt-oss-120b"),
            endpoint("groq", "openai/gpt-oss-120b", "groq-secret") to fake {
                rejectedCalls.incrementAndGet()
                ModelResponse.Failure("Model HTTP 401: invalid api key groq-secret")
            }
        )
        val refused = rejected.complete(request) as ModelResponse.Failure
        assertTrue(refused.message.contains("no-auto-resume"))
        assertTrue(ModelFailure.humanize(refused.message).contains("secret password"))
        assertFalse(refused.message.contains("groq-secret"))
        rejected.complete(request)
        assertEquals(1, rejectedCalls.get())
    }

    @Test
    fun remainingQuotaIsRemembered() {
        val quota = ProviderQuotaBook()
        quota.noteHttp("groq", "openai/gpt-oss-120b", 200, 17, null)
        assertEquals(17, quota.remaining("groq", "openai/gpt-oss-120b"))
    }

    @Test
    fun dailyLimitIsOneCallAndAllHelpersExhaustedWaitsThenTriesAgain() {
        val dailyCalls = AtomicInteger()
        val daily = object : ModelGateway {
            override fun complete(request: ModelRequest) = stream(request) {}
            override fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse {
                dailyCalls.incrementAndGet()
                return ModelResponse.Failure("Model HTTP 429: free-models-per-day")
            }
        }
        ModelCallWithRetry.call(daily, { request }, { false }, {})
        assertEquals(1, dailyCalls.get())

        val resumeAt = System.currentTimeMillis() + 200
        val resumed = AtomicInteger()
        val waiting = object : ModelGateway {
            override fun complete(request: ModelRequest) = stream(request) {}
            override fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse {
                val n = resumed.incrementAndGet()
                return if (n == 1) {
                    ModelResponse.Failure("ALL_HELPERS_EXHAUSTED\nresume-at-ms:$resumeAt\nopenrouter")
                } else {
                    ModelResponse.Text("back to work")
                }
            }
        }
        val response = ModelCallWithRetry.call(waiting, { request }, { false }, {})
        assertTrue(response is ModelResponse.Text)
        assertEquals("back to work", (response as ModelResponse.Text).content)
        assertEquals(2, resumed.get())
    }

    private fun ranked(vararg ids: String): () -> List<String> = { ids.toList() }

    private fun endpoint(providerId: String, modelId: String, key: String) = ProviderEndpoint(
        providerId = providerId,
        plainName = providerId,
        modelId = modelId,
        baseUrl = "https://example.com/v1",
        apiKey = key
    )

    private fun gateway(
        clock: AtomicLong,
        ranking: () -> List<String>,
        vararg installed: Pair<ProviderEndpoint, ModelGateway>
    ) = ProviderRotatingGateway(
        endpoints = installed.map { it.first },
        quota = ProviderQuotaBook(),
        ranking = ranking,
        clock = { clock.get() },
        installed = installed.associate { (endpoint, gateway) ->
            endpoint.providerId + "\n" + endpoint.modelId to gateway
        }
    )

    private fun fake(reply: () -> ModelResponse) = object : ModelGateway {
        override fun complete(request: ModelRequest) = reply()
        override fun stream(request: ModelRequest, onDelta: (String) -> Unit) = reply()
    }
}
