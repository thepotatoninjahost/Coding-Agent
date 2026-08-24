package com.codingagent.agent
import com.codingagent.workspace.AgentTask

/**
 * ONE JOB: Shared result types for the single agent spine ([AutonomousAgent]).
 * Not a second execution loop — only the outcome vocabulary used by chat and tests.
 */
sealed class AgentRuntimeResult {
    data class Completed(val task: AgentTask) : AgentRuntimeResult()
    data class NeedsInput(val task: AgentTask, val question: String) : AgentRuntimeResult()
    data class NeedsApproval(val task: AgentTask, val question: String, val proposalId: String) : AgentRuntimeResult()
    data class Failed(val task: AgentTask) : AgentRuntimeResult()
}
