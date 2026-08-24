package com.codingagent.core

import java.net.HttpURLConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse
import com.codingagent.model.ModelToolDefinition
import com.codingagent.model.RemoteHttpGateway

class ModelGatewayTest {
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
    fun `streamed tool call arguments are accumulated instead of treated as text`() {
        val gateway = RemoteHttpGateway("http://127.0.0.1:8080/v1", "", "local", connectionFactory = { _ ->
            fakeConnection("""data: {"choices":[{"delta":{"tool_calls":[{"id":"call_1","index":0,"function":{"name":"read_file","arguments":"{\"path\":\"src/"}}}]}}]}
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"Main.kt\"}"}}]}}]}
data: [DONE]
""".trimIndent())
        })

        val result = gateway.stream(ModelRequest("system", "inspect", emptyList())) {}

        assertEquals(ModelResponse.ToolCall("read_file", "{\"path\":\"src/Main.kt\"}", "", "call_1"), result)
    }

    private fun fakeConnection(body: String, onRequest: (String) -> Unit = {}): HttpURLConnection = object : HttpURLConnection(java.net.URL("http://127.0.0.1:8080/v1/chat/completions")) {
        private val request = java.io.ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream() = body.byteInputStream()
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
