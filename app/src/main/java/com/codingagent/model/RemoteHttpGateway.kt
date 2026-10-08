package com.codingagent.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * ONE JOB: HTTP OpenAI-compatible /chat/completions.
 */
class RemoteHttpGateway(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    private val timeoutMillis: Int = 60_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val activeConnection: AtomicReference<HttpURLConnection?> = AtomicReference(null),
    private val cancellationGeneration: AtomicLong = AtomicLong(0L)
) : ModelGateway {
    override fun cancel() {
        cancellationGeneration.incrementAndGet()
        activeConnection.getAndSet(null)?.disconnect()
    }
    override fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse {
        val generation = cancellationGeneration.get()
        if (isCancelled(generation)) return ModelResponse.Failure("Cancelled")
        val first = streamOnce(request, onDelta, generation)
        if (isCancelled(generation)) return ModelResponse.Failure("Cancelled")
        if (first is ModelResponse.Failure && isEmptyContentFailure(first.message) && request.tools.isNotEmpty()) {
            return streamOnce(request.copy(tools = emptyList()), onDelta, generation)
        }
        return first
    }

    private fun streamOnce(request: ModelRequest, onDelta: (String) -> Unit, generation: Long): ModelResponse {
        if (isCancelled(generation)) return ModelResponse.Failure("Cancelled")
        val connection = openConnection(generation) ?: return if (isCancelled(generation)) ModelResponse.Failure("Cancelled") else ModelResponse.Failure("Model gateway configuration is incomplete")
        return try {
            configure(connection)
            val body = requestBody(request)
            body.put("stream", true)
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            if (isCancelled(generation)) return ModelResponse.Failure("Cancelled")
            if (connection.responseCode !in 200..299) return failure(connection)
            parseStreamedBody(connection.inputStream, onDelta, generation)
        } catch (error: IOException) {
            ModelResponse.Failure("Model request failed: ${error.message.orEmpty().ifBlank { error.javaClass.simpleName }}")
        } catch (error: Exception) {
            ModelResponse.Failure("Model response could not be processed: ${error.message.orEmpty().ifBlank { error.javaClass.simpleName }}")
        } finally {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    /**
     * Reconstructs a single OpenAI-style chat message from Server-Sent-Events deltas by
     * accumulating text content and tool-call arguments (which arrive split across many
     * chunks, keyed by index) into one merged JSONObject, then reuses the exact same
     * responseFromChatMessage() path that non-streaming responses already go through.
     * This avoids a second, divergent parsing implementation for the streamed case.
     */
    private fun parseStreamedBody(
        input: java.io.InputStream,
        onDelta: (String) -> Unit,
        generation: Long
    ): ModelResponse {
        val mergedContent = StringBuilder()
        var toolCallId: String? = null
        var toolCallName: String? = null
        val toolCallArguments = StringBuilder()
        var sawToolCall = false

        input.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            for (rawLine in lines) {
                if (isCancelled(generation)) return@useLines
                val line = rawLine.trim()
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue

                val textPiece = delta.optString("content")
                if (textPiece.isNotEmpty()) {
                    mergedContent.append(textPiece)
                    onDelta(textPiece)
                }

                val calls = delta.optJSONArray("tool_calls")
                if (calls != null) {
                    for (i in 0 until calls.length()) {
                        val call = calls.optJSONObject(i) ?: continue
                        if (call.optInt("index", 0) != 0) continue // this app acts on one tool call at a time
                        sawToolCall = true
                        call.optString("id").takeIf { it.isNotBlank() }?.let { toolCallId = it }
                        val function = call.optJSONObject("function")
                        function?.optString("name")?.takeIf { it.isNotBlank() }?.let { toolCallName = it }
                        function?.optString("arguments")?.let { toolCallArguments.append(it) }
                    }
                }
            }
        }

        if (isCancelled(generation)) return ModelResponse.Failure("Cancelled")

        val merged = JSONObject().put("role", "assistant")
        if (mergedContent.isNotEmpty()) merged.put("content", mergedContent.toString())
        if (sawToolCall && !toolCallName.isNullOrBlank()) {
            merged.put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", toolCallId ?: JSONObject.NULL)
                        .put("function", JSONObject().put("name", toolCallName).put("arguments", toolCallArguments.toString()))
                )
            )
        }
        return responseFromChatMessage(merged)
    }

    override fun complete(request: ModelRequest): ModelResponse {
        val generation = cancellationGeneration.get()
        if (isCancelled(generation)) return ModelResponse.Failure("Cancelled")
        val first = completeOnce(request, generation)
        if (isCancelled(generation)) return ModelResponse.Failure("Cancelled")
        if (first is ModelResponse.Failure && isEmptyContentFailure(first.message) && request.tools.isNotEmpty()) {
            return completeOnce(request.copy(tools = emptyList()), generation)
        }
        return first
    }

    private fun completeOnce(request: ModelRequest, generation: Long): ModelResponse {
        if (isCancelled(generation)) return ModelResponse.Failure("Cancelled")
        val connection = openConnection(generation) ?: return if (isCancelled(generation)) ModelResponse.Failure("Cancelled") else ModelResponse.Failure("Model gateway configuration is incomplete")
        return try {
            configure(connection)
            connection.outputStream.use { it.write(requestBody(request).toString().toByteArray(StandardCharsets.UTF_8)) }
            if (isCancelled(generation)) return ModelResponse.Failure("Cancelled")
            if (connection.responseCode !in 200..299) return failure(connection)
            parseCompletionBody(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (error: IOException) {
            ModelResponse.Failure("Model request failed: ${error.message.orEmpty().ifBlank { error.javaClass.simpleName }}")
        } catch (error: Exception) {
            ModelResponse.Failure("Model response could not be processed: ${error.message.orEmpty().ifBlank { error.javaClass.simpleName }}")
        } finally {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    private fun parseCompletionBody(text: String): ModelResponse {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ModelResponse.Failure("Model returned an empty response")
        val json = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return JsonModelResponseParser().parse(trimmed)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message") ?: choice?.optJSONObject("delta")
        if (message != null) return responseFromChatMessage(message)
        val textField = choice?.optString("text").orEmpty()
        if (textField.isNotBlank()) return JsonModelResponseParser().parse(textField)
        return JsonModelResponseParser().parse(trimmed)
    }

    private fun isEmptyContentFailure(message: String): Boolean {
        val lower = message.lowercase()
        return "empty response" in lower ||
            "no usable message content" in lower ||
            "did not contain content or tool" in lower ||
            "no streamed message content" in lower
    }

    private fun isCancelled(generation: Long): Boolean = cancellationGeneration.get() != generation

    private fun openConnection(generation: Long): HttpURLConnection? {
        if (endpoint.isBlank() || model.isBlank()) return null
        if (ModelEndpointPolicy.validate(endpoint) != null) return null
        if (apiKey.isBlank() && !ModelEndpointPolicy.isLocalEndpoint(endpoint)) return null
        val connection = connectionFactory(endpoint.trimEnd('/') + "/chat/completions")
        if (isCancelled(generation)) {
            connection.disconnect()
            return null
        }
        activeConnection.set(connection)
        if (isCancelled(generation) && activeConnection.compareAndSet(connection, null)) {
            connection.disconnect()
            return null
        }
        return connection
    }

    private fun configure(connection: HttpURLConnection) {
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.requestMethod = "POST"
        connection.doOutput = true
        if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        val host = runCatching { connection.url?.host.orEmpty() }.getOrDefault("")
        val applied = linkedMapOf<String, String>()
        if (host.contains("openrouter.ai")) {
            applied["HTTP-Referer"] = "https://github.com/thepotatoninjahost/Coding-Agent"
            applied["X-Title"] = "Coding-Agent"
        }
        applied.putAll(extraHeaders)
        for ((name, value) in applied) {
            if (name.isNotBlank() && value.isNotBlank()) connection.setRequestProperty(name, value)
        }
    }

    private fun requestBody(request: ModelRequest): JSONObject {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", request.system))
        request.transcript.forEach { message ->
            val serialized = JSONObject().put("role", message.role)
            // Same fallback for both branches below: an assistant tool_calls[].id and the
            // following tool message's tool_call_id MUST be byte-identical or a strict
            // OpenAI-compatible provider rejects the request on the very next turn. The old
            // code derived them independently ("call_$toolName" here, "" on the tool message),
            // which silently broke every turn after the first tool call whenever
            // message.toolCallId was null (recovered XML calls, providers that omit ids, etc.).
            // A tool-role ModelMessage never carries toolName, so the fallback can't be
            // reconstructed per-message — it must be a fixed sentinel shared by both sides.
            val resolvedToolCallId = message.toolCallId?.takeIf { it.isNotBlank() } ?: "call_unmatched"
            if (message.role == "assistant" && message.toolName != null) {
                serialized.put("content", JSONObject.NULL)
                serialized.put(
                    "tool_calls",
                    JSONArray().put(
                        JSONObject()
                            .put("id", resolvedToolCallId)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject().put("name", message.toolName).put("arguments", message.toolArguments ?: "{}")
                            )
                    )
                )
            } else {
                serialized.put("content", message.content)
                if (message.role == "tool") serialized.put("tool_call_id", resolvedToolCallId)
            }
            messages.put(serialized)
        }
        messages.put(JSONObject().put("role", "user").put("content", request.user))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.1)
            .put("stream", false)
        if (request.tools.isNotEmpty()) {
            body.put("tool_choice", "auto")
            body.put("tools", JSONArray().apply {
                request.tools.forEach { tool ->
                    val parameters = runCatching { JSONObject(tool.inputSchema) }.getOrElse { JSONObject().put("type", "object") }
                    put(
                        JSONObject().put("type", "function").put(
                            "function",
                            JSONObject().put("name", tool.name).put("description", tool.description).put("parameters", parameters)
                        )
                    )
                }
            })
        }
        return body
    }

    private fun responseFromChatMessage(message: JSONObject): ModelResponse {
        val calls = message.optJSONArray("tool_calls")
        if (calls != null && calls.length() > 0) {
            val first = calls.optJSONObject(0)
            val function = first?.optJSONObject("function") ?: first
            val name = function?.optString("name").orEmpty()
            if (name.isBlank()) return ModelResponse.Failure("Model returned a tool call without a function name")
            return ModelResponse.ToolCall(
                name,
                function?.optString("arguments", "{}") ?: "{}",
                extractMessageText(message),
                first?.optString("id")
            )
        }
        val legacy = message.optJSONObject("function_call")
        if (legacy != null) {
            val name = legacy.optString("name")
            if (name.isNotBlank()) {
                return ModelResponse.ToolCall(
                    name,
                    legacy.optString("arguments", "{}"),
                    extractMessageText(message),
                    message.optString("id").takeIf { it.isNotBlank() }
                )
            }
        }
        val content = extractMessageText(message)
        return if (content.isBlank()) {
            ModelResponse.Failure("Model returned no usable message content")
        } else {
            JsonModelResponseParser().parse(content)
        }
    }

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
                                val text = part.optString("text").ifBlank { part.optString("content") }
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
        val snippet = body.replace("\\s+".toRegex(), " ").trim().take(600)
        return ModelResponse.Failure("Model HTTP ${connection.responseCode}: ${snippet.ifBlank { "(empty body)" }}")
    }

    companion object {
        private const val USER_AGENT =
            "Coding-Agent/1.0 (Android; https://github.com/thepotatoninjahost/Coding-Agent)"
    }
}
