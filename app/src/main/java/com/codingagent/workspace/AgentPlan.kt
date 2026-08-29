package com.codingagent.workspace

/**
 * ONE JOB: Represent the ordered execution plan produced by AgentPlanner.
 * Consumed by PlanningLoop, AutonomousAgent, and AgentTaskBuilders.
 */
data class AgentPlan(
    val steps: List<AgentStep>
)

/**
 * ONE JOB: A single named phase in an AgentPlan.
 * [phase] is a short label (e.g. "GATHER", "WRITE", "VERIFY").
 * [detail] is the human-readable description of what this step entails.
 */
data class AgentStep(
    val phase: String,
    val detail: String
)
