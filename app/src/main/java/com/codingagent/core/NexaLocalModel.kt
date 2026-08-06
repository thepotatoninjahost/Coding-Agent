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
    private val lock = Any()

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
                nCtx = 2048,
                max_tokens = 512,
                enable_thinking = false,
                npu_model_folder_path = modelDirectory.absolutePath
            ),
            plugin_id = "npu"
        )
        wrapper = runBlocking {
            val result = LlmWrapper.Companion.builder().llmCreateInput(input).build()
            result.getOrElse { err ->
                error(
                    "Nexa model load failed: ${err.message.orEmpty()} | cause=${err.cause?.message.orEmpty()}"
                )
            }
        }
    }

    override fun complete(request: ModelRequest): ModelResponse = stream(request) {}

    override fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse = synchronized(lock) {
        try {
            runBlocking {
                runCatching { wrapper.reset() }

                val messages = buildMessages(request)
                val templateResult = wrapper.applyChatTemplate(
                    messages.toTypedArray(),
                    /* tools = */ null,
                    /* enableThinking = */ false,
                    /* addGenerationPrompt = */ true
                )
                val formatted = templateResult.getOrElse { err ->
                    return@runBlocking ModelResponse.Failure(
                        "Nexa applyChatTemplate failed: ${err.message.orEmpty()} | cause=${err.cause?.message.orEmpty()}"
                    )
                }
                val prompt = formatted.formattedText
                if (prompt.isBlank()) {
                    return@runBlocking ModelResponse.Failure("Nexa applyChatTemplate returned empty formattedText")
                }

                val output = StringBuilder()
                val config = GenerationConfig().apply {
                    maxTokens = 512
                }
                wrapper.generateStreamFlow(prompt, config).collect { result ->
                    when (result) {
                        is LlmStreamResult.Token -> {
                            output.append(result.text)
                            onDelta(result.text)
                        }
                        is LlmStreamResult.Error -> {
                            val t = result.throwable
                            error(
                                "generate:${t.message.orEmpty().ifBlank { t.javaClass.simpleName }}" +
                                    " | promptChars=${prompt.length}"
                            )
                        }
                        is LlmStreamResult.Completed -> Unit
                    }
                }
                if (output.isBlank()) {
                    ModelResponse.Failure("Nexa returned no output | promptChars=${prompt.length}")
                } else {
                    JsonModelResponseParser().parse(output.toString())
                }
            }
        } catch (error: Exception) {
            ModelResponse.Failure(
                "Nexa local inference failed: ${error.message.orEmpty()}"
            )
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching { wrapper.close() }
        }
    }

    private fun buildMessages(request: ModelRequest): ArrayList<ChatMessage> {
        val messages = ArrayList<ChatMessage>()
        val systemBody = request.system.take(2_000)
        if (systemBody.isNotBlank()) {
            messages += ChatMessage("system", systemBody)
        }
        request.transcript.takeLast(4).forEach { msg ->
            val role = when (msg.role.lowercase()) {
                "assistant", "model" -> "assistant"
                "tool" -> "user"
                else -> msg.role.lowercase().ifBlank { "user" }
            }
            val content = msg.content.take(1_200)
            if (content.isNotBlank()) messages += ChatMessage(role, content)
        }
        messages += ChatMessage("user", request.user.take(3_000))
        return messages
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
}
