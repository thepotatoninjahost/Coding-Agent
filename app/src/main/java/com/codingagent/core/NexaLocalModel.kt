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
        val input = LlmCreateInput(
            model_name = "qwen3-4b",
            model_path = modelDirectory.absolutePath,
            config = ModelConfig(max_tokens = 4096),
            plugin_id = "npu",
            device_id = null
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

    private fun awaitSdkInitialization(context: Context) {
        val completed = CountDownLatch(1)
        var failure: String? = null
        NexaSdk.Companion.getInstance().init(context, object : NexaSdk.InitCallback {
            override fun onSuccess() { completed.countDown() }
            override fun onFailure(message: String) {
                failure = message
                completed.countDown()
            }
        })
        check(completed.await(60, TimeUnit.SECONDS)) { "Nexa SDK initialization timed out" }
        check(failure == null) { "Nexa SDK initialization failed: $failure" }
    }

    private fun buildPrompt(request: ModelRequest): String = buildString {
        append(request.system).append("\n\n")
        if (request.researchRequired) append("RESEARCH_GATE: satisfied by the attached multi-source research brief; do not skip research or claim knowledge without evidence.\n\n")
        request.transcript.forEach { append(it.role).append(": ").append(it.content).append("\n") }
        append("user: ").append(request.user)
    }
}
