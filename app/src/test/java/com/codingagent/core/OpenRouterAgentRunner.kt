package com.codingagent.core

import com.codingagent.agent.AgentKnowledge
import com.codingagent.agent.AutonomousAgent
import com.codingagent.agent.AutonomousAgentConfig
import com.codingagent.agent.AutonomousAgentEvent
import com.codingagent.model.ModelSettings
import com.codingagent.workspace.KnowledgeHit
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * Runs the real agent loop against OpenRouter.
 *
 * Not part of the normal unit-test suite. `scripts/run_openrouter_agent.sh` passes
 * `-PrunOpenRouter` and reads the key from the environment. The key is never printed.
 */
class OpenRouterAgentRunner {
    @Test
    fun runCoreLoopAgainstOpenRouter() {
        val key = System.getenv("OPENROUTER_API_KEY").orEmpty().filterNot { it.isWhitespace() }
        if (key.isBlank()) {
            fail("OPENROUTER_API_KEY is not set. Paste your own key in the environment. It is not stored in this repository.")
        }
        val request = System.getenv("OPENROUTER_AGENT_REQUEST")?.trim().takeUnless { it.isNullOrBlank() }
            ?: "Explain this project in one short paragraph. Do not change any files."
        val settings = ModelSettings(apiKey = key).normalized()
        check(!settings.apiKey.isBlank())
        check(settings.baseUrl == ModelSettings.OPENROUTER_BASE_URL)
        check(settings.modelName == ModelSettings.OPENROUTER_FREE_MODEL)
        val gateway = settings.remoteGateway(timeoutMillis = 90_000)
        if (gateway == null) {
            fail("OpenRouter is not usable. The secret password was empty after cleanup.")
            return
        }
        val project = OpenRouterRunReport.projectDirectory(System.getenv("OPENROUTER_AGENT_PROJECT"))
        if (project is OpenRouterRunReport.ProjectDirectory.Missing) {
            fail(project.message)
            return
        }
        val root = (project as OpenRouterRunReport.ProjectDirectory.Ready).root
        val agent = AutonomousAgent(
            root,
            object : AgentKnowledge {
                override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
            },
            gateway,
            AutonomousAgentConfig(maxTurns = 4)
        )
        val report = StringBuilder()
        report.append("Request: ").append(request).append('\n')
        val events = agent.run(request) { event ->
            val line = redact(describe(event), key)
            if (line.isNotBlank()) report.append(line).append('\n')
        }
        val text = redact(report.toString(), key)
        val out = resultFile()
        out.parentFile?.mkdirs()
        out.writeText(text)
        assertFalse(text.contains(key))
        val terminal = events.lastOrNull()
        val failure = OpenRouterRunReport.failureReason(terminal)
        if (failure != null) {
            fail(redact("$failure\n$text", key))
        }
    }

    private fun describe(event: AutonomousAgentEvent): String = when (event) {
        is AutonomousAgentEvent.Started -> "Started"
        is AutonomousAgentEvent.Phase -> "Phase ${event.name}: ${event.detail}"
        is AutonomousAgentEvent.ToolStarted -> "Tool ${event.name}"
        is AutonomousAgentEvent.ToolFinished -> "Tool ${event.name} finished. Success: ${event.success}"
        is AutonomousAgentEvent.ModelDelta -> ""
        is AutonomousAgentEvent.ModelMessage -> "Helper said: ${event.content.take(800)}"
        is AutonomousAgentEvent.Completed -> OpenRouterRunReport.describeCompleted(event)
        is AutonomousAgentEvent.Failed -> "Failed: ${event.message}"
        is AutonomousAgentEvent.Stopped -> "Stopped: ${event.message}"
        is AutonomousAgentEvent.ApprovalRequired -> "Waiting for approval. Nothing was saved yet. That is not a finished job."
    }

    private fun resultFile(): File {
        val start = System.getProperty("user.dir") ?: return File("build/openrouter-agent-result.txt")
        var dir = File(start).absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, "settings.gradle").isFile) {
                return File(dir, "app/build/openrouter-agent-result.txt")
            }
            dir = dir.parentFile ?: return File("build/openrouter-agent-result.txt")
        }
        return File("build/openrouter-agent-result.txt")
    }

    private fun redact(text: String, key: String): String {
        if (key.isBlank()) return text
        return text.replace(key, "[redacted]")
    }
}
