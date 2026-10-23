package com.codingagent.core

import java.net.HttpURLConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.codingagent.model.ModelRequest
import com.codingagent.model.JsonModelResponseParser
import com.codingagent.model.ModelResponse
import com.codingagent.model.ModelToolDefinition
import com.codingagent.model.RemoteHttpGateway
import com.codingagent.model.RotatingModelGateway

class ModelGatewayTest {

    @Test
    fun reasoningOnlyJsonIsNotReturnedAsUserFacingText() {
        val result = JsonModelResponseParser().parse("""{"reasoning_content":"private reasoning"}""")
        assertTrue(result is ModelResponse.Failure)
    }


    @Test
    fun reasoningPartsInContentArraysAreNotShownAsTheAnswer() {
        val gateway = RemoteHttpGateway("http://127.0.0.1:8080/v1", "", "local", connectionFactory = { _ ->
            fakeConnection("""{"choices":[{"message":{"content":[{"type":"analysis","text":"private reasoning"},{"type":"text","text":"user-facing answer"}]}}]}""")
        })
        val result = gateway.complete(ModelRequest("system", "question", emptyList()))
        assertTrue(result is ModelResponse.Text)
        assertEquals("user-facing answer", (result as ModelResponse.Text).content)
    }

    @Test
    fun providerLengthFinishReasonRejectsPartialNonStreamingContent() {
        val gateway = RemoteHttpGateway("http://127.0.0.1:8080/v1", "", "local", connectionFactory = { _ ->
            fakeConnection("""{"choices":[{"finish_reason":"length","message":{"content":"partial answer"}}]}""")
        })
        val result = gateway.complete(ModelRequest("system", "question", emptyList()))
        assertTrue(result is ModelResponse.Failure)
        assertTrue((result as ModelResponse.Failure).message.contains("cut off"))
        assertFalse((result as ModelResponse.Failure).message.contains("finish_reason"))
    }

    @Test
    fun providerLengthFinishReasonRejectsPartialStreamingContent() {
        val gateway = RemoteHttpGateway("http://127.0.0.1:8080/v1", "", "local", connectionFactory = { _ ->
            fakeConnection("""data: {"choices":[{"delta":{"content":"partial answer"}}]}
data: {"choices":[{"delta":{},"finish_reason":"length"}]}
data: [DONE]
""".trimIndent())
        })
        val result = gateway.stream(ModelRequest("system", "question", emptyList())) {}
        assertTrue(result is ModelResponse.Failure)
        assertTrue((result as ModelResponse.Failure).message.contains("cut off"))
        assertFalse((result as ModelResponse.Failure).message.contains("finish_reason"))
    }

    @Test
    fun malformedStreamEventRejectsPreviouslyAccumulatedPartialContent() {
        val gateway = RemoteHttpGateway("http://127.0.0.1:8080/v1", "", "local", connectionFactory = { _ ->
            fakeConnection("""data: {"choices":[{"delta":{"content":"partial answer"}}]}
data: {malformed-json}
data: [DONE]
""".trimIndent())
        })

        val result = gateway.stream(ModelRequest("system", "question", emptyList())) {}

        assertTrue(result is ModelResponse.Failure)
        assertTrue((result as ModelResponse.Failure).message.contains("partial content was rejected"))
    }

    @Test
    fun multipleToolCallsAreRejectedRatherThanSilentlyDroppingCalls() {
        val gateway = RemoteHttpGateway("http://127.0.0.1:8080/v1", "", "local", connectionFactory = { _ ->
            fakeConnection("""{"choices":[{"message":{"tool_calls":[{"id":"call_1","function":{"name":"read_file","arguments":"{}"}},{"function":{"name":"search_project","arguments":"{}"}}]}}]}""")
        })
        val result = gateway.complete(ModelRequest("system", "inspect", listOf(ModelToolDefinition("read_file", "read", "{}"))))
        assertTrue(result is ModelResponse.ToolCall)
        val call = result as ModelResponse.ToolCall
        assertEquals("read_file", call.name)
        assertTrue(call.thought.contains("next turn"))
    }

