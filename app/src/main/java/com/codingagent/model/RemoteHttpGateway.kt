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
    /**
     * openrouter/free picks a different free model on every request. After the first
     * capable model answers, later steps in this task stay on that same model.
     */
    private val pinnedModel = AtomicReference<String?>(null)
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
        val connection = try {
            openConnection(generation)
        } catch (error: IOException) {
            return ModelResponse.Failure(
                "Model request failed: ${ModelConnectionProbe.sanitizeProbeError(error.message.orEmpty().ifBlank { error.javaClass.simpleName })}"
            )
        } ?: return if (isCancelled(generation)) ModelResponse.Failure("Cancelled") else ModelResponse.Failure("The code helper is not fully set up.")
        return try {
            configure(connection)
            val body = requestBody(request)
            body.put("stream", true)
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            if (isCancelled(generation)) return ModelResponse.Failure("Cancelled")
            val code = connection.responseCode
            if (code in 300..399) {
                return ModelResponse.Failure("The helper tried to send me to a different web address. I stopped so your secret password was not sent there.")
            }
            if (code !in 200..299) return failure(connection)
            parseStreamedBody(bounded(connection.inputStream), onDelta, generation)
        } catch (error: IOException) {
            ModelResponse.Failure("Model request failed: ${ModelConnectionProbe.sanitizeProbeError(error.message.orEmpty().ifBlank { error.javaClass.simpleName })}")
        } catch (error: Exception) {
            ModelResponse.Failure("Model response could not be processed: ${ModelConnectionProbe.sanitizeProbeError(error.message.orEmpty().ifBlank { error.javaClass.simpleName })}")
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
        var sawMultipleToolCalls = false
        var sawDone = false
        var malformedPayload = false
        var finishReason: String? = null

        input.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            for (rawLine in lines) {
                if (isCancelled(generation)) return@useLines
                val line = rawLine.trim()
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") {
                    sawDone = true
                    break
                }
                val chunk = try {
                    JSONObject(payload)
                } catch (_: Exception) {
                    // A malformed data event may contain missing text or tool arguments.
                    // Do not silently skip it and return a plausible-looking partial answer.
                    malformedPayload = true
                    break
                }
                noteServedModel(chunk.optString("model"))
                val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
                choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let { finishReason = it }
                val delta = choice.optJSONObject("delta") ?: continue

                val textPiece = delta.optString("content")
                if (textPiece.isNotEmpty()) {
                    mergedContent.append(textPiece)
                    onDelta(textPiece)
                }

                val calls = delta.optJSONArray("tool_calls")
                if (calls != null) {
                    for (i in 0 until calls.length()) {
                        val call = calls.optJSONObject(i) ?: continue
                        if (call.optInt("index", 0) != 0) {
                            // One tool runs this turn. Extra calls stay visible in the reply
                            // so the task can continue with the next call instead of failing.
                            sawMultipleToolCalls = true
                            continue
                        }
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
        if (malformedPayload) {
            return ModelResponse.Failure("Model stream contained a malformed event; partial content was rejected.")
        }
        if (finishReason.isTruncatedFinishReason()) return truncatedResponseFailure(finishReason!!)
        if (!sawDone && finishReason.isNullOrBlank()) {
            return ModelResponse.Failure("Model stream ended without a completion marker; partial content was rejected.")
        }
        if (sawMultipleToolCalls) {
            if (mergedContent.isNotEmpty()) mergedContent.append('\n')
            mergedContent.append(EXTRA_TOOL_CALL_NOTE)
        }

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
        val connection = try {
            openConnection(generation)
        } catch (error: IOException) {
            return ModelResponse.Failure(
                "Model request failed: ${ModelConnectionProbe.sanitizeProbeError(error.message.orEmpty().ifBlank { error.javaClass.simpleName })}"
            )
        } ?: return if (isCancelled(generation)) ModelResponse.Failure("Cancelled") else ModelResponse.Failure("The code helper is not fully set up.")
        return try {
            configure(connection)
            connection.outputStream.use { it.write(requestBody(request).toString().toByteArray(StandardCharsets.UTF_8)) }
            if (isCancelled(generation)) return ModelResponse.Failure("Cancelled")
            val code = connection.responseCode
            if (code in 300..399) {
                return ModelResponse.Failure("The helper tried to send me to a different web address. I stopped so your secret password was not sent there.")
            }
            if (code !in 200..299) return failure(connection)
            parseCompletionBody(bounded(connection.inputStream).bufferedReader().use { it.readText() })
        } catch (error: IOException) {
            ModelResponse.Failure("Model request failed: ${ModelConnectionProbe.sanitizeProbeError(error.message.orEmpty().ifBlank { error.javaClass.simpleName })}")
        } catch (error: Exception) {
            ModelResponse.Failure("Model response could not be processed: ${ModelConnectionProbe.sanitizeProbeError(error.message.orEmpty().ifBlank { error.javaClass.simpleName })}")
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
        noteServedModel(json.optString("model"))
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val finishReason = choice?.optString("finish_reason")
        if (finishReason.isTruncatedFinishReason()) return truncatedResponseFailure(finishReason!!)
        val message = choice?.optJSONObject("message") ?: choice?.optJSONObject("delta")
        if (message != null) return responseFromChatMessage(message)
        val textField = choice?.optString("text").orEmpty()
        if (textField.isNotBlank()) return JsonModelResponseParser().parse(textField)
        return JsonModelResponseParser().parse(trimmed)
    }

    private fun String?.isTruncatedFinishReason(): Boolean {
        val normalized = this?.lowercase() ?: return false
        return normalized in setOf("length", "max_tokens", "max_output_tokens", "token_limit")
    }

    private fun truncatedResponseFailure(reason: String): ModelResponse.Failure =
        ModelResponse.Failure(
            "Model response ended at a provider token limit (finish_reason=$reason); partial content was rejected. " +
                "Check the configured model's output/context limits or split the input into smaller parts."
        )

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
        ModelEndpointPolicy.refuseResolvedDestination(endpoint)?.let { reason ->
            throw java.io.IOException(reason)
        }
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
        connection.instanceFollowRedirects = false
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
            .put("model", modelForRequest())
            .put("messages", messages)
            .put("temperature", 0.1)
            .put("stream", false)
        if (request.tools.isNotEmpty()) {
            // The agent executes one tool per turn. Prevent OpenAI-compatible models
            // from returning parallel calls that this single-call protocol would drop.
            body.put("parallel_tool_calls", false)
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
            val extra = calls.length() - 1
            val thought = buildString {
                append(extractMessageText(message))
                if (extra > 0) {
                    if (isNotEmpty()) append('\n')
                    append(EXTRA_TOOL_CALL_NOTE)
                }
            }
            return ModelResponse.ToolCall(
                name,
                function?.optString("arguments", "{}") ?: "{}",
                thought,
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
                                val type = part.optString("type").lowercase()
                                if (!type.contains("reasoning") && type != "analysis") {
                                    val text = part.optString("text").ifBlank { part.optString("content") }
                                    if (text.isNotBlank()) append(text)
                                }
                            }
                        }
                    }
                }.trim()
                if (parts.isNotBlank()) return parts
            }
            is JSONObject -> {
                val type = direct.optString("type").lowercase()
                if (!type.contains("reasoning") && type != "analysis") {
                    val text = direct.optString("text").ifBlank { direct.optString("content") }
                    if (text.isNotBlank()) return text.trim()
                }
            }
        }
        for (key in listOf("refusal", "output_text")) {
            val v = message.optString(key)
            if (v.isNotBlank()) return v.trim()
        }
        return ""
    }

    private fun failure(connection: HttpURLConnection): ModelResponse.Failure {
        val body = readErrorBody(connection)
        val readable = readableError(body)
        val snippet = readable.take(ERROR_SNIPPET_CHARS)
        val suffix = if (readable.length > ERROR_SNIPPET_CHARS) "…" else ""
        // Keep the status code in the text. Retry and the plain-English rewrite both look for it.
        return ModelResponse.Failure("Model HTTP ${connection.responseCode}: ${snippet.ifBlank { "(empty body)" }}$suffix")
    }

    private fun readableError(body: String): String {
        val sanitized = ModelConnectionProbe.sanitizeProbeError(body).trim()
        val json = runCatching { JSONObject(sanitized) }.getOrNull() ?: return sanitized
        val nested = json.optJSONObject("error")?.optString("message").orEmpty()
        val top = json.optString("message")
        val message = nested.ifBlank { top }.trim()
        return ModelConnectionProbe.sanitizeProbeError(message).ifBlank { sanitized }
    }

    private fun readErrorBody(connection: HttpURLConnection): String {
        val stream = connection.errorStream ?: connection.inputStream ?: return ""
        return runCatching { bounded(stream, ERROR_BODY_BYTES).bufferedReader().use { it.readText() } }.getOrDefault("")
    }

    private fun modelForRequest(): String = pinnedModel.get()?.takeIf { it.isNotBlank() } ?: model

    private fun noteServedModel(served: String?) {
        val name = served?.trim().orEmpty()
        if (name.isBlank() || name.equals("openrouter/free", ignoreCase = true)) return
        if (!model.equals("openrouter/free", ignoreCase = true)) return
        if (name in WEAK_FREE_MODELS) return
        pinnedModel.compareAndSet(null, name)
    }

    private fun bounded(input: java.io.InputStream, maxBytes: Int = MAX_RESPONSE_BYTES): java.io.InputStream =
        object : java.io.FilterInputStream(input) {
            private var total = 0
            override fun read(): Int {
                val value = super.read()
                if (value >= 0) addBytes(1)
                return value
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val readCount = super.read(buffer, offset, length)
                if (readCount > 0) addBytes(readCount)
                return readCount
            }
            private fun addBytes(readCount: Int) {
                total += readCount
                if (total > maxBytes) {
                    throw IOException("Model response exceeded $maxBytes bytes and was rejected")
                }
            }
        }

    companion object {
        private const val USER_AGENT =
            "Coding-Agent/1.0 (Android; https://github.com/thepotatoninjahost/Coding-Agent)"
        private const val MAX_RESPONSE_BYTES = 4_000_000
        private const val ERROR_BODY_BYTES = 16_384
        private const val ERROR_SNIPPET_CHARS = 2_000
        private const val EXTRA_TOOL_CALL_NOTE =
            "Only the first tool call is being run. Send any other tool call on the next turn."
        private val WEAK_FREE_MODELS = setOf(
            "liquid/lfm-2.5-2.6b:free",
            "nvidia/nemotron-3.5-content-safety:free"
        )
    }
}
