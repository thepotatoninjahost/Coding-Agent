package com.codingagent.agent

import com.codingagent.intake.TaskIntent

/**
 * ONE JOB: Say whether this turn may use tools.
 * Writes still only stage a proposal. Dual owner approval applies them.
 */
data class LoopDecision(
    val toolsOpen: Boolean,
    val demandWrite: Boolean,
    val synthesizeFromEvidence: Boolean
)

object LoopControl {
    fun decide(
        @Suppress("UNUSED_PARAMETER") turn: Int,
        @Suppress("UNUSED_PARAMETER") maxTurns: Int,
        @Suppress("UNUSED_PARAMETER") usefulGathers: Int,
        @Suppress("UNUSED_PARAMETER") writeRefusals: Int,
        @Suppress("UNUSED_PARAMETER") intent: TaskIntent,
        @Suppress("UNUSED_PARAMETER") wholeProjectReview: Boolean
    ): LoopDecision = LoopDecision(
        toolsOpen = true,
        demandWrite = false,
        synthesizeFromEvidence = false
    )
}
