package com.codingagent.core

fun interface CodingAgentExecutor {
    fun execute(request: String): AgentRuntimeResult
}
