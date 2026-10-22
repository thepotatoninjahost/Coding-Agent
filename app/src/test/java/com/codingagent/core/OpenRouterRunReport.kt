package com.codingagent.core

import com.codingagent.agent.AutonomousAgentEvent
import java.io.File
import java.nio.file.Files

/**
 * Decides whether an OpenRouter run actually finished. Waiting for approval,
 * or a question with nothing built, is not a successful run.
 */
internal object OpenRouterRunReport {
    sealed class ProjectDirectory {
        data class Ready(val root: File) : ProjectDirectory()
        data class Missing(val message: String) : ProjectDirectory()
    }

    fun projectDirectory(envValue: String?): ProjectDirectory {
        if (envValue.isNullOrBlank()) {
            val root = Files.createTempDirectory("openrouter-agent").toFile()
            root.resolve("README.md").writeText("This sample project prints a greeting.\n")
            return ProjectDirectory.Ready(root)
        }
        val dir = File(envValue)
        if (!dir.isDirectory) {
            return ProjectDirectory.Missing("That project folder does not exist: $envValue")
        }
        return ProjectDirectory.Ready(dir)
    }

    fun failureReason(event: AutonomousAgentEvent?): String? = when (event) {
        null -> "The helper did not finish."
        is AutonomousAgentEvent.ApprovalRequired ->
            "Waiting for approval. Nothing was saved yet. That is not a finished job."
        is AutonomousAgentEvent.Completed -> when {
            event.task.status == "needs-input" ->
                "The helper asked a question and did not finish the job. ${event.task.summary}"
            event.task.status == "failed" ->
                "The helper did not finish the job. ${event.task.summary}"
            event.task.status.startsWith("completed") -> null
            else -> "The helper did not finish the job (${event.task.status}). ${event.task.summary}"
        }
        is AutonomousAgentEvent.Failed -> "Failed: ${event.message}"
        is AutonomousAgentEvent.Stopped -> "Stopped: ${event.message}"
        else -> "The helper did not finish."
    }

    fun describeCompleted(event: AutonomousAgentEvent.Completed): String = when (event.task.status) {
        "needs-input" -> "Asking you: ${event.task.summary}"
        else -> "Finished (${event.task.status}): ${event.task.summary}"
    }
}
