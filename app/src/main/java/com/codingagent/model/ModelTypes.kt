package com.codingagent.model

/**
 * ONE JOB: Shared model request/response types.
 */
interface ModelGateway {
    fun complete(request: ModelRequest): ModelResponse
    fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse = complete(request)
}

data class ModelRequest(
    val system: String,
    val user: String,
    val tools: List<ModelToolDefinition>,
    val transcript: List<ModelMessage> = emptyList(),
    val researchRequired: Boolean = false
)

data class ModelToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: String
)

data class ModelMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArguments: String? = null
)

sealed class ModelResponse {
    abstract val content: String
    data class Text(override val content: String) : ModelResponse()
    data class ToolCall(
        val name: String,
        val arguments: String,
        val thought: String = "",
        val callId: String? = null
    ) : ModelResponse() {
        override val content: String get() = thought
    }
    data class Failure(val message: String) : ModelResponse() {
        override val content: String get() = message
    }
}
