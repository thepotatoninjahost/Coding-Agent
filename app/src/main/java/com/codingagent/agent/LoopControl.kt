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
        val changeWork = intent == TaskIntent.CHANGE ||
            intent == TaskIntent.CREATE ||
            intent == TaskIntent.REFACTOR ||
            intent == TaskIntent.DEBUG
        // Inspect/explain/whole-project: tight gather budget then write.
        // Change work: higher budget so mutations can run after reads; closes at 3 gathers.
        val gatherCap = when {
            wholeProjectReview || intent == TaskIntent.INSPECT || intent == TaskIntent.EXPLAIN -> 2
            changeWork -> 3
            else -> 4
        }
        val toolsOpen = !lastTurns && usefulGathers < gatherCap
        val demandWrite = !toolsOpen
        val synthesize = !changeWork && demandWrite && writeRefusals >= 2
        return LoopDecision(
            toolsOpen = toolsOpen,
            demandWrite = demandWrite,
            synthesizeFromEvidence = synthesize
        )
    }
}
