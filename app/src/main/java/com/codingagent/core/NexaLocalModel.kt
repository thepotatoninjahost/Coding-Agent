package com.codingagent.core

import android.content.Context
import android.util.Log
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
 * On-device Nexa NPU gateway (Qwen3-4B mobile package).
 *
 * Session notes (ai.nexa:core 0.0.24):
 * - Official samples: applyChatTemplate(messages, tools=null, enableThinking=false) → generateStreamFlow.
 * - applyChatTemplate is suspend and must be called from a coroutine / runBlocking.
 * - reset() is suspend and returns Int (native session clear / KV wipe).
 * - stopStream() cancels an in-flight stream before reset/generate.
 * - We stopStream + reset only when the previous call left a dirty session
 *   (error, cancel, or non-completed stream), not on every successful turn.
 * - Soft prompt budget ~2000 chars after template to avoid NPU overflow.
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

    /** True after a failed/cancelled stream until a successful reset or clean generate completes. */
    @Volatile
    private var sessionDirty: Boolean = false

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
                val prep = prepareSession()
                if (prep != null) {
                    Log.w(TAG, "NPU session prepare: $prep")
                }

                val messages = buildMessages(request)
                // Official path: tools = null; suspend API requires coroutine context
                val templateResult = wrapper.applyChatTemplate(
                    messages.toTypedArray(),
                    /* tools = */ null,
                    /* enableThinking = */ false,
                    /* addGenerationPrompt = */ true
                )
                val formatted = templateResult.getOrElse { err ->
                    sessionDirty = true
                    return@runBlocking ModelResponse.Failure(
                        "Nexa applyChatTemplate failed: ${err.message.orEmpty()} | cause=${err.cause?.message.orEmpty()}" +
                            (prep?.let { " | session=$it" } ?: "")
                    )
                }
                val prompt = formatted.formattedText
                if (prompt.isBlank()) {
                    sessionDirty = true
                    return@runBlocking ModelResponse.Failure("Nexa applyChatTemplate returned empty formattedText")
                }
                if (prompt.length > maxFormattedChars) {
                    sessionDirty = true
                    return@runBlocking ModelResponse.Failure(
                        "Nexa prompt over budget (promptChars=${prompt.length} max=$maxFormattedChars). Shorten the user request." +
                            (prep?.let { " | session=$it" } ?: "")
                    )
                }

                val output = StringBuilder()
                val config = GenerationConfig().apply {
                    maxTokens = 512
                    nPast = 0
                }
                var streamError: String? = null
                wrapper.generateStreamFlow(prompt, config).collect { result ->
                    when (result) {
                        is LlmStreamResult.Token -> {
                            output.append(result.text)
                            onDelta(result.text)
                        }
                        is LlmStreamResult.Error -> {
                            val t = result.throwable
                            streamError = "generate:${t.message.orEmpty().ifBlank { t.javaClass.simpleName }}" +
                                " | promptChars=${prompt.length}" +
                                (prep?.let { " | session=$it" } ?: "")
                            sessionDirty = true
                        }
                        is LlmStreamResult.Completed -> {
                            if (streamError == null) sessionDirty = false
                        }
                    }
                }
                if (streamError != null) {
                    return@runBlocking ModelResponse.Failure("Nexa local inference failed: $streamError")
                }
                if (output.isBlank()) {
                    sessionDirty = true
                    ModelResponse.Failure(
                        "Nexa returned no output | promptChars=${prompt.length}" +
                            (prep?.let { " | session=$it" } ?: "")
                    )
                } else {
                    JsonModelResponseParser().parse(output.toString())
                }
            }
        } catch (error: Exception) {
            sessionDirty = true
            ModelResponse.Failure(
                "Nexa local inference failed: ${error.message.orEmpty()}"
            )
        }
    }

    private suspend fun prepareSession(): String? {
        val stopMsg = runCatching { wrapper.stopStream() }.fold(
            onSuccess = { "stop=ok" },
            onFailure = { e -> "stopFail=${e.message.orEmpty().ifBlank { e.javaClass.simpleName }}" }
        )
        if (!sessionDirty) return null
        val resetMsg = runCatching { wrapper.reset() }.fold(
            onSuccess = { code ->
                sessionDirty = false
                "reset=$code"
            },
            onFailure = { e ->
                "resetFail=${e.message.orEmpty().ifBlank { e.javaClass.simpleName }}"
            }
        )
        return "$stopMsg;$resetMsg"
    }

    fun close() {
        synchronized(lock) {
            runCatching {
                runBlocking {
                    runCatching { wrapper.stopStream() }
                    runCatching { wrapper.reset() }
                }
            }
            runCatching { wrapper.close() }
        }
    }

    private fun buildMessages(request: ModelRequest): ArrayList<ChatMessage> {
        val messages = ArrayList<ChatMessage>()
        // Tight system + transcript to stay under prompt budget after template expansion
        val systemBody = request.system.take(600)
        if (systemBody.isNotBlank()) {
            messages += ChatMessage("system", systemBody)
        }
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

    companion object {
        private const val TAG = "NexaLocalModel"
    }
}
