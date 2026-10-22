package com.codingagent.agent

/**
 * ONE JOB: Turn-budget and output limits for one agent run.
 */
data class AutonomousAgentConfig(
    /**
     * One job stops after this many helper calls. OpenRouter's free plan is about 50 calls a day,
     * so one job must not use the whole day. Other helpers are tried when one is used up.
     */
    val maxTurns: Int = 40,
    val commandTimeoutSeconds: Long = 180,
    val maxOutputCharacters: Int = 6_000,
    val maxConsecutiveToolFailures: Int = 5,
    val maxIdenticalToolRepeats: Int = 8,
    val maxEvidenceRefusals: Int = 3
)
