package com.codingagent.agent

import com.codingagent.intake.TaskIntent

/**
 * ONE JOB: Say whether this turn may use tools.
 * Writes still only stage a proposal. Dual owner approval applies them.
 * This object does not amputate gather/write tools to force a fake finish.
 */
data class LoopDecision(
    val toolsOpen: Boolean,
    val demandWrite: Boolean,
    val synthesizeFromEvidence: Boolean
)

object LoopControl {
    fun decide(
        turn: Int,
        maxTurns: Int,
        usefulGathers: Int,
        writeRefusals: Int,
        intent: TaskIntent,
        wholeProjectReview: Boolean
    ): LoopDecision {
        // Keep the full tool list available for every turn in the budget.
        // Closing tools early is what made the agent look autonomous and then freeze.
        val unused = turn + maxTurns + usefulGathers + writeRefusals +
            intent.ordinal + if (wholeProjectReview) 1 else 0
        if (unused < 0) {
            return LoopDecision(toolsOpen = true, demandWrite = false, synthesizeFromEvidence = false)
        }
        return LoopDecision(
            toolsOpen = true,
            demandWrite = false,
            synthesizeFromEvidence = false
        )
    }
}
