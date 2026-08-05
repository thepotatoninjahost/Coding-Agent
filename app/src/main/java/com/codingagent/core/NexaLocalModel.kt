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
                max_tokens = 1024
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

    override fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse {
        val prompt: String
        try {
            prompt = formatWithChatTemplate(request)
        } catch (error: Exception) {
            return ModelResponse.Failure(
                "Nexa applyChatTemplate failed: ${error.message.orEmpty()}"
            )
        }
        return try {
            val output = StringBuilder()
            runBlocking {
                wrapper.generateStreamFlow(
                    prompt,
                    GenerationConfig().apply { maxTokens = 1024 }
                ).collect { result ->
                    when (result) {
                        is LlmStreamResult.Token -> {
                            output.append(result.text)
                            onDelta(result.text)
                        }
                        is LlmStreamResult.Error -> {
                            val t = result.throwable
                            error(
                                buildString {
                                    append("generate:")
                                    append(t.message.orEmpty().ifBlank { t.javaClass.simpleName })
                                    t.cause?.message?.let { append(" | cause=").append(it) }
                                    append(" | promptChars=").append(prompt.length)
                                }
                            )
                        }
                        is LlmStreamResult.Completed -> Unit
                    }
                }
            }
            if (output.isBlank()) {
                ModelResponse.Failure("Nexa returned no output | promptChars=${prompt.length}")
            } else {
                JsonModelResponseParser().parse(output.toString())
            }
        } catch (error: Exception) {
            ModelResponse.Failure(
                "Nexa local inference failed: ${error.message.orEmpty()} | promptChars=${prompt.length}"
            )
        }
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
     * NPU requires applyChatTemplate before generateStreamFlow.
     * Official Android samples pass tools=null — large tool JSON on NPU is unsupported / unstable.
     * Tool instructions stay in the system text; model still returns JSON tool calls from the prompt.
     */
    private fun formatWithChatTemplate(request: ModelRequest): String = runBlocking {
        val messages = ArrayList<ChatMessage>()
        val systemBody = buildString {
            append(request.system.take(2_500))
            if (request.researchRequired) {
                append("\n\nRESEARCH_GATE: use the attached research brief; do not invent sources.")
            }
        }
        if (systemBody.isNotBlank()) {
            messages += ChatMessage("system", systemBody)
        }
        request.transcript.takeLast(6).forEach { msg ->
            val role = when (msg.role.lowercase()) {
                "assistant", "model" -> "assistant"
                "tool" -> "user"
                else -> msg.role.lowercase().ifBlank { "user" }
            }
            val content = if (msg.role.equals("tool", ignoreCase = true)) {
                "tool ${msg.toolName.orEmpty()}: ${msg.content}".take(1_500)
            } else {
                msg.content.take(1_500)
            }
            if (content.isNotBlank()) messages += ChatMessage(role, content)
        }
        messages += ChatMessage("user", request.user.take(4_000))

        val templateResult = wrapper.applyChatTemplate(
            messages.toTypedArray(),
            /* tools = */ null,
            /* enableThinking = */ false,
            /* addGenerationPrompt = */ true
        )
        val formatted = templateResult.getOrElse { err ->
            error(
                "${err.message.orEmpty()} | cause=${err.cause?.message.orEmpty()}"
            )
        }
        val text = formatted.formattedText
        check(text.isNotBlank()) { "empty formattedText" }
        text
    }
}
