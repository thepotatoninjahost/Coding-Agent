package com.codingagent.agent

import java.time.Instant
import com.codingagent.workspace.AgentPlan
import com.codingagent.workspace.AgentTask
import com.codingagent.workspace.ChangeRecord
import com.codingagent.workspace.PendingChangeProposal
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Build AgentTask objects for the run loop's terminal/near-terminal outcomes
 * (stopped, failed, waiting-approval). Pure construction only — extracted verbatim out of
 * AutonomousAgent.kt, no wording/status/behavior changes. Callers still own recording
 * (recordTask) and emitting the matching event.
 */
object AgentTaskBuilders {
    fun stopped(taskId: String, request: String, plan: AgentPlan, changes: List<ChangeRecord>, message: String): AgentTask =
        AgentTask(
            taskId, request, "stopped", plan, changes,
            VerificationReport(false, emptyList()), listOf("${Instant.now()}: $message"), message
        )

    fun failed(
        id: String,
        request: String,
        plan: AgentPlan,
        message: String,
        changes: List<ChangeRecord>
    ): AgentTask = AgentTask(
        id = id,
        request = request,
        status = "failed",
        plan = plan,
        changes = changes,
        verification = VerificationReport(true, emptyList()),
        events = listOf("${Instant.now()}: $message"),
        summary = message
    )

    fun approval(
        id: String,
        request: String,
        plan: AgentPlan,
        proposal: PendingChangeProposal
    ): AgentTask = AgentTask(
        id = id,
        request = request,
        status = "waiting-approval",
        plan = plan,
        changes = proposal.changeSet.changes,
        verification = proposal.verification,
        events = listOf(
            "${Instant.now()}: proposal ${proposal.id} staged; awaiting two owner approvals"
        ),
        summary = "Review proposal ${proposal.id} and confirm twice before applying any code change"
    )
}
