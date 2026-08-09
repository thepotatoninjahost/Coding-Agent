package com.codingagent.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class HttpModelGateway(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    private val timeoutMillis: Int = 30_000
) : ModelGateway {
    override fun complete(request: ModelRequest): ModelResponse {
        if (apiKey.isBlank()) return ModelResponse.Failure("Model API key is not configured")
        return try {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            val payload = JSONObject()
                .put("model", model)
                .put("messages", messages(request))
                .put("temperature", 0.1)
                .put("response_format", JSONObject().put("type", "json_object"))
            connection.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) ModelResponse.Failure("Model HTTP ${connection.responseCode}: ${body.take(400)}")
            else parseProviderResponse(body)
        } catch (error: IOException) {
            ModelResponse.Failure("Model network failure: ${error.message ?: error.javaClass.simpleName}")
        } catch (error: Exception) {
            ModelResponse.Failure("Model response failure: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun messages(request: ModelRequest): JSONArray = JSONArray().apply {
        put(JSONObject().put("role", "system").put("content", request.system))
        put(JSONObject().put("role", "user").put("content", request.user))
        request.transcript.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
    }

    private fun parseProviderResponse(body: String): ModelResponse {
        val content = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        if (content.isBlank()) return ModelResponse.Failure("Model returned no message content")
        return JsonModelResponseParser().parse(content)
    }
}
