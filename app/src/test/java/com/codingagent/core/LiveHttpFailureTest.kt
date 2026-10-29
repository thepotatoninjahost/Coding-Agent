package com.codingagent.core

import com.codingagent.agent.AgentKnowledge
import com.codingagent.agent.AutonomousAgent
import com.codingagent.agent.AutonomousAgentConfig
import com.codingagent.agent.AutonomousAgentEvent
import com.codingagent.model.ModelIdCheck
import com.codingagent.model.ProviderEndpoint
import com.codingagent.model.ProviderQuotaBook
import com.codingagent.model.ProviderRotatingGateway
import com.codingagent.workspace.KnowledgeHit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the real agent through a local OpenAI-shaped server.
 * The server repeats the failures from the live Groq and Requesty run.
 */
class LiveHttpFailureTest {
    @Test
    fun liveFailuresStillFinishTheTask() {
        ScriptedChatServer().use { server ->
            val base = "http://127.0.0.1:${server.port}/v1"
            val dropped = ProviderEndpoint("requesty", "missing", "cohere/north-mini-code:free", base, "test-key")
            val kept = ProviderEndpoint("requesty", "real", "nvidia/nemotron-3-ultra-550b-a55b", base, "test-key")
            val filtered = ModelIdCheck.filter(listOf(dropped, kept)) { url ->
                URL(url).openConnection() as java.net.HttpURLConnection
            }
            assertTrue(filtered.none { it.modelId.endsWith(":free") })
            assertTrue(filtered.any { it.modelId == "nvidia/nemotron-3-ultra-550b-a55b" })

            val endpoints = listOf(
                endpoint(base, "bad-but-listed"),
                endpoint(base, "qwen/qwen3.8-27b"),
                endpoint(base, "slow-model"),
                endpoint(base, "google/gemma-4-31b-it"),
                endpoint(base, "nvidia/nemotron-3-ultra-550b-a55b")
            )
            val gateway = ProviderRotatingGateway(
                endpoints = endpoints,
                quota = ProviderQuotaBook(Files.createTempDirectory("quota").toFile().resolve("quota.json")),
                ranking = { endpoints.map { it.modelId } },
                timeoutMillis = 10_000
            )
            val root = Files.createTempDirectory("live-http").toFile()
            val agent = AutonomousAgent(
                root,
                object : AgentKnowledge {
                    override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
                },
                gateway,
                AutonomousAgentConfig(maxTurns = 8)
            )
            val events = agent.run("Build me a tiny compiler")
            val shown = events.joinToString("\n") { event ->
                when (event) {
                    is AutonomousAgentEvent.Failed -> event.message
                    is AutonomousAgentEvent.Completed -> event.task.summary
                    is AutonomousAgentEvent.ApprovalRequired -> event.task.summary
                    is AutonomousAgentEvent.Phase -> event.detail
                    is AutonomousAgentEvent.ToolFinished -> event.result
                    else -> ""
                }
            }
            assertFalse(shown.contains("Model HTTP"))
            assertFalse(shown.contains("Invalid model parameters"))
            assertFalse(shown.contains("function call turn"))
            assertTrue(events.last() is AutonomousAgentEvent.ApprovalRequired)
            val summary = (events.last() as AutonomousAgentEvent.ApprovalRequired).task.summary
            assertTrue(summary.contains("I ran the examples and they matched."))
            assertFalse(summary.contains("prints the correct result"))
            assertFalse(summary.contains("Model HTTP"))
            assertFalse(summary.contains("finish_reason"))
            assertFalse(summary.contains("partial content was rejected"))
            val toolNames = events.filterIsInstance<AutonomousAgentEvent.ToolFinished>().map { it.name }
            assertTrue(toolNames.contains("create_file"))
            assertTrue(summary.contains("1+1") || summary.contains("matched"))
            assertTrue(server.bodies.isNotEmpty())
            assertTrue(server.bodies.all { it.contains("\"max_tokens\"") })
            assertTrue(server.statuses.any { it == 400 })
            assertTrue(server.statuses.any { it == 413 })
            assertTrue(server.statuses.any { it == 429 })
            assertTrue(server.models.none { it.endsWith(":free") })
            assertTrue(server.models.count { it.contains("qwen") } <= 2)
            assertTrue(server.ordered)
        }
    }

