package com.codingagent.core

import android.content.Context
import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.NexaSdk
import com.nexa.sdk.bean.ChatMessage
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmCreateInput
import com.nexa.sdk.bean.LlmStreamResult
import com.nexa.sdk.bean.ModelConfig
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NexaLocalModelGateway(
    context: Context,
    private val modelDirectory: File,
    private val modelName: String = Qwen3NpuPackage.modelName
) : ModelGateway {
    private val wrapper: LlmWrapper

    init {
        awaitSdkInitialization(context)
        check(modelDirectory.resolve("nexa.manifest").isFile) {
            "Nexa model manifest is missing: ${modelDirectory.absolutePath}"
        }
        val entry = modelDirectory.resolve("files-1-1.nexa")
        check(entry.isFile && entry.length() > 0L) {
            "Nexa model entry missing: ${entry.absolutePath}"
        }
        val input = LlmCreateInput(
            model_name = "qwen3-4b",
            model_path = entry.absolutePath,
            config = ModelConfig(
                nCtx = 4096,
                max_tokens = 2048
            ),
            plugin_id = "npu"
        )
        wrapper = runBlocking {
            val result = LlmWrapper.Companion.builder().llmCreateInput(input).build()
            result.getOrElse { err ->
                val detail = buildString {
                    append(err.message.orEmpty())
                    append(" | cause=")
                    append(err.cause?.message.orEmpty())
                    append(" | class=")
                    append(err.javaClass.name)
                }
                error("Nexa model load failed: $detail")
            }
        }
    }

    override fun complete(request: ModelRequest): ModelResponse = stream(request) {}

    override fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse = try {
        val prompt = formatWithChatTemplate(request)
        val output = StringBuilder()
        runBlocking {
            wrapper.generateStreamFlow(prompt, GenerationConfig().apply { maxTokens = 2048 }).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> {
                        output.append(result.text)
                        onDelta(result.text)
                    }
                    is LlmStreamResult.Error -> {
                        val t = result.throwable
                        error(
                            buildString {
                                append(t.message.orEmpty().ifBlank { t.javaClass.simpleName })
                                t.cause?.message?.let { append(" | cause=").append(it) }
                            }
                        )
                    }
                    is LlmStreamResult.Completed -> Unit
                }
            }
        }
        if (output.isBlank()) {
            ModelResponse.Failure("Nexa returned no output")
        } else {
            JsonModelResponseParser().parse(output.toString())
        }
    } catch (error: Exception) {
        ModelResponse.Failure("Nexa local inference failed: ${error.message.orEmpty()}")
    }

    fun close() {
        wrapper.close()
    }

    private fun awaitSdkInitialization(context: Context) {
        val completed = CountDownLatch(1)
        var failure: String? = null
        NexaSdk.Companion.getInstance().init(context, object : NexaSdk.InitCallback {
            override fun onSuccess() {
                completed.countDown()
            }

            override fun onFailure(message: String) {
                failure = message
                completed.countDown()
            }
        })
        check(completed.await(60, TimeUnit.SECONDS)) { "Nexa SDK initialization timed out" }
        check(failure == null) { "Nexa SDK initialization failed: $failure" }
    }

    /**
     * NPU (and official Nexa Android) requires the model chat template before generateStreamFlow.
     * Raw concatenated prompts cause native generate failures (opaque codes like -221315576).
     * See Nexa Android NPU API: applyChatTemplate → generateStreamFlow(template.formattedText).
     */
    private fun formatWithChatTemplate(request: ModelRequest): String {
        val messages = ArrayList<ChatMessage>()
        val systemBody = buildString {
            append(request.system)
            if (request.researchRequired) {
                append("\n\nRESEARCH_GATE: satisfied by the attached multi-source research brief; ")
                append("do not skip research or claim knowledge without evidence.")
            }
        }
        if (systemBody.isNotBlank()) {
            messages += ChatMessage("system", systemBody)
        }
        request.transcript.forEach { msg ->
            val role = when (msg.role.lowercase()) {
                "assistant", "model" -> "assistant"
                "tool" -> "user" // NPU chat templates often lack tool role; fold into user
                else -> msg.role.lowercase().ifBlank { "user" }
            }
            val content = if (msg.role.equals("tool", ignoreCase = true)) {
                "tool ${msg.toolName.orEmpty()}: ${msg.content}"
            } else {
                msg.content
            }
            if (content.isNotBlank()) messages += ChatMessage(role, content)
        }
        messages += ChatMessage("user", request.user)

        val toolsJson = toolsJsonOrNull(request)
        val templateResult = wrapper.applyChatTemplate(
            messages.toTypedArray(),
            toolsJson,
            /* enableThinking = */ false
        )
        val formatted = templateResult.getOrElse { err ->
            error(
                "Nexa applyChatTemplate failed: ${err.message.orEmpty()} | cause=${err.cause?.message.orEmpty()}"
            )
        }
        val text = formatted.formattedText
        check(text.isNotBlank()) { "Nexa applyChatTemplate returned empty formattedText" }
        return text
    }

    private fun toolsJsonOrNull(request: ModelRequest): String? {
        if (request.tools.isEmpty()) return null
        val arr = JSONArray()
        request.tools.forEach { tool ->
            arr.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", JSONObject(tool.inputSchema.ifBlank { "{}" }))
                    )
            )
        }
        return arr.toString()
    }
}
