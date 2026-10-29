package com.codingagent.core

import com.codingagent.agent.AgentKnowledge
import com.codingagent.agent.AutonomousAgent
import com.codingagent.agent.AutonomousAgentConfig
import com.codingagent.agent.AutonomousAgentEvent
import com.codingagent.agent.ContextBudget
import com.codingagent.agent.PlainEnglish
import com.codingagent.agent.StuckDetector
import com.codingagent.agent.TaskBudget
import com.codingagent.agent.AgentAction
import com.codingagent.agent.AgentActionCategory
import com.codingagent.agent.AgentConstitution
import com.codingagent.agent.ConstitutionRule
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse
import com.codingagent.model.OpenRouterDailyBudget
import com.codingagent.research.StructuredSources
import com.codingagent.workspace.DeviceCodeRunner
import com.codingagent.workspace.KnowledgeHit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.nio.file.Files
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class Round6FixesTest {
    @Test
    fun sourcedResearchStopsAndThePromptHasTodaysDate() {
        OpenRouterDailyBudget.noteRemaining(50)
        try {
            val root = Files.createTempDirectory("round6-research").toFile()
            var calls = 0
            val requests = mutableListOf<ModelRequest>()
            val gateway = object : ModelGateway {
                override fun complete(request: ModelRequest): ModelResponse {
                    calls++
                    requests += request
                    return ModelResponse.ToolCall(
                        "research_web",
                        """{"query":"latest stable Kotlin version search $calls"}"""
                    )
                }
            }
            val events = AutonomousAgent(
                root,
                emptyKnowledge(),
                gateway,
                AutonomousAgentConfig(maxTurns = 40),
                sourceConnect = { json("""[{"cycle":"2.4","latest":"2.4.21","releaseDate":"2026-10-08"}]""") }
            ).run("What is the latest stable version of Kotlin? Tell me the version number and when it was released.")
            val last = events.last() as AutonomousAgentEvent.Completed
            assertEquals(1, calls)
            assertTrue(last.task.summary.contains("2.4.21"))
            assertTrue(last.task.summary.contains("October 8, 2026"))
            assertFalse(last.task.summary.contains("as far as I got"))
            val today = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
            assertTrue(requests.first().system.contains(today))
            assertTrue(requests.first().system.contains("not from the future"))
            assertTrue(requests.first().user.contains(today))
        } finally {
            OpenRouterDailyBudget.clear()
        }
    }

    @Test
    fun lowDailyQuotaCapsTheTaskAndWarns() {
        OpenRouterDailyBudget.noteRemaining(10)
        try {
            assertEquals(2, TaskBudget.callsFor(40, 10))
            assertEquals(8, TaskBudget.callsFor(40, 50))
            assertEquals(1, TaskBudget.callsFor(40, 2))
            val root = Files.createTempDirectory("round6-budget").toFile()
            var calls = 0
            val gateway = object : ModelGateway {
                override fun complete(request: ModelRequest): ModelResponse {
                    calls++
                    return ModelResponse.ToolCall("list_files", """{"path":""}""")
                }
            }
            val events = AutonomousAgent(root, emptyKnowledge(), gateway, AutonomousAgentConfig(maxTurns = 40))
                .run("Explain what this unused phrase means and keep going until you are sure.")
            assertTrue(calls <= 2)
            assertTrue(events.filterIsInstance<AutonomousAgentEvent.Phase>().any {
                it.detail.contains("10 free requests left today") && it.detail.contains("at most 2")
            })
        } finally {
            OpenRouterDailyBudget.clear()
        }
    }

    @Test
    fun stepLimitUsesTheSourcedFactAlreadyFound() {
        val root = Files.createTempDirectory("round6-limit").toFile()
        root.resolve("notes.txt").writeText("kotlin 2.4.21 was published on October 8, 2026. Source: https://github.com/JetBrains/kotlin/releases/tag/v2.4.21\n")
        var calls = 0
        val gateway = object : ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse {
                calls++
                return ModelResponse.ToolCall("read_file", """{"path":"notes.txt","startCharacter":0}""")
            }
        }
        val events = AutonomousAgent(root, emptyKnowledge(), gateway, AutonomousAgentConfig(maxTurns = 2))
            .run("Read the note and tell me the date it records.")
        val last = events.last() as AutonomousAgentEvent.Completed
        assertTrue(calls <= 2)
        assertTrue(last.task.summary.contains("2.4.21"))
        assertTrue(last.task.summary.contains("October 8, 2026"))
        assertFalse(last.task.summary.contains("as far as I got"))
    }

    @Test
    fun identicalSearchResultsStopAfterThree() {
        val observation = "The same page text came back again with no new fact and no publication date at all."
        val step = { query: String ->
            StuckDetector.Step("research_web||$query|", observation, error = false, textOnly = false)
        }
        assertTrue(StuckDetector.check(listOf(step("one"), step("two"))) == null)
        val stuck = StuckDetector.check(listOf(step("one"), step("two"), step("three")))
        assertTrue(stuck!!.contains("three"))
    }

    @Test
    fun stagedFileShowsApprovalInsteadOfNothingSaved() {
        val root = Files.createTempDirectory("round6-stage").toFile()
        val content = "function compile(text) { return String(text); }\n"
        val args = JSONObject().put("path", "compiler.js").put("content", content).toString()
        var calls = 0
        val gateway = object : ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse {
                calls++
                return if (calls == 1) {
                    ModelResponse.ToolCall("create_file", args)
                } else {
                    ModelResponse.Text("The compiler.js file is staged and waiting for your approval.")
                }
            }
        }
        val events = AutonomousAgent(root, emptyKnowledge(), gateway, AutonomousAgentConfig(maxTurns = 6))
            .run("Build a tiny compiler in JavaScript that turns arithmetic expressions like 1+2*3 into JavaScript")
        val ready = events.filterIsInstance<AutonomousAgentEvent.ToolFinished>().first { it.name == "create_file" }
        assertTrue(ready.result.contains("review_examples"))
        assertTrue(ready.result.contains("run_program"))
        assertTrue(events.any { it is AutonomousAgentEvent.ApprovalRequired })
        val summary = events.filterIsInstance<AutonomousAgentEvent.ApprovalRequired>().last().task.summary
        assertFalse(summary.contains("Nothing was saved"))
        assertFalse(summary.contains("I have not built anything"))
        assertTrue(summary.contains("I have not run this yet"))
        assertFalse(root.resolve("compiler.js").exists())
    }

    @Test
    fun namedVersionIsNotReplacedByTheLatestRelease() {
        val fact = StructuredSources.answer("Kotlin 2.0.0 release date May 2024") {
            json("""[{"cycle":"2.4","latest":"2.4.21","releaseDate":"2026-10-08"}]""")
        }
        assertTrue(fact!!.contains("2.0.0"))
        assertTrue(fact.contains("not found"))
        assertFalse(fact.contains("2.4.21"))
    }

    @Test
    fun explainAnswerIsOneShortParagraphWithoutMarkdownOrJargon() {
        val softened = PlainEnglish.soften(
            "**This** project keeps API keys in the repo and uses indexing to find files. " +
                "It also talks about the checksum-backed rollback and then adds a second paragraph that should be dropped.\n\nSecond paragraph stays out.",
            oneShortParagraph = true
        )
        assertFalse(softened.contains("*"))
        assertFalse(softened.contains("API keys"))
        assertFalse(softened.contains("repo"))
        assertFalse(softened.contains("indexing"))
        assertFalse(softened.contains("Second paragraph"))
        assertTrue(softened.contains("passwords"))
        assertTrue(softened.contains("project"))
        assertTrue(softened.contains("listing files"))
        assertTrue(softened.split(Regex("\\s+")).size <= 80)
    }

    @Test
    fun badCompilerOutputDoesNotCountAsACleanError() {
        val crash = "function compile(text) { console.log('Error: bad\\n\\tat program.js:1'); }\n"
        val crashed = DeviceCodeRunner.cases(crash, listOf("1+" to "error", "abc" to "error"))
        assertFalse(crashed.passed)
        val infinity = "function compile(text) { console.log('Infinity'); }\n"
        val infinite = DeviceCodeRunner.cases(infinity, listOf("8/0" to "error", "1+" to "error"))
        assertFalse(infinite.passed)
    }

    @Test
    fun constitutionEmitsTheTwoMissingRules() {
        val blank = AgentAction("", AgentActionCategory.CODE_CHANGE, ownerVerified = true, approvalCount = 2, evaluationPassed = true)
        assertTrue(AgentConstitution.check(blank).any { it.rule == ConstitutionRule.TRANSPARENCY_LOG })
        val export = AgentAction("Send the project away", AgentActionCategory.DATA_EXPORT, ownerVerified = true, approvalCount = 2)
        assertTrue(AgentConstitution.check(export).any { it.rule == ConstitutionRule.SAFETY_BOUNDARY })
    }

    private fun emptyKnowledge() = object : AgentKnowledge {
        override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
    }

    private fun json(body: String): HttpURLConnection =
        object : HttpURLConnection(java.net.URL("https://endoflife.date/api/kotlin.json")) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getInputStream() = body.byteInputStream()
            override fun getErrorStream() = "".byteInputStream()
            override fun getHeaderField(name: String?) = null
        }
}
