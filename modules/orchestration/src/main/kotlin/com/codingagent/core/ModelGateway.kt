package com.codingagent.core

import com.codingagent.model.AgentModelProtocol as ModuleAgentModelProtocol
import com.codingagent.model.HttpModelGateway as ModuleHttpModelGateway
import com.codingagent.model.JsonModelResponseParser as ModuleJsonModelResponseParser
import com.codingagent.model.ModelGateway as ModuleModelGateway
import com.codingagent.model.ModelMessage as ModuleModelMessage
import com.codingagent.model.ModelRequest as ModuleModelRequest
import com.codingagent.model.ModelResponse as ModuleModelResponse
import com.codingagent.model.ModelToolDefinition as ModuleModelToolDefinition
import com.codingagent.model.OpenAiCompatibleGateway as ModuleOpenAiCompatibleGateway
import com.codingagent.model.UnconfiguredModelGateway as ModuleUnconfiguredModelGateway

typealias ModelGateway = ModuleModelGateway
typealias ModelRequest = ModuleModelRequest
typealias ModelToolDefinition = ModuleModelToolDefinition
typealias ModelMessage = ModuleModelMessage
typealias ModelResponse = ModuleModelResponse
typealias ModelText = ModuleModelResponse.Text
typealias ModelToolCall = ModuleModelResponse.ToolCall
typealias ModelFailure = ModuleModelResponse.Failure
typealias OpenAiCompatibleGateway = ModuleOpenAiCompatibleGateway
typealias HttpModelGateway = ModuleHttpModelGateway
typealias UnconfiguredModelGateway = ModuleUnconfiguredModelGateway
typealias JsonModelResponseParser = ModuleJsonModelResponseParser

object AgentModelProtocol {
    val SYSTEM: String get() = ModuleAgentModelProtocol.SYSTEM
    fun tools(): List<ModelToolDefinition> = ModuleAgentModelProtocol.tools()
}
