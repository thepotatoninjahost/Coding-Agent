package com.codingagent.agent

/** ONE JOB: Represent the ordered execution plan produced by AgentPlanner. */
data class AgentPlan(
    val steps: List<AgentStep>
)