    @Test
    fun freeRouterPinsTheFirstCapableModelForLaterSteps() {
        val bodies = mutableListOf<String>()
        var calls = 0
        val gateway = RemoteHttpGateway("https://openrouter.ai/api/v1", "test-secret-value", "openrouter/free", connectionFactory = { _ ->
            calls++
            val body = if (calls == 1) {
                """{"model":"cohere/north-mini-code:free","choices":[{"message":{"content":"first"}}]}"""
            } else {
                """{"model":"liquid/lfm-2.5-2.6b:free","choices":[{"message":{"content":"second"}}]}"""
            }
            fakeConnection(body, onRequest = { captured -> bodies += captured })
        })
        gateway.complete(ModelRequest("system", "one", emptyList()))
        gateway.complete(ModelRequest("system", "two", emptyList()))
        assertTrue(bodies[0].contains("\"model\":\"openrouter/free\""))
        assertTrue(bodies[1].contains("\"model\":\"cohere/north-mini-code:free\""))
        assertFalse(bodies[1].contains("liquid/lfm"))
    }

    @Test
    fun `cancellation prevents model rotation`() {
        var firstCalls = 0
        var secondCalls = 0
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val first = object : com.codingagent.model.ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse {
                firstCalls++
                entered.countDown()
                cancelled.await(2, TimeUnit.SECONDS)
                return ModelResponse.Failure("429 rate limit")
            }
            override fun cancel() {
                cancelled.countDown()
            }
        }
        val second = object : com.codingagent.model.ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse {
                secondCalls++
                return ModelResponse.Text("should not run")
            }
            override fun cancel() = Unit
        }
        val gateway = RotatingModelGateway(
            listOf(
                RotatingModelGateway.Entry("first", first),
                RotatingModelGateway.Entry("second", second)
            )
        )
        val resultHolder = arrayOfNulls<ModelResponse>(1)
        val worker = Thread {
            resultHolder[0] = gateway.complete(ModelRequest("system", "test", emptyList()))
        }
        worker.start()

        assertTrue("first model was never invoked", entered.await(2, TimeUnit.SECONDS))
        gateway.cancel()

        worker.join(2_000)
        assertTrue("rotation did not stop after cancellation", !worker.isAlive)
        assertEquals(ModelResponse.Failure("Cancelled"), resultHolder[0])
        assertEquals(1, firstCalls)
        assertEquals(0, secondCalls)
    }

    @Test
    fun longSystemAndUserPromptsReachTheProviderWithoutTruncation() {
        var requestBody = ""
        val systemPrompt = "SYSTEM-START-" + "s".repeat(900) + "-SYSTEM-END-SENTINEL"
        val userPrompt = "USER-START-" + "u".repeat(1_200) + "-USER-END-SENTINEL"
        val gateway = RemoteHttpGateway("http://127.0.0.1:8080/v1", "", "local", connectionFactory = { _ ->
            fakeConnection("""{"choices":[{"message":{"content":"ok"}}]}""", onRequest = { requestBody = it })
        })

        val result = gateway.complete(ModelRequest(systemPrompt, userPrompt, emptyList()))

        assertTrue(result is ModelResponse.Text)
        assertTrue("complete system prompt must reach provider", requestBody.contains(systemPrompt))
        assertTrue("complete user prompt must reach provider", requestBody.contains(userPrompt))
    }

    @Test
    fun `remote http request sends tool schemas and parses a tool call`() {
        var requestBody = ""
        val gateway = RemoteHttpGateway("http://127.0.0.1:8080/v1", "", "local", connectionFactory = { _ ->
            fakeConnection("""
                {"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"call_1","index":0,"function":{"name":"read_file","arguments":"{\"path\":\"src/Main.kt\"}"}}]}}]}
            """.trimIndent(), onRequest = { requestBody = it })
        })

        val result = gateway.complete(ModelRequest("system", "inspect", listOf(ModelToolDefinition("read_file", "read", "{\"type\":\"object\"}"))))

        assertTrue(requestBody.contains("\"tools\""))
        assertTrue(requestBody.contains("\"name\":\"read_file\""))
        assertEquals(ModelResponse.ToolCall("read_file", "{\"path\":\"src/Main.kt\"}", "", "call_1"), result)
    }

    @Test
    fun `cancellation prevents empty-response retry`() {
        var connections = 0
        lateinit var gateway: RemoteHttpGateway
        gateway = RemoteHttpGateway(
            "http://127.0.0.1:8080/v1", "", "local",
            connectionFactory = {
                connections++
                fakeConnection("", onRequest = { gateway.cancel() })
            }
        )

        val result = gateway.complete(
            ModelRequest(
                "system",
                "inspect",
                listOf(ModelToolDefinition("read_file", "read", "{\"type\":\"object\"}"))
            )
        )

        assertEquals(ModelResponse.Failure("Cancelled"), result)
        assertEquals("cancellation must prevent the fallback request", 1, connections)
    }

    @Test
    fun `stream parsing stops emitting deltas after cancellation`() {
        lateinit var gateway: RemoteHttpGateway
        var deltas = 0
        gateway = RemoteHttpGateway("http://127.0.0.1:8080/v1", "", "local", connectionFactory = { _ ->
            fakeConnection("""data: {"choices":[{"delta":{"content":"first"}}]}
data: {"choices":[{"delta":{"content":"second"}}]}
data: [DONE]
""".trimIndent())
        })

        val result = gateway.stream(ModelRequest("system", "inspect", emptyList())) {
            deltas++
            if (deltas == 1) gateway.cancel()
        }

        assertEquals(ModelResponse.Failure("Cancelled"), result)
        assertEquals("cancellation must stop further streamed deltas", 1, deltas)
    }

    @Test
    fun `streamed multiple tool calls keep the first call and continue`() {
        val gateway = RemoteHttpGateway("http://127.0.0.1:8080/v1", "", "local", connectionFactory = { _ ->
            fakeConnection("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{}"}}]}}]}
