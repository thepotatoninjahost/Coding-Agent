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
                json.optString("text").isNotBlank() -> ModelResponse.Text(json.getString("text"))
                json.has("reasoning_content") || json.has("reasoning") ->
                    ModelResponse.Failure("Model returned internal reasoning without a user-facing answer")
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
        parseHarmonyToolCall(text)?.let { return it }
        parseEmbeddedToolJson(text)?.let { return it }
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

    /** gpt-oss on Groq often puts the call in a harmony channel instead of tool_calls. */
    private fun parseHarmonyToolCall(text: String): ModelResponse.ToolCall? {
        val marker = Regex("""to=functions\.([A-Za-z0-9_]+)""").find(text) ?: return null
        val name = marker.groupValues[1]
        val after = text.substring(marker.range.last + 1)
        val jsonStart = after.indexOf('{')
        if (jsonStart < 0) return null
        val json = sliceJsonObject(after.substring(jsonStart)) ?: return null
        return ModelResponse.ToolCall(name, json)
    }

    /** Nemotron sometimes wraps a JSON tool call in tool_call tags instead of XML parameters. */
    private fun parseEmbeddedToolJson(text: String): ModelResponse.ToolCall? {
        if (!text.contains("<tool_call", ignoreCase = true)) return null
        val inner = text.substringAfter("<tool_call", "").substringAfter(">", "").substringBefore("</tool_call>")
        val start = inner.indexOf('{')
        if (start < 0) return null
        val json = sliceJsonObject(inner.substring(start)) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val name = obj.optString("name").ifBlank { obj.optString("tool") }
        if (name.isBlank()) return null
        val arguments = when (val raw = obj.opt("arguments")) {
            is JSONObject -> raw.toString()
            is String -> raw.ifBlank { "{}" }
            else -> "{}"
        }
        return ModelResponse.ToolCall(name.removePrefix("functions."), arguments)
    }

    private fun sliceJsonObject(text: String): String? {
        var depth = 0
        var quote = false
        var escaped = false
        text.forEachIndexed { index, char ->
            if (quote) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quote = false
                return@forEachIndexed
            }
            when (char) {
                '"' -> quote = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(0, index + 1)
                }
            }
        }
        return null
    }
}

