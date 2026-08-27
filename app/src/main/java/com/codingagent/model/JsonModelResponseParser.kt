package com.codingagent.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import com.codingagent.intake.TaskIntent

class JsonModelResponseParser {
    fun parse(raw: String): ModelResponse {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ModelResponse.Failure("Model returned an empty response")
        parseXmlToolCall(trimmed)?.let { return it }
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

    /**
     * Some providers (NVIDIA NIM and others) emit tools as XML in the text body
     * instead of OpenAI tool_calls. Treat that as a real tool call, not a final answer.
     */
    private fun parseXmlToolCall(raw: String): ModelResponse.ToolCall? {
        val text = raw.trim()
        if (!text.contains("<tool_call", ignoreCase = true) &&
            !text.contains("<function=", ignoreCase = true)
        ) {
            return null
        }
        val name = Regex(
            """<function\s*=\s*([A-Za-z0-9_]+)""",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.getOrNull(1)
            ?: Regex(
                """<tool_call[^>]*\bname\s*=\s*["']([A-Za-z0-9_]+)["']""",
                RegexOption.IGNORE_CASE
            ).find(text)?.groupValues?.getOrNull(1)
        if (name.isNullOrBlank()) return null
        val args = JSONObject()
        Regex(
            """<parameter\s*=\s*([A-Za-z0-9_]+)>\s*(.*?)\s*</parameter>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(text).forEach { match ->
            args.put(match.groupValues[1], match.groupValues[2].trim())
        }
        return ModelResponse.ToolCall(name, if (args.length() == 0) "{}" else args.toString())
    }
}