data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_2","function":{"name":"search_project","arguments":"{}"}}]}}]}
data: [DONE]
""".trimIndent())
        })

        val result = gateway.stream(ModelRequest("system", "inspect", emptyList())) {}

        assertTrue(result is ModelResponse.ToolCall)
        val call = result as ModelResponse.ToolCall
        assertEquals("read_file", call.name)
        assertEquals("call_1", call.callId)
        assertTrue(call.thought.contains("next turn"))
    }

    @Test
    fun `streamed tool call arguments are accumulated instead of treated as text`() {
        val gateway = RemoteHttpGateway("http://127.0.0.1:8080/v1", "", "local", connectionFactory = { _ ->
            fakeConnection("""data: {"choices":[{"delta":{"tool_calls":[{"id":"call_1","index":0,"function":{"name":"read_file","arguments":"{\"path\":\"src/"}}]}}]}
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"Main.kt\"}"}}]}}]}
data: [DONE]
""".trimIndent())
        })

        val result = gateway.stream(ModelRequest("system", "inspect", emptyList())) {}

        assertEquals(ModelResponse.ToolCall("read_file", "{\"path\":\"src/Main.kt\"}", "", "call_1"), result)
    }

    @Test
    fun redirectsAreRejectedWithoutUsingTheRedirectBody() {
        val gateway = RemoteHttpGateway("http://127.0.0.1:8080/v1", "", "local", connectionFactory = { _ ->
            fakeConnection("secret-body", code = 302)
        })
        val result = gateway.complete(ModelRequest("system", "question", emptyList()))
        assertTrue(result is ModelResponse.Failure)
        val message = (result as ModelResponse.Failure).message
        assertTrue(message.contains("different web address"))
        assertTrue(!message.contains("secret-body"))
    }

    @Test
    fun openRouterErrorJsonKeepsTheStatusAndDropsTheWrapper() {
        val gateway = RemoteHttpGateway("http://127.0.0.1:9/v1", "sk-test", "openrouter/free", connectionFactory = { _ ->
            fakeConnection("""{"error":{"message":"Invalid API key","code":401}}""", code = 401)
        })
        val result = gateway.complete(ModelRequest("system", "ping", emptyList()))
        val message = (result as ModelResponse.Failure).message
        assertTrue(message.contains("Model HTTP 401"))
        assertTrue(message.contains("Invalid API key"))
        assertFalse(message.contains("{"))
    }

    @Test
    fun openRouterSendsAttributionHeadersAndDoesNotPutTheKeyInTheAddress() {
        val headers = linkedMapOf<String, String>()
        var requestedUrl = ""
        val gateway = RemoteHttpGateway(
            "https://openrouter.ai/api/v1",
            "test-secret-value",
            "openrouter/free",
            connectionFactory = { url ->
                requestedUrl = url
                headerCapturingConnection(headers, """{"choices":[{"message":{"content":"pong"}}]}""")
            }
        )
        val result = gateway.complete(ModelRequest("system", "ping", emptyList()))
        assertTrue(result is ModelResponse.Text)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", requestedUrl)
        assertFalse(requestedUrl.contains("test-secret-value"))
        assertEquals("Bearer test-secret-value", headers["Authorization"])
        assertEquals("Coding-Agent", headers["X-Title"])
        assertTrue(headers["HTTP-Referer"].orEmpty().contains("Coding-Agent"))
        assertEquals(1, headers.values.count { it.contains("test-secret-value") })
    }

    @Test
    fun errorBodiesDoNotEchoBearerTokens() {
        val gateway = RemoteHttpGateway("http://127.0.0.1:8080/v1", "sk-test", "local", connectionFactory = { _ ->
            fakeConnection("Authorization: Bearer sk-testvalueleaked", code = 401)
        })
        val result = gateway.complete(ModelRequest("system", "question", emptyList()))
        assertTrue(result is ModelResponse.Failure)
        val message = (result as ModelResponse.Failure).message
        assertTrue(message.contains("[redacted]"))
        assertTrue(!message.contains("sk-testvalueleaked"))
    }

    private fun headerCapturingConnection(headers: MutableMap<String, String>, body: String): HttpURLConnection =
        object : HttpURLConnection(java.net.URL("https://openrouter.ai/api/v1/chat/completions")) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getInputStream() = body.byteInputStream()
            override fun getErrorStream(): java.io.InputStream? = null
            override fun getOutputStream(): java.io.OutputStream = java.io.ByteArrayOutputStream()
            override fun setRequestProperty(key: String?, value: String?) {
                if (key != null && value != null) headers[key] = value
            }
            override fun getHeaderField(name: String?): String? = null
            override fun getPermission() = null
            override fun getContent() = null
            override fun getContentLength() = -1
            override fun getResponseMessage() = "OK"
            override fun getRequestMethod() = "POST"
            override fun setRequestMethod(method: String?) = Unit
        }

    private fun fakeConnection(body: String, onRequest: (String) -> Unit = {}, code: Int = 200): HttpURLConnection = object : HttpURLConnection(java.net.URL("http://127.0.0.1:8080/v1/chat/completions")) {
        private val request = java.io.ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = body.byteInputStream()
        override fun getErrorStream() = if (code in 200..299) null else body.byteInputStream()
        override fun getOutputStream(): java.io.OutputStream {
            return object : java.io.FilterOutputStream(request) {
                override fun close() { super.close(); onRequest(request.toString(Charsets.UTF_8.name())) }
            }
        }
        override fun getHeaderField(name: String?): String? = null
        override fun getPermission() = null
        override fun getContent() = null
        override fun getContentLength() = -1
        override fun getResponseMessage() = "OK"
        override fun getRequestMethod() = "POST"
        override fun setRequestMethod(method: String?) = Unit
        override fun toString(): String {
            onRequest(request.toString(Charsets.UTF_8.name()))
            return super.toString()
        }
    }
}