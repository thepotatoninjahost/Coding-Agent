package com.codingagent.agent

/**
 * ONE JOB: A single named phase in an AgentPlan.
 * [phase] is a short label (e.g. "GATHER", "WRITE", "VERIFY").
 * [detail] is the human-readable description of what this step entails.
 */
data class AgentStep(
    val phase: String,
    val detail: String
)