    @Test
    fun groqMinuteLimitWaitsThenTheCompilerReachesAPassingCard() {
        GroqReplayServer(oversize = false).use { server ->
            val events = runCompiler(server)
            val shown = visible(events)
            assertEquals(429, server.statuses.first())
            assertTrue(server.statuses.any { it == 200 })
            assertTrue(shown.contains("second"))
            assertFalse(shown.contains("today at"))
            val approval = events.last() as AutonomousAgentEvent.ApprovalRequired
            assertTrue(approval.task.summary.contains("compiler.js"))
            assertTrue(approval.task.summary.contains("I ran the examples and they matched."))
            assertTrue(approval.task.summary.contains("(1+2)*3"))
            assertFalse(approval.task.summary.contains("I have not run this yet"))
            assertFalse(approval.task.summary.contains("unfinished"))
            assertFalse(approval.task.summary.contains("OPEN JOB"))
            assertFalse(shown.contains("Model HTTP"))
        }
    }

    @Test
    fun aRequestBiggerThanTheMinuteLimitIsShortenedAndStillReachesTheCard() {
        GroqReplayServer(oversize = true).use { server ->
            val events = runCompiler(server)
            val shown = visible(events)
            assertEquals(429, server.statuses.first())
            assertTrue(server.statuses.any { it == 200 })
            assertFalse(shown.contains("used up"))
            assertFalse(shown.contains("today at"))
            val approval = events.last() as AutonomousAgentEvent.ApprovalRequired
            assertTrue(approval.task.summary.contains("I ran the examples and they matched."))
            assertTrue(approval.task.summary.contains("compiler.js"))
            assertFalse(approval.task.summary.contains("unfinished"))
        }
    }

    private fun runCompiler(server: GroqReplayServer): List<AutonomousAgentEvent> {
        val base = "http://127.0.0.1:${server.port}/v1"
        val gateway = ProviderRotatingGateway(
            endpoints = listOf(
                ProviderEndpoint("groq", "Groq large code helper", "openai/gpt-oss-120b", base, "test-groq-key")
            ),
            quota = ProviderQuotaBook(Files.createTempDirectory("groq-quota").toFile().resolve("quota.json")),
            ranking = { listOf("openai/gpt-oss-120b") },
            preferredModel = "openai/gpt-oss-120b",
            timeoutMillis = 10_000
        )
        val root = Files.createTempDirectory("groq-replay").toFile()
        return AutonomousAgent(root, object : AgentKnowledge {
            override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
        }, gateway, AutonomousAgentConfig(maxTurns = 8)).run(
            "Write a tiny compiler in JavaScript that turns arithmetic like (1+2)*3 into stack-machine instructions, run it, and test it on a few examples including a bad input"
        )
    }

    private fun visible(events: List<AutonomousAgentEvent>): String = events.joinToString("\n") { event ->
        when (event) {
            is AutonomousAgentEvent.Failed -> event.message
            is AutonomousAgentEvent.Completed -> event.task.summary
            is AutonomousAgentEvent.ApprovalRequired -> event.task.summary
            is AutonomousAgentEvent.Phase -> event.detail
            else -> ""
        }
    }

    @Test
    fun aFailedModelListKeepsTheHelpers() {
        val endpoint = ProviderEndpoint("custom", "helper", "local-model", "http://127.0.0.1:9/v1", "test-key")
        val kept = ModelIdCheck.filter(listOf(endpoint)) { _ -> throw java.io.IOException("down") }
        assertTrue(kept.single().modelId == "local-model")
    }

    private fun endpoint(base: String, modelId: String) = ProviderEndpoint(
        providerId = "custom",
        plainName = modelId,
        modelId = modelId,
        baseUrl = base,
        apiKey = "test-key"
    )
}

private class ScriptedChatServer : AutoCloseable {
    val port: Int
    val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    val models = java.util.Collections.synchronizedList(mutableListOf<String>())
    val statuses = java.util.Collections.synchronizedList(mutableListOf<Int>())
    @Volatile var ordered: Boolean = true
    private val socket: ServerSocket
    private val thread: Thread
    @Volatile private var running = true
    private val slowHits = AtomicInteger()

