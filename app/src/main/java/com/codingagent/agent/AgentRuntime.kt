package com.codingagent.agent

/** Shared result types for the agent runtime. No orchestration logic lives here. */

data class AgentStepResult(
    val success: Boolean,
    val message: String = "",
    val data: Any? = null,
)

data class AgentRunSummary(
    val steps: List<AgentStepResult> = emptyList(),
    val completed: Boolean = false,
    val error: String? = null,
)
