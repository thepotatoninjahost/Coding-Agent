package com.codingagent.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

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

class OpenAiCompatibleGateway(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    private val timeoutMillis: Int = 60_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) : ModelGateway {
    override fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse {
        val connection = openConnection() ?: return ModelResponse.Failure("Model gateway configuration is incomplete")
        return try {
            configure(connection, true)
            connection.outputStream.use { it.write(requestBody(request, true).toString().toByteArray(StandardCharsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return failure(connection)
            val content = StringBuilder()
            val toolCalls = linkedMapOf<Int, StreamToolCall>()
            val pendingLines = StringBuilder()
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
                toolCalls.size > 1 -> ModelResponse.Failure("Model returned ${toolCalls.size} tool calls; the agent currently executes one tool per turn")
                toolCalls.isNotEmpty() -> {
                    val call = toolCalls.values.single()
                    val arguments = call.arguments.toString().trim().let { raw ->
                        when {
                            raw.isBlank() -> "{}"
                            raw.startsWith("{") -> raw
                            else -> "{$raw}"
                        }
                    }
                    if (call.name.isBlank()) ModelResponse.Failure("Model streamed a tool call without a function name")
                    else ModelResponse.ToolCall(call.name, arguments, content.toString(), call.id?.takeIf { it.isNotBlank() })
                }
                content.isBlank() -> ModelResponse.Failure("Model returned no streamed message content")
                else -> JsonModelResponseParser().parse(content.toString())
            }
        } catch (error: Exception) {
            ModelResponse.Failure("Model stream failed: ${error.message.orEmpty().ifBlank { error.javaClass.simpleName }}")
        } finally {
            connection.disconnect()
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
            if (message == null) JsonModelResponseParser().parse(text) else responseFromOpenAiMessage(message)
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

    private fun responseFromOpenAiMessage(message: JSONObject): ModelResponse {
        val calls = message.optJSONArray("tool_calls")
        if (calls != null && calls.length() > 0) {
            if (calls.length() > 1) return ModelResponse.Failure("Model returned ${calls.length()} tool calls; the agent currently executes one tool per turn")
            val function = calls.optJSONObject(0)?.optJSONObject("function")
                ?: return ModelResponse.Failure("Model returned a malformed tool call")
            val name = function.optString("name")
            if (name.isBlank()) return ModelResponse.Failure("Model returned a tool call without a function name")
            return ModelResponse.ToolCall(name, function.optString("arguments", "{}"), message.optString("content"), calls.optJSONObject(0)?.optString("id"))
        }
        val content = message.optString("content")
        return if (content.isBlank()) ModelResponse.Failure("Model returned no message content") else JsonModelResponseParser().parse(content)
    }

    private fun normalizeStreamArguments(raw: String): String {
        if (raw.trimStart().startsWith("{")) return raw
        val repaired = raw.substringAfterLast("{", raw).let { fragment ->
            if (fragment.contains("\"}")) "{\"path\":\"${fragment.substringBeforeLast("\"}")}\"}" else raw
        }
        return repaired
    }

    private fun failure(connection: HttpURLConnection): ModelResponse.Failure {
        val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return ModelResponse.Failure("Model HTTP ${connection.responseCode}: ${body.take(500)}")
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
                json.optString("tool").isNotBlank() -> ModelResponse.ToolCall(
                    name = json.getString("tool"),
                    arguments = json.optJSONObject("arguments")?.toString() ?: json.optString("arguments", "{}"),
                    thought = json.optString("thought")
                )
                json.optString("content").isNotBlank() -> ModelResponse.Text(json.getString("content"))
                else -> ModelResponse.Failure("Model JSON did not contain content or tool")
            }
        } catch (_: Exception) {
            ModelResponse.Text(trimmed)
        }
    }
}

object AgentModelProtocol {
    val SYSTEM = """
You are the coding agent's execution model. Work in bounded, observable steps.
You may inspect files, search the project, search reference material, research the web, edit files, run checks, and inspect results.
For experimental or theoretical requests, research the underlying theory, competing hypotheses, prior art, prototypes, benchmarks, counterexamples, failed attempts, limitations, and falsifiable tests. Do not present speculation as established fact.
Never claim a change happened unless a tool result proves it. Never invent file contents or command output.
Use a tool call when more information or an action is required. Use final text only when the task is complete or needs explicit user input.
Before editing: inspect the relevant files. After editing: run the selected checks. If checks fail: diagnose the actual output, repair, and rerun within the attempt budget.
Return tool calls as JSON: {"tool":"name","arguments":{...},"thought":"short reason"}.
Return final responses as JSON: {"content":"..."}.
""".trimIndent()

    fun tools(): List<ModelToolDefinition> = listOf(
        ModelToolDefinition("list_files", "List project files in a relative directory", """{"type":"object","properties":{"path":{"type":"string"}},"required":[]}"""),
        ModelToolDefinition("read_file", "Read one project-relative text file", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""),
        ModelToolDefinition("search_project", "Search project text and return matching lines", """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""),
        ModelToolDefinition("search_knowledge", "Search imported reference material", """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""),
        ModelToolDefinition("research_web", "Search the internet across documentation, implementations, theory, experiments, benchmarks, criticism, and failure modes; never stop at one result", """{"type":"object","properties":{"query":{"type":"string"},"mode":{"type":"string"},"sources":{"type":"integer"}},"required":["query"]}"""),
        ModelToolDefinition("replace_text", "Stage an exact replacement as a pending typed change proposal. This never writes immediately.", """{"type":"object","properties":{"path":{"type":"string"},"oldText":{"type":"string"},"newText":{"type":"string"},"reason":{"type":"string"}},"required":["path","oldText","newText"]}"""),
        ModelToolDefinition("create_file", "Stage a new file as a pending typed change proposal. This never writes immediately.", """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"reason":{"type":"string"}},"required":["path","content"]}"""),
        ModelToolDefinition("approve_change", "Record one explicit owner approval for a pending change. Two approvals are required before writing.", """{"type":"object","properties":{"id":{"type":"string"},"ownerVerified":{"type":"boolean"},"ownerLabel":{"type":"string"}},"required":["id","ownerVerified","ownerLabel"]}"""),
        ModelToolDefinition("reject_change", "Reject a pending change proposal without writing it.", """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}"""),
        ModelToolDefinition("run_command", "Run a bounded command in the project root", """{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}"""),
        ModelToolDefinition("verify", "Run static verification and selected project checks", """{"type":"object","properties":{},"required":[]}""")
    )
}
