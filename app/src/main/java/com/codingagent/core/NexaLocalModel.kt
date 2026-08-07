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

/**
 * On-device Nexa NPU gateway.
 *
 * Official NPU sample path:
 *   applyChatTemplate(messages, null, false)  // tools must be null
 *   generateStreamFlow(formattedText, GenerationConfig())
 *
 * Tool calling uses the text JSON protocol in AgentModelProtocol.SYSTEM.
 * Embedding tool schemas into applyChatTemplate is what inflated prompts to ~3k chars.
 */
class NexaLocalModelGateway(
    context: Context,
    private val modelDirectory: File,
    private val modelName: String = Qwen3NpuPackage.modelName
) : ModelGateway {
    private val wrapper: LlmWrapper
    private val lock = Any()

    /** Soft budget for formatted prompt before generate. Measured failure band was ~3069. */
    private val maxFormattedChars = 2000

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
                error(
                    "Nexa model load failed: ${err.message.orEmpty()} | cause=${err.cause?.message.orEmpty()} | class=${err.javaClass.name}"
                )
            }
        }
    }

    override fun complete(request: ModelRequest): ModelResponse = stream(request) {}

    override fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse = synchronized(lock) {
        var promptChars = 0
        var toolsAttached = false
        try {
            runCatching { wrapper.javaClass.getMethod("stopStream").invoke(wrapper) }
            runCatching { wrapper.javaClass.getMethod("reset").invoke(wrapper) }

            val prompt = formatWithChatTemplate(request)
            promptChars = prompt.length
            if (promptChars > maxFormattedChars) {
                return ModelResponse.Failure(
                    "Nexa prompt over budget (promptChars=$promptChars max=$maxFormattedChars toolsAttached=$toolsAttached). Shorten the user request."
                )
            }

            val output = StringBuilder()
            runBlocking {
                val genConfig = GenerationConfig().apply {
                    maxTokens = 1024
                    runCatching {
                        val field = this::class.java.getDeclaredField("nPast")
                        field.isAccessible = true
                        field.set(this, 0)
                    }
                }
                wrapper.generateStreamFlow(prompt, genConfig).collect { result ->
                    when (result) {
                        is LlmStreamResult.Token -> {
                            output.append(result.text)
                            onDelta(result.text)
                        }
                        is LlmStreamResult.Error -> {
                            val t = result.throwable
                            error(
                                buildString {
                                    append(t.javaClass.name)
                                    append(": ")
                                    append(t.message.orEmpty().ifBlank { "(no message)" })
                                    t.cause?.let { c ->
                                        append(" | cause=")
                                        append(c.javaClass.name)
                                        append(": ")
                                        append(c.message.orEmpty())
                                    }
                                    append(" | promptChars=").append(promptChars)
                                    append(" | toolsAttached=").append(toolsAttached)
                                }
                            )
                        }
                        is LlmStreamResult.Completed -> Unit
                    }
                }
            }
            if (output.isBlank()) {
                ModelResponse.Failure("Nexa returned no output | promptChars=$promptChars | toolsAttached=$toolsAttached")
            } else {
                JsonModelResponseParser().parse(output.toString())
            }
        } catch (error: Exception) {
            ModelResponse.Failure(
                "Nexa local inference failed: ${error.message.orEmpty().ifBlank { error.javaClass.simpleName }} | promptChars=$promptChars | toolsAttached=$toolsAttached"
            )
        }
    }

    fun close() {
        runCatching { wrapper.javaClass.getMethod("stopStream").invoke(wrapper) }
        runCatching { wrapper.javaClass.getMethod("reset").invoke(wrapper) }
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
     * NPU requires applyChatTemplate(...).formattedText.
     * tools argument is always null (official Nexa Android NPU sample).
     */
    private fun formatWithChatTemplate(request: ModelRequest): String {
        val messages = ArrayList<ChatMessage>()
        val systemBody = request.system.take(600)
        if (systemBody.isNotBlank()) {
            messages += ChatMessage("system", systemBody)
        }
        // At most two prior transcript turns, heavily truncated.
        request.transcript.takeLast(2).forEach { msg ->
            val role = when (msg.role.lowercase()) {
                "assistant", "model" -> "assistant"
                "tool" -> "user"
                else -> msg.role.lowercase().ifBlank { "user" }
            }
            val content = if (msg.role.equals("tool", ignoreCase = true)) {
                "tool ${msg.toolName.orEmpty()}: ${msg.content.take(600)}"
            } else {
                msg.content.take(800)
            }
            if (content.isNotBlank()) messages += ChatMessage(role, content)
        }
        messages += ChatMessage("user", request.user.take(1500))

        // Official path: tools = null
        val templateResult = wrapper.applyChatTemplate(
            messages.toTypedArray(),
            null,
            /* enableThinking = */ false
        )
        val formatted = templateResult.getOrElse { err ->
            error(
                "Nexa applyChatTemplate failed: ${err.message.orEmpty()} | cause=${err.cause?.message.orEmpty()} | class=${err.javaClass.name}"
            )
        }
        val text = formatted.formattedText
        check(text.isNotBlank()) { "Nexa applyChatTemplate returned empty formattedText" }
        return text
    }
}