    init {
        socket = ServerSocket(0)
        port = socket.localPort
        thread = Thread {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                Thread { handle(client) }.start()
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    override fun close() {
        running = false
        runCatching { socket.close() }
        thread.join(1_000)
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            val input = socket.getInputStream()
            val request = readHttp(input)
            val path = request.path
            val response = when {
                request.method == "GET" && path.endsWith("/models") -> json(200, modelsJson())
                request.method == "POST" && path.endsWith("/chat/completions") -> chat(request.body)
                else -> json(404, """{"error":{"message":"not found"}}""")
            }
            socket.getOutputStream().write(response)
            socket.getOutputStream().flush()
        }
    }

    private fun chat(body: String): ByteArray {
        bodies += body
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return json(400, """{"error":{"message":"Invalid model parameters"}}""")
        val model = json.optString("model")
        models += model
        if (!json.has("max_tokens")) {
            statuses += 400
            return json(400, """{"error":{"message":"Invalid model parameters"}}""")
        }
        val messages = json.optJSONArray("messages") ?: JSONArray()
        if (toolCallBeforeUser(messages)) {
            ordered = false
            statuses += 400
            return json(400, """{"error":{"message":"function call turn comes immediately after a user turn or after a function response turn"}}""")
        }
        if (model.endsWith(":free") || model.contains("north-mini") || model == "bad-but-listed") {
            statuses += 400
            return json(400, """{"error":{"message":"Invalid model parameters"}}""")
        }
        if (model.contains("qwen")) {
            statuses += 413
            return json(413, """{"error":{"message":"Request too large. OTPM limit 1000. output tokens"}}""")
        }
        if (model == "slow-model" && slowHits.getAndIncrement() == 0) {
            statuses += 429
            return json(429, """{"error":{"message":"Rate limit exceeded. try again in 1"}}""")
        }
        statuses += 200
        return sse(script(messages.toString()))
    }

    private fun script(transcript: String): String {
        val lower = transcript.lowercase()
        return when {
            "proposal_ready" in lower -> sseTool("review_examples", reviewArguments())
            else -> sseTool("create_file", createArguments())
        }
    }

    private fun toolCallBeforeUser(messages: JSONArray): Boolean {
        var sawUser = false
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (message.optString("role") == "user") sawUser = true
            if (message.optString("role") == "assistant" && message.has("tool_calls") && !sawUser) return true
        }
        return false
    }

    private fun modelsJson(): String {
        val data = JSONArray()
        listOf(
            "bad-but-listed",
            "qwen/qwen3.8-27b",
            "slow-model",
            "google/gemma-4-31b-it",
            "nvidia/nemotron-3-ultra-550b-a55b"
        ).forEach { data.put(JSONObject().put("id", it)) }
        return JSONObject().put("data", data).toString()
    }

    private fun createArguments(): String = JSONObject()
        .put("path", "compiler.js")
        .put("content", JS_COMPILER)
        .put(
            "reason",
            "What I want to do: Add compiler.py, a small program that reads simple sums.\n" +
                "Why: You asked for a tiny program that reads written instructions.\n" +
                "What will change: Create compiler.py.\n" +
                "Possible effects: After you say yes, the new file can read one or more lines.\n" +
                "Risks: A line that is not a simple sum is refused.\n" +
                "What your approval means: Nothing is written until you say yes twice."
        )
        .toString()

    private fun reviewArguments(): String = JSONObject()
        .put("path", "compiler.js")
        .put("examples", "1+1 => 2\n2+3||4+1 => 5||5\n1+ => error")
        .put("notes", "Two examples. One of them is more than one line.")
        .toString()

    private fun sseTool(name: String, arguments: String): String {
        val call = JSONObject()
            .put("index", 0)
            .put("id", "call_$name")
            .put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", arguments))
        val first = JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(call))))
        )
        val second = JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", "tool_calls"))
        )
        return "data: $first\n\ndata: $second\n\ndata: [DONE]\n\n"
    }

    private fun sse(payload: String): ByteArray {
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        return head.toByteArray(StandardCharsets.UTF_8) + bytes
    }

    private fun json(code: Int, payload: String): ByteArray {
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val reason = when (code) {
            400 -> "Bad Request"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            else -> "OK"
        }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        return head.toByteArray(StandardCharsets.UTF_8) + bytes
    }

    private fun readHttp(input: InputStream): HttpRequest {
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n") && header.length < 64_000) {
            val next = input.read()
            if (next < 0) break
            header.append(next.toChar())
        }
        val text = header.toString()
        val first = text.lineSequence().firstOrNull().orEmpty()
        val method = first.substringBefore(' ')
        val path = first.substringAfter(' ').substringBefore(' ')
        val length = Regex("(?i)Content-Length:\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val body = if (length <= 0) "" else {
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(bytes, read, length - read)
                if (count < 0) break
                read += count
            }
            String(bytes, 0, read, StandardCharsets.UTF_8)
        }
        return HttpRequest(method, path, body)
    }

    private data class HttpRequest(val method: String, val path: String, val body: String)

    companion object {
        private val JS_COMPILER = """
            function evaluate(line) {
              var compact = String(line).replace(/ /g, "");
              if (compact === "") return;
              if (/[+\-*/]${'$'}/.test(compact) || (compact.split("(").length !== compact.split(")").length)) {
                console.log("error");
                return;
              }
              try {
                var value = Function("return (" + compact + ")")();
                if (typeof value !== "number" || !isFinite(value)) console.log("error");
                else console.log(String(value));
              } catch (e) {
                console.log("error");
              }
            }
            String(__stdin || "").split("\n").forEach(evaluate);
        """.trimIndent() + "\n"
    }
}

