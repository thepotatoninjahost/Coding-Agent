package com.codingagent.orchestration

import com.codingagent.domain.*

data class WorkflowPhase(val name: String, val responsibility: String)

object AgentWorkflow {
    val phases = listOf(
        WorkflowPhase("intake", "Normalize the request and define acceptance criteria"),
        WorkflowPhase("plan", "Select a bounded execution plan"),
        WorkflowPhase("research", "Gather current external technical evidence"),
        WorkflowPhase("context", "Read relevant project files and constraints"),
        WorkflowPhase("proposal", "Produce a typed multi-file change proposal"),
        WorkflowPhase("approval", "Obtain the required owner confirmation"),
        WorkflowPhase("apply", "Apply the approved change set transactionally"),
        WorkflowPhase("verify", "Run checks and inspect the result"),
        WorkflowPhase("repair", "Diagnose failures and retry or roll back")
    )

    fun phases(): List<String> = phases.map { it.name }
    fun newPlan(): PlanningLoop = PlanningLoop(phases.map { it.name to it.responsibility })
    fun toolPlan(includeChanges: Boolean, includeChecks: Boolean): List<ToolInvocation> {
        val result = mutableListOf<ToolInvocation>()
        fun add(kind: ToolKind, purpose: String) { result += ToolInvocation("${result.size + 1}-${kind.name.lowercase()}", kind, purpose, result.lastOrNull()?.let { listOf(it.id) }.orEmpty()) }
        add(ToolKind.INDEX_REPOSITORY, "Build the repository file, symbol, import, and checksum view")
        add(ToolKind.SEARCH_KNOWLEDGE, "Retrieve local coding references and lessons")
        add(ToolKind.SEARCH_PROJECT, "Locate target files, symbols, and relevant evidence")
        if (includeChanges) { add(ToolKind.SYNTHESIZE_CODE, "Produce a structured, testable change proposal"); add(ToolKind.APPLY_CHANGES, "Apply the approved proposal through the workspace transaction API") }
        if (includeChecks) add(ToolKind.RUN_CHECKS, "Run the selected project checks")
        add(ToolKind.VERIFY, "Evaluate static and runtime acceptance evidence")
        add(ToolKind.RECORD_LESSON, "Persist reusable evidence")
        return result
    }
}
