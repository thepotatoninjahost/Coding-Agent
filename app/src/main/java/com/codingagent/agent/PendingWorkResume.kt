package com.codingagent.agent

import java.util.UUID
import com.codingagent.workspace.AgentPlan
import com.codingagent.workspace.AgentTask
import com.codingagent.workspace.ChangeDiff
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: If the owner is asking about the current unfinished job (pending
 * proposal, hidden files, try again, continue), answer from local state.
 * Do not start a new model turn that forgets the proposal and prints Status: completed.
 */
object PendingWorkResume {
    private val resumeHints = listOf(
        "try again", "try it again", "retry", "continue", "keep going",
        "show the file", "show the files", "show file", "show files",
        "hiding the file", "hiding the files", "cannot confirm", "can't confirm",
        "can not confirm", "where is the file", "where are the files",
        "review proposal", "pending proposal", "what did you propose",
        "what did you change", "show the diff", "show diff", "the proposal"
    )

    fun isResumeRequest(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.isEmpty()) return false
        if (t.length <= 24 && (t == "continue" || t == "retry" || t == "again" || t == "resume")) return true
        return resumeHints.any { t.contains(it) }
    }

    fun tryResume(agent: AutonomousAgent, text: String, recentAgentText: String?): AgentRuntimeResult? {
        if (!isResumeRequest(text)) return null
        val pending = agent.pendingProposals().firstOrNull()
        if (pending != null) {
            val body = ChangeDiff.ownerReviewText(pending)
            val task = AgentTask(
                id = UUID.randomUUID().toString(),
                request = text,
                status = "waiting-approval",
                plan = AgentPlan(text, emptyList(), emptyList()),
                changes = pending.changeSet.changes,
                verification = pending.verification,
                events = listOf("resumed pending proposal ${pending.id}"),
                summary = body
            )
            return AgentRuntimeResult.NeedsApproval(task, body, pending.id)
        }
        if (recentAgentText != null && ModelFailure.isRateLimit(recentAgentText)) {
            return AgentRuntimeResult.Failed(
                AgentTask(
                    id = UUID.randomUUID().toString(),
                    request = text,
                    status = "failed",
                    plan = AgentPlan(text, emptyList(), emptyList()),
                    changes = emptyList(),
                    verification = VerificationReport(false, emptyList()),
                    events = emptyList(),
                    summary = recentAgentText.take(400) +
                        "\nNot calling the model again yet — wait for the rate-limit window, then send the original task (not just \"continue\")."
                )
            )
        }
        return AgentRuntimeResult.Failed(
            AgentTask(
                id = UUID.randomUUID().toString(),
                request = text,
                status = "failed",
                plan = AgentPlan(text, emptyList(), emptyList()),
                changes = emptyList(),
                verification = VerificationReport(false, emptyList()),
                events = emptyList(),
                summary = "There is no pending code proposal and no live task to resume. " +
                    "Say the actual work (what to create or change). \"Continue\" alone does not recreate a dropped job."
            )
        )
    }
}