private class GroqReplayServer(private val oversize: Boolean) : AutoCloseable {
    val port: Int
    val statuses = java.util.Collections.synchronizedList(mutableListOf<Int>())
    private val socket: ServerSocket
    private val thread: Thread
    @Volatile private var running = true
    private val chats = java.util.concurrent.atomic.AtomicInteger()

    init {
        socket = ServerSocket(0)
        port = socket.localPort
        thread = Thread {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                Thread { handle(client) }.start()
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    override fun close() {
        running = false
        runCatching { socket.close() }
        thread.join(1_000)
    }

    private fun handle(client: Socket) {
        client.use { open ->
            val request = readHttp(open.getInputStream())
            val response = if (request.method == "POST" && request.path.endsWith("/chat/completions")) chat() else {
                json(404, """{"error":{"message":"not found"}}""")
            }
            open.getOutputStream().write(response)
            open.getOutputStream().flush()
        }
    }

    private fun chat(): ByteArray {
        val turn = chats.getAndIncrement()
        if (turn == 0) {
            statuses += 429
            val requested = if (oversize) 12_000 else 1_200
            val used = if (oversize) 0 else 6_800
            val body = """{"error":{"message":"Rate limit reached for model `openai/gpt-oss-120b` in organization `org_test` service tier `on_demand` on tokens per minute (TPM): Limit 8000, Used $used, Requested $requested. Please try again in 1s.","type":"tokens","code":"rate_limit_exceeded"}}"""
            return json(
                429,
                body,
                listOf(
                    "retry-after: 1",
                    "x-ratelimit-limit-tokens: 8000",
                    "x-ratelimit-remaining-tokens: ${if (oversize) 0 else 6800}",
                    "x-ratelimit-reset-tokens: 1s",
                    "x-ratelimit-reset-requests: 1s"
                )
            )
        }
        statuses += 200
        return sse(tool("create_file", createArguments()))
    }

    private fun createArguments(): String = JSONObject()
        .put("path", "compiler.js")
        .put("content", PASSING_COMPILER)
        .toString()

    private fun tool(name: String, arguments: String): String {
        val call = JSONObject()
            .put("index", 0)
            .put("id", "call_$name")
            .put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", arguments))
        val first = JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(call))))
        )
        val second = JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", "tool_calls"))
        )
        return "data: $first\n\ndata: $second\n\ndata: [DONE]\n\n"
    }

    private fun sse(payload: String): ByteArray {
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        return head.toByteArray(StandardCharsets.UTF_8) + bytes
    }

    private fun json(code: Int, payload: String, extra: List<String> = emptyList()): ByteArray {
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val reason = if (code == 429) "Too Many Requests" else if (code == 404) "Not Found" else "OK"
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\n")
            extra.forEach { append(it).append("\r\n") }
            append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        }
        return head.toByteArray(StandardCharsets.UTF_8) + bytes
    }

    private fun readHttp(input: InputStream): HttpRequest {
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n") && header.length < 64_000) {
            val next = input.read()
            if (next < 0) break
            header.append(next.toChar())
        }
        val text = header.toString()
        val first = text.lineSequence().firstOrNull().orEmpty()
        val method = first.substringBefore(' ')
        val path = first.substringAfter(' ').substringBefore(' ')
        val length = Regex("(?i)Content-Length:\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val body = if (length <= 0) "" else {
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(bytes, read, length - read)
                if (count < 0) break
                read += count
            }
            String(bytes, 0, read, StandardCharsets.UTF_8)
        }
        return HttpRequest(method, path, body)
    }

    private data class HttpRequest(val method: String, val path: String, val body: String)

    companion object {
        private val PASSING_COMPILER = """
            var expr = String(process.argv[2] || "");
            function bad(s) {
              if (!s) return true;
              if (/[+\-*/]$/.test(s)) return true;
              if (s.indexOf("++") >= 0) return true;
              if (/[^0-9+\-*/().\s]/.test(s)) return true;
              return false;
            }
            if (bad(expr)) console.log("error");
            else if (expr === "(1+2)*3") console.log("PUSH 1\nPUSH 2\nADD\nPUSH 3\nMUL");
            else if (expr === "-3+1") console.log("PUSH -3\nPUSH 1\nADD");
            else console.log("PUSH 0");
        """.trimIndent()
    }
}
