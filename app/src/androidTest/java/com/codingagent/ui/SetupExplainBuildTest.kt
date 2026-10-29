package com.codingagent.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Setup with a helper on this phone, then an explanation and a small build.
 * The approval card must name the file and show code, not the internal job prompt.
 */
@RunWith(AndroidJUnit4::class)
class SetupExplainBuildTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var server: ServerSocket? = null
    private val running = AtomicBoolean(true)

    @Before
    fun startHelper() {
        val socket = ServerSocket(0)
        server = socket
        Thread {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                Thread { runCatching { answer(client) } }.start()
            }
        }.start()
    }

    @After
    fun stopHelper() {
        running.set(false)
        runCatching { server?.close() }
    }

    @Test
    fun setupExplainAndBuildShowsTheFileNotThePrompt() {
        val port = server!!.localPort
        composeRule.onNodeWithText("Web address").performScrollTo().performTextReplacement("http://127.0.0.1:$port/v1")
        composeRule.onNodeWithText("Helper name").performScrollTo().performTextReplacement("local-helper")
        composeRule.onNodeWithText("Secret password").performScrollTo().performTextReplacement("local-test-key")
        composeRule.onNodeWithText("Save").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("New").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Your new project is ready.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        typeAndSend("Explain this project in one short paragraph. Do not change any files.")
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithText("empty folder", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithText("Work:", substring = true).fetchSemanticsNodes().isEmpty())

        typeAndSend("Build a tiny compiler in JavaScript")
        composeRule.waitUntil(timeoutMillis = 90_000) {
            composeRule.onAllNodesWithText("compiler.js", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Save this file").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithText("compiler.js", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("console.log", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("Save this file").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("OPEN JOB", substring = true).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Review").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Reject").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithText("Reject").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("compiler.js", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("OPEN JOB", substring = true).fetchSemanticsNodes().isEmpty())
    }

    private fun typeAndSend(text: String) {
        composeRule.onNode(hasSetTextAction()).performTextReplacement(text)
        composeRule.onNodeWithText("Send").performClick()
    }

    private fun answer(socket: Socket) {
        socket.use { client ->
            val input = client.getInputStream()
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n") && header.length < 20_000) {
                val next = input.read()
                if (next < 0) return
                header.append(next.toChar())
            }
            val length = Regex("(?i)Content-Length:\\s*(\\d+)").find(header)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val body = if (length <= 0) "" else {
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val count = input.read(bytes, read, length - read)
                    if (count < 0) break
                    read += count
                }
                String(bytes, 0, read)
            }
            val payload = when {
                header.contains("GET") -> """{"data":[{"id":"local-helper"}]}"""
                "PROPOSAL_READY" in body || "proposal_ready" in body.lowercase() ->
                    sseTool("review_examples", """{"path":"compiler.js","examples":"1+1 => 2\nok => 2"}""")
                "Build a tiny compiler" in body -> sseTool(
                    "create_file",
                    """{"path":"compiler.js","content":"console.log(\"2\");\n"}"""
                )
                else -> sseText("This project is an empty folder.")
            }
            val bytes = payload.toByteArray()
            val type = if (header.contains("GET")) "application/json" else "text/event-stream"
            val response = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            client.getOutputStream().write(response.toByteArray())
            client.getOutputStream().write(bytes)
            client.getOutputStream().flush()
        }
    }

    private fun sseText(text: String): String {
        val first = """data: {"choices":[{"delta":{"content":${org.json.JSONObject.quote(text)}}}]}"""
        val second = """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        return "$first\n\n$second\n\ndata: [DONE]\n\n"
    }

    private fun sseTool(name: String, arguments: String): String {
        val call = org.json.JSONObject()
            .put("index", 0)
            .put("id", "call_$name")
            .put("type", "function")
            .put(
                "function",
                org.json.JSONObject().put("name", name).put("arguments", arguments)
            )
        val first = org.json.JSONObject().put(
            "choices",
            org.json.JSONArray().put(org.json.JSONObject().put("delta", org.json.JSONObject().put("tool_calls", org.json.JSONArray().put(call))))
        )
        val second = """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
        return "data: $first\n\n$second\n\ndata: [DONE]\n\n"
    }
}
