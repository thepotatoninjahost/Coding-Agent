package com.codingagent.agent

import com.codingagent.intake.TaskIntent

/**
 * ONE JOB: Keep gathering tools available until the model has enough evidence to choose a change.
 * Only the end-of-budget guard can force the execution gate; a gather count alone cannot.
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
        val changeWork = intent in setOf(
            TaskIntent.CHANGE,
            TaskIntent.CREATE,
            TaskIntent.REFACTOR,
            TaskIntent.DEBUG
        )

        if (!changeWork) {
            return LoopDecision(
                toolsOpen = true,
                demandWrite = false,
                synthesizeFromEvidence = false
            )
        }

        val forcedByRefusal = writeRefusals >= 2
        val lateTurn = turn >= (maxTurns - 2).coerceAtLeast(1)
        // Never force a code change from zero evidence. If the turn budget expires without
        // useful evidence, the caller must fail truthfully instead of guessing.
        val shouldDemandWrite = usefulGathers > 0 && (forcedByRefusal || lateTurn)

        return LoopDecision(
            toolsOpen = !shouldDemandWrite,
            demandWrite = shouldDemandWrite,
            synthesizeFromEvidence = shouldDemandWrite && usefulGathers > 0
        )
    }
}
