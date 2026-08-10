package com.codingagent.ui

import androidx.compose.ui.graphics.Color

internal val NeonGreen = Color(0xFF39FF14)
internal val FluoroOrange = Color(0xFFFF6B00)
internal val DarkPurple = Color(0xFF12081F)
internal val PanelPurple = Color(0xFF1E1033)
internal val RaisedPurple = Color(0xFF2A1848)
internal val LinePurple = Color(0xFF4A2F6A)
internal val SoftGreen = Color(0xFF7ACC7A)
internal val DangerRed = Color(0xFFFF4500)
internal val Ink = NeonGreen
internal val Canvas = DarkPurple
internal val Panel = PanelPurple
internal val Line = LinePurple
internal val Accent = NeonGreen
internal val Blue = FluoroOrange
internal val Amber = FluoroOrange
internal val Danger = DangerRed

internal enum class SurfaceTab(val label: String) { CHAT("Chat"), FILES("Files"), REVIEW("Review"), TERMINAL("Terminal"), RESEARCH("Research") }
internal enum class AgentStatus(val label: String, val color: Color) {
    READY("Ready", Accent),
    PLANNING("Planning", Blue),
    RESEARCHING("Researching", Blue),
    WORKING("Working", Blue),
    MODEL("Model", Blue),
    TOOL("Tool", Amber),
    EDITING("Editing", Amber),
    APPROVAL("Waiting for approval", Amber),
    RUNNING("Verifying", Blue),
    FAILED("Failed", Danger),
    STOPPED("Stopped", Danger)
}

internal fun mapAgentPhase(phase: String): AgentStatus = when (phase.uppercase()) {
    "STARTED", "INTAKE", "PLAN", "PLANNING" -> AgentStatus.PLANNING
    "RESEARCH" -> AgentStatus.RESEARCHING
    "MODEL" -> AgentStatus.MODEL
    "TOOL" -> AgentStatus.TOOL
    "APPROVAL" -> AgentStatus.APPROVAL
    "DONE", "COMPLETED" -> AgentStatus.READY
    "FAILED" -> AgentStatus.FAILED
    else -> AgentStatus.WORKING
}
