package com.codingagent.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * ONE JOB: Model calls + streaming for OpenAI-compatible gateways.
 */
interface ModelGateway {
    fun complete(request: ModelRequest): ModelResponse
    fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse = complete(request)
}

data class ModelRequest(
    val system: String,
    val user: String,
    val tools: List<ModelToolDefinition>,
    val transcript: List<ModelMessage> = emptyList(),
    val researchRequired: Boolean = false
)

data class ModelToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: String
)

data class ModelMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArguments: String? = null
)

sealed class ModelResponse {
    data class Text(val content: String) : ModelResponse()
    data class ToolCall(val name: String, val arguments: String, val thought: String = "", val callId: String? = null) : ModelResponse()
    data class Failure(val message: String) : ModelResponse()
}

class RemoteHttpGateway(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    private val timeoutMillis: Int = 60_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val extraHeaders: Map<String, String> = emptyMap()
) : ModelGateway {
    override fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse {
        val connection = openConnection() ?: return ModelResponse.Failure("Model gateway configuration is incomplete")
        return try {
            configure(connection, true)
            connection.outputStream.use { it.write(requestBody(request, true).toString().toByteArray(StandardCharsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return failure(connection)
            val content = StringBuilder()
            val toolCalls = linkedMapOf<Int, StreamToolCall>()
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (!line.startsWith("data:")) return@forEach
                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank() || data == "[DONE]") return@forEach
                    runCatching {
                        val delta = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return@runCatching
                        appendStreamDelta(delta, content, toolCalls, onDelta)
                    }.onFailure {
                        recoverMalformedStreamDelta(data, toolCalls)
                    }
                }
            }
            when {
                toolCalls.isNotEmpty() -> {
                    val call = toolCalls.values.first()
                    val arguments = call.arguments.toString().trim().let { raw ->
                        when {
                            raw.isBlank() -> "{}"
                            raw.startsWith("{") -> raw
                            else -> "{$raw}"
                        }
                    }
                    if (call.name.isBlank()) {
                        ModelResponse.Failure("Model streamed a tool call without a function name")
                    } else {
                        ModelResponse.ToolCall(
                            call.name,
                            arguments,
                            content.toString(),
                            call.id?.takeIf { it.isNotBlank() }
                        )
                    }
                }
                content.isBlank() -> {
                    // Some providers (e.g. NVIDIA OpenAI-compat) return HTTP 200 with empty SSE.
                    // Fall back to a non-streaming completion before failing the turn.
                    connection.disconnect()
                    return complete(request)
                }
                else -> JsonModelResponseParser().parse(content.toString())
            }
        } catch (error: Exception) {
            // Stream path failed — try one non-streaming request before giving up.
            return try {
                complete(request)
            } catch (fallbackError: Exception) {
                ModelResponse.Failure(
                    "Model stream failed: ${error.message.orEmpty().ifBlank { error.javaClass.simpleName }}"
                )
            }
        } finally {
            try { connection.disconnect() } catch (_: Exception) { }
        }
    }

    override fun complete(request: ModelRequest): ModelResponse {
        val connection = openConnection() ?: return ModelResponse.Failure("Model gateway configuration is incomplete")
        return try {
            configure(connection, false)
            connection.outputStream.use { it.write(requestBody(request, false).toString().toByteArray(StandardCharsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return failure(connection)
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val message = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            if (message == null) JsonModelResponseParser().parse(text) else responseFromChatMessage(message)
        } catch (error: IOException) {
            ModelResponse.Failure("Model request failed: ${error.message.orEmpty().ifBlank { error.javaClass.simpleName }}")
        } catch (error: Exception) {
            ModelResponse.Failure("Model response could not be processed: ${error.message.orEmpty().ifBlank { error.javaClass.simpleName }}")
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(): HttpURLConnection? {
        if (endpoint.isBlank() || model.isBlank()) return null
        if (apiKey.isBlank() && !endpoint.startsWith("http://127.0.0.1") && !endpoint.startsWith("http://localhost")) return null
        return try {
            connectionFactory(endpoint.trimEnd('/') + "/chat/completions")
        } catch (error: Exception) {
            throw IllegalArgumentException("Model endpoint is invalid: ${error.message.orEmpty()}")
        }
    }

    private fun configure(connection: HttpURLConnection, streaming: Boolean) {
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.requestMethod = "POST"
        connection.doOutput = true
        if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", if (streaming) "text/event-stream" else "application/json")
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        // User-supplied headers win. OpenRouter defaults only fill gaps when host matches.
        val host = runCatching { connection.url?.host.orEmpty() }.getOrDefault("")
        val applied = linkedMapOf<String, String>()
        if (host.contains("openrouter.ai")) {
            applied["HTTP-Referer"] = "https://github.com/thepotatoninjahost/Coding-Agent"
            applied["X-Title"] = "Coding-Agent"
        }
        applied.putAll(extraHeaders)
        for ((name, value) in applied) {
            if (name.isNotBlank() && value.isNotBlank()) {
                connection.setRequestProperty(name, value)
            }
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private fun requestBody(request: ModelRequest, streaming: Boolean): JSONObject {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", request.system))
        request.transcript.forEach { message ->
            val serialized = JSONObject().put("role", message.role)
            if (message.role == "assistant" && message.toolName != null) {
                serialized.put("content", JSONObject.NULL)
                serialized.put("tool_calls", JSONArray().put(JSONObject()
                    .put("id", message.toolCallId ?: "call_${message.toolName}")
                    .put("type", "function")
                    .put("function", JSONObject().put("name", message.toolName).put("arguments", message.toolArguments ?: "{}"))))
            } else {
                serialized.put("content", message.content)
                if (message.role == "tool") serialized.put("tool_call_id", message.toolCallId ?: "")
            }
            messages.put(serialized)
        }
        messages.put(JSONObject().put("role", "user").put("content", request.user))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.1)
            .put("stream", streaming)
        if (request.tools.isNotEmpty()) {
            body.put("tool_choice", "auto")
            body.put("tools", JSONArray().apply {
                request.tools.forEach { tool ->
                    val parameters = runCatching { JSONObject(tool.inputSchema) }.getOrElse { JSONObject().put("type", "object") }
                    put(JSONObject().put("type", "function").put("function", JSONObject()
                        .put("name", tool.name)
                        .put("description", tool.description)
                        .put("parameters", parameters)))
                }
            })
        }
        return body
    }

    private fun responseFromChatMessage(message: JSONObject): ModelResponse {
        val calls = message.optJSONArray("tool_calls")
        if (calls != null && calls.length() > 0) {
            val function = calls.optJSONObject(0)?.optJSONObject("function")
                ?: return ModelResponse.Failure("Model returned a malformed tool call")
            val name = function.optString("name")
            if (name.isBlank()) return ModelResponse.Failure("Model returned a tool call without a function name")
            return ModelResponse.ToolCall(
                name,
                function.optString("arguments", "{}"),
                extractMessageText(message),
                calls.optJSONObject(0)?.optString("id")
            )
        }
        val content = extractMessageText(message)
        return if (content.isBlank()) {
            ModelResponse.Failure(
                "Model returned no usable message content " +
                    "(checked content, content parts, reasoning_content). " +
                    "Keys: ${message.keys().asSequence().toList().joinToString()}"
            )
        } else {
            JsonModelResponseParser().parse(content)
        }
    }

    /**
     * OpenAI-compatible providers differ: content may be a string, an array of parts,
     * null with reasoning_content filled (reasoning models), or refusal text.
     */
    private fun extractMessageText(message: JSONObject): String {
        val direct = message.opt("content")
        when (direct) {
            null, JSONObject.NULL -> Unit
            is String -> if (direct.isNotBlank()) return direct.trim()
            is JSONArray -> {
                val parts = buildString {
                    for (i in 0 until direct.length()) {
                        val part = direct.opt(i) ?: continue
                        when (part) {
                            is String -> if (part.isNotBlank()) append(part)
                            is JSONObject -> {
                                val text = part.optString("text")
                                    .ifBlank { part.optString("content") }
                                if (text.isNotBlank()) append(text)
                            }
                        }
                    }
                }.trim()
                if (parts.isNotBlank()) return parts
            }
            is JSONObject -> {
                val text = direct.optString("text").ifBlank { direct.optString("content") }
                if (text.isNotBlank()) return text.trim()
            }
        }
        for (key in listOf("reasoning_content", "reasoning", "refusal", "output_text")) {
            val v = message.optString(key)
            if (v.isNotBlank()) return v.trim()
        }
        return ""
    }


    private fun failure(connection: HttpURLConnection): ModelResponse.Failure {
        val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            .ifBlank { connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty() }
        val headerBits = listOf("server", "cf-ray", "x-request-id", "www-authenticate", "x-error", "error")
            .mapNotNull { name ->
                connection.getHeaderField(name)?.takeIf { it.isNotBlank() }?.let { "$name=$it" }
            }
            .joinToString("; ")
        val target = runCatching { connection.url?.host.orEmpty() + (connection.url?.path.orEmpty()) }.getOrDefault("")
        val detail = buildString {
            append("Model HTTP ${connection.responseCode}")
            if (target.isNotBlank()) append(" @ $target")
            if (headerBits.isNotBlank()) append(" [$headerBits]")
            val snippet = body.replace("\\s+".toRegex(), " ").trim().take(600)
            if (snippet.isNotBlank()) append(": $snippet")
            else append(": (empty body)")
        }
        return ModelResponse.Failure(detail)
    }

    private fun appendStreamDelta(delta: JSONObject, content: StringBuilder, toolCalls: MutableMap<Int, StreamToolCall>, onDelta: (String) -> Unit) {
        delta.optString("content").takeIf { it.isNotEmpty() }?.let {
            content.append(it)
            onDelta(it)
        }
        val calls = delta.optJSONArray("tool_calls") ?: return
        for (index in 0 until calls.length()) {
            val call = calls.optJSONObject(index) ?: continue
            val position = call.optInt("index", index)
            val function = call.optJSONObject("function")
            val current = toolCalls.getOrPut(position) { StreamToolCall() }
            call.optString("id").takeIf { it.isNotEmpty() }?.let { current.id = it }
            function?.optString("name")?.takeIf { it.isNotEmpty() }?.let { current.name = it }
            call.optString("name").takeIf { it.isNotEmpty() }?.let { current.name = it }
            function?.optString("arguments")?.takeIf { it.isNotEmpty() }?.let { fragment ->
                if (current.arguments.isEmpty() && fragment.firstOrNull() != '{') current.arguments.append('{')
                current.arguments.append(fragment)
            }
        }
    }

    private fun recoverMalformedStreamDelta(data: String, toolCalls: MutableMap<Int, StreamToolCall>) {
        val current = toolCalls.getOrPut(0) { StreamToolCall() }
        Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(data)?.groupValues?.getOrNull(1)?.let { current.id = it }
        Regex("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(data)?.groupValues?.getOrNull(1)?.let { current.name = it }
        Regex("\\\"arguments\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)").find(data)?.groupValues?.getOrNull(1)?.let { fragment ->
            current.arguments.append(unescapeJsonFragment(fragment))
        }
    }

    private fun unescapeJsonFragment(fragment: String): String = fragment
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    private class StreamToolCall {
        var id: String? = null
        var name: String = ""
        val arguments = StringBuilder()
    }
}

class UnconfiguredModelGateway : ModelGateway {
    override fun complete(request: ModelRequest): ModelResponse = ModelResponse.Failure("No model gateway is configured")
}

class JsonModelResponseParser {
    fun parse(raw: String): ModelResponse {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ModelResponse.Failure("Model returned an empty response")
        return try {
            val json = JSONObject(trimmed)
            when {
                (json.optString("tool").ifBlank { json.optString("name") }).isNotBlank() -> ModelResponse.ToolCall(
                    name = json.optString("tool").ifBlank { json.getString("name") },
                    arguments = json.optJSONObject("arguments")?.toString() ?: json.optString("arguments", "{}"),
                    thought = json.optString("thought")
                )
                json.optString("content").isNotBlank() -> ModelResponse.Text(json.getString("content"))
                json.optString("reasoning_content").isNotBlank() -> ModelResponse.Text(json.getString("reasoning_content"))
                json.optString("text").isNotBlank() -> ModelResponse.Text(json.getString("text"))
                else -> ModelResponse.Text(trimmed)
            }
        } catch (_: Exception) {
            ModelResponse.Text(trimmed)
        }
    }
}

object AgentModelProtocol {
    val DEFAULT_SYSTEM = """You are a Coding-Agent: an autonomous software-engineering system on the user's device.

You plan, use tools, observe real results, and iterate until the goal is completed. You are not a one-shot chatbot.

## Core loop
1. Understand the goal precisely. Decompose into ordered steps.
2. Gather real evidence with tools before analyzing or changing code. Never invent paths, file contents, command output, or test results.
3. Act with exactly one tool call per turn. Observe the full result before the next step.
4. On failure: diagnose, adjust, retry correctly. Do not repeat the same failing call.
5. Verify after meaningful edits and when hunting bugs. Never report a fake pass.
6. Research when the user asks or when current external knowledge is required (APIs, libraries, errors, best practices that change over time).
7. When the goal is done, answer in clear technical English grounded in what you actually read, ran, or researched.

## Hard rules
- Evidence first. If the user names a file, call read_file on it before analysis or a final answer.
- Exactly one tool per turn. Parallel tool calls are not executed as a set; only the first is used.
- Code changes (create_file, replace_text) only STAGE a proposal. Dual owner approval is required. Never claim a change was applied until a tool returns APPLIED.
- Prefer small, precise, reversible steps. Prefer truth over plausible guesses.
- Persist until the goal is met. Only stop early when you need a specific missing input from the user that tools cannot supply — state exactly what you need.
- Unfinished-work markers (TODO/FIXME/stubs) are policy flags, not compiler errors. Real errors come from compile/test/runtime evidence and code reasoning.
- Research is a first-class capability. Use research_web (and search_knowledge when relevant) for evolving APIs, docs, and practices. Cite what you found; do not invent sources.

## Available tools
list_files, read_file, search_project, search_knowledge, research_web, replace_text, create_file, approve_change, reject_change, run_command, verify
""".trimIndent()

    val SYSTEM: String get() = DEFAULT_SYSTEM

    fun tools(): List<ModelToolDefinition> = listOf(
        ModelToolDefinition(
            "list_files",
            "List files and directories under a project-relative path. Use an empty path for the project root. Prefer this before guessing paths.",
            """{"type":"object","properties":{"path":{"type":"string","description":"Project-relative directory path (empty or '.' for root)"}},"required":[]}"""
        ),
        ModelToolDefinition(
            "read_file",
            "Read the full content of one project file. Required before analyzing or modifying any named file.",
            """{"type":"object","properties":{"path":{"type":"string","description":"Project-relative file path"}},"required":["path"]}"""
        ),
        ModelToolDefinition(
            "search_project",
            "Search the project source for a text or regex-like query. Returns matching lines with paths.",
            """{"type":"object","properties":{"query":{"type":"string","description":"Search query"}},"required":["query"]}"""
        ),
        ModelToolDefinition(
            "search_knowledge",
            "Search the local offline knowledge base (reference material imported into the agent).",
            """{"type":"object","properties":{"query":{"type":"string","description":"Search query"}},"required":["query"]}"""
        ),
        ModelToolDefinition(
            "research_web",
            "Research the web for technical information. Use for APIs, errors, and external facts not in the project.",
            """{"type":"object","properties":{"query":{"type":"string","description":"Research query"},"mode":{"type":"string","description":"BROAD or DEEP"},"sources":{"type":"integer","description":"Max sources to gather"}},"required":["query"]}"""
        ),
        ModelToolDefinition(
            "replace_text",
            "Stage an exact text replacement. Dual owner approval is required before it is applied.",
            """{"type":"object","properties":{"path":{"type":"string"},"oldText":{"type":"string"},"newText":{"type":"string"},"reason":{"type":"string"}},"required":["path","oldText","newText"]}"""
        ),
        ModelToolDefinition(
            "create_file",
            "Stage a new file. Dual owner approval is required before it is written.",
            """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"reason":{"type":"string"}},"required":["path","content"]}"""
        ),
        ModelToolDefinition(
            "approve_change",
            "Record one owner approval for a pending proposal (two approvals required).",
            """{"type":"object","properties":{"id":{"type":"string"},"ownerVerified":{"type":"boolean"},"ownerLabel":{"type":"string"}},"required":["id","ownerVerified","ownerLabel"]}"""
        ),
        ModelToolDefinition(
            "reject_change",
            "Reject a pending change proposal.",
            """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}"""
        ),
        ModelToolDefinition(
            "run_command",
            "Run a shell command in the project root and return stdout/stderr/exit code.",
            """{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}"""
        ),
        ModelToolDefinition(
            "verify",
            "Run static verification (unfinished-work marker scan). Never reports a fake pass.",
            """{"type":"object","properties":{},"required":[]}"""
        )
    )

    fun toolsForIntent(intent: TaskIntent): List<ModelToolDefinition> {
        val all = tools().associateBy { it.name }
        val names = when (intent) {
            TaskIntent.INSPECT, TaskIntent.EXPLAIN, TaskIntent.UNKNOWN ->
                listOf("list_files", "read_file", "search_project", "search_knowledge", "research_web")
            TaskIntent.CHANGE, TaskIntent.CREATE, TaskIntent.REFACTOR, TaskIntent.DEBUG ->
                listOf("list_files", "read_file", "search_project", "search_knowledge", "research_web", "replace_text", "create_file", "approve_change", "reject_change", "verify", "run_command")
            TaskIntent.TEST ->
                listOf("list_files", "read_file", "run_command", "verify", "search_project")
        }
        return names.mapNotNull { all[it] }
    }
}
