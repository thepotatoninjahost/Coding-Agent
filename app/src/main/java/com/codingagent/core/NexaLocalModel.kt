package com.codingagent.core

import android.content.Context
import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.NexaSdk
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmCreateInput
import com.nexa.sdk.bean.LlmStreamResult
import com.nexa.sdk.bean.ModelConfig
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.io.File

class NexaLocalModelGateway(
    context: Context,
    private val modelDirectory: File,
    private val modelName: String = Qwen3NpuPackage.modelName
) : ModelGateway {
    private val wrapper: LlmWrapper

    init {
        var initError: String? = null
        var initialized = false
        NexaSdk.Companion.getInstance().init(context, object : NexaSdk.InitCallback {
            override fun onSuccess() { initialized = true }
            override fun onFailure(message: String) { initError = message }
        })
        check(initialized || initError == null) { "Nexa SDK initialization failed: $initError" }
        val input = LlmCreateInput(
            modelName,
            modelDirectory.absolutePath,
            "",
            ModelConfig(),
            NexaSdk.PLUGIN_ID_NPU,
            ""
        )
        wrapper = runBlocking {
            val result = LlmWrapper.Companion.builder().llmCreateInput(input).build()
            result.getOrElse { error("Nexa model load failed: ${it.message.orEmpty()}") }
        }
    }

    override fun complete(request: ModelRequest): ModelResponse = stream(request) {}

    override fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse = try {
        val prompt = buildPrompt(request)
        val output = StringBuilder()
        runBlocking {
            wrapper.generateStreamFlow(prompt, GenerationConfig().apply { maxTokens = 2048 }).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> {
                        output.append(result.text)
                        onDelta(result.text)
                    }
                    is LlmStreamResult.Error -> error(result.throwable.message.orEmpty())
                    is LlmStreamResult.Completed -> Unit
                }
            }
        }
        if (output.isBlank()) ModelResponse.Failure("Nexa returned no output") else JsonModelResponseParser().parse(output.toString())
    } catch (error: Exception) {
        ModelResponse.Failure("Nexa local inference failed: ${error.message.orEmpty()}")
    }

    fun close() { wrapper.close() }

    private fun buildPrompt(request: ModelRequest): String = buildString {
        append(request.system).append("\n\n")
        if (request.researchRequired) append("RESEARCH_GATE: satisfied by the attached multi-source research brief; do not skip research or claim knowledge without evidence.\n\n")
        request.transcript.forEach { append(it.role).append(": ").append(it.content).append("\n") }
        append("user: ").append(request.user)
    }
}
