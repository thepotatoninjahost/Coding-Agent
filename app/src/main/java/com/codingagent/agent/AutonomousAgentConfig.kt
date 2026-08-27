package com.codingagent.agent

/**
 * ONE JOB: Tunable limits for one agent run.
 */
data class AutonomousAgentConfig(
    val maxTurns: Int = 24,
    val commandTimeoutSeconds: Long = 180,
    val maxOutputCharacters: Int = 6_000,
    val maxConsecutiveToolFailures: Int = 5,
    val maxIdenticalToolRepeats: Int = 3,
    val maxEvidenceRefusals: Int = 3
)
