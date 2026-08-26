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
        val gatherCap = if (wholeProjectReview || intent == TaskIntent.INSPECT || intent == TaskIntent.EXPLAIN) {
            2
        } else {
            4
        }
        // Change work must keep tools open so replace_text/create_file can run.
        // demandWrite is advisory only; AutonomousAgent still executes mutation tools.
        val toolsOpen = !lastTurns && (changeWork || usefulGathers < gatherCap)
        val demandWrite = !toolsOpen
        val synthesize = !changeWork && demandWrite && writeRefusals >= 2
        return LoopDecision(
            toolsOpen = toolsOpen,
            demandWrite = demandWrite,
            synthesizeFromEvidence = synthesize
        )
    }
}
