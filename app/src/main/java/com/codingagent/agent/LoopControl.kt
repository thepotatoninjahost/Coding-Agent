package com.codingagent.agent

import com.codingagent.intake.TaskIntent

/**
 * ONE JOB: Decide whether this turn may call tools or must write a result.
 *
 * A coding agent gathers a small amount of real evidence, then acts or answers.
 * It does not spend the whole turn budget listing files.
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
        val lastTurns = turn >= (maxTurns - 2).coerceAtLeast(0)
        val gatherCap = if (wholeProjectReview || intent == TaskIntent.INSPECT || intent == TaskIntent.EXPLAIN) {
            2
        } else {
            3
        }
        val toolsOpen = !lastTurns && usefulGathers < gatherCap
        val demandWrite = !toolsOpen
        val synthesize = demandWrite && writeRefusals >= 2
        return LoopDecision(
            toolsOpen = toolsOpen,
            demandWrite = demandWrite,
            synthesizeFromEvidence = synthesize
        )
    }
}
