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
        assertEquals(24, TaskBudget.callsFor(40, 1000, changeWork = true))
        assertEquals(24, TaskBudget.callsFor(40, null, changeWork = true))
        assertTrue(TaskBudget.callsFor(40, 45, changeWork = true) >= 12)
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
        val events = AutonomousAgent(root, emptyKnowledge(), gateway, AutonomousAgentConfig(maxTurns = 4))
            .run("Build a tiny compiler in JavaScript that turns arithmetic expressions like 1+2*3 into JavaScript")
        val ready = events.filterIsInstance<AutonomousAgentEvent.ToolFinished>().first { it.name == "create_file" }
        assertTrue(ready.result.contains("review_examples"))
        assertTrue(events.none { it is AutonomousAgentEvent.ApprovalRequired })
        val last = events.last() as AutonomousAgentEvent.Completed
        assertTrue(last.task.summary.contains("unfinished"))
        assertFalse(last.task.summary.contains("I ran the examples"))
        assertFalse(root.resolve("compiler.js").exists())
    }

    @Test
    fun aPassingRunShowsTheFileNameOnTheApprovalCard() {
        val root = Files.createTempDirectory("round6-run").toFile()
        val content = """
            var text = String(__stdin || "").trim();
            if (!text || /[+\-*/]$/.test(text)) console.log("error");
            else console.log("2");
        """.trimIndent()
        val create = JSONObject().put("path", "compiler.js").put("content", content).toString()
        val review = JSONObject().put("path", "compiler.js").put("examples", "1+1 => 2\n1+ => error").toString()
        var calls = 0
        val gateway = object : ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse {
                calls++
                return if (calls == 1) ModelResponse.ToolCall("create_file", create)
                else ModelResponse.ToolCall("review_examples", review)
            }
        }
        val events = AutonomousAgent(root, emptyKnowledge(), gateway)
            .run("Build a tiny compiler in JavaScript")
        val approval = events.filterIsInstance<AutonomousAgentEvent.ApprovalRequired>().last()
        assertTrue(approval.task.summary.contains("compiler.js"))
        assertTrue(approval.task.summary.contains("I ran the examples and they matched."))
        assertFalse(approval.task.summary.contains("OPEN JOB"))
        assertFalse(root.resolve("compiler.js").exists())
    }

    @Test
    fun aBuggyCompilerIsRunAndFixedBeforeTheCard() {
        val root = Files.createTempDirectory("round6-buggy").toFile()
        val buggy = """
            var expr = String(process.argv[2] || "");
            console.log("PUSH " + expr);
        """.trimIndent()
        val fixed = """
            var expr = String(process.argv[2] || "");
            function bad(s) {
              if (!s) return true;
              if (/[+\-*/]$/.test(s)) return true;
              if (s.indexOf("++") >= 0) return true;
              if (/[^0-9+\-*/().\s]/.test(s)) return true;
              return false;
            }
            if (bad(expr)) console.log("error");
            else if (expr === "(1+2)*3") console.log("PUSH 1\nPUSH 2\nADD\nPUSH 3\nMUL");
            else if (expr === "-3+1") console.log("PUSH -3\nPUSH 1\nADD");
            else console.log("PUSH 0");
        """.trimIndent()
        var calls = 0
        val gateway = object : ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse {
                calls++
                val body = if (calls == 1) buggy else fixed
                return ModelResponse.ToolCall(
                    "create_file",
                    JSONObject().put("path", "compiler.js").put("content", body).toString()
                )
            }
        }
        val events = AutonomousAgent(root, emptyKnowledge(), gateway).run(
            "Write a tiny compiler in JavaScript that turns arithmetic like (1+2)*3 into stack-machine instructions, run it, and test it on a few examples including a bad input"
        )
        val approvalIndex = events.indexOfFirst { it is AutonomousAgentEvent.ApprovalRequired }
        assertTrue(approvalIndex > 0)
        val before = events.take(approvalIndex).joinToString("\n")
        assertTrue(before.contains("did not match") || before.contains("1+"))
        val approval = events[approvalIndex] as AutonomousAgentEvent.ApprovalRequired
        assertTrue(approval.task.summary.contains("compiler.js"))
        assertTrue(approval.task.summary.contains("I ran the examples and they matched."))
        assertTrue(approval.task.summary.contains("1+"))
        assertFalse(approval.task.summary.contains("I have not run this yet"))
        assertFalse(approval.task.summary.contains("OPEN JOB"))
        assertFalse(approval.task.summary.contains("PROPOSED CHANGES"))
        assertTrue(calls >= 2)
        assertFalse(root.resolve("compiler.js").exists())
    }

    @Test
    fun aRequestThatIsTooBigTriesAgainAndThenSaysUnfinished() {
        val root = Files.createTempDirectory("round6-too-big").toFile()
        var calls = 0
        val gateway = object : ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse {
                calls++
                return ModelResponse.Failure("Model HTTP 413: Request too large")
            }
        }
        val events = AutonomousAgent(root, emptyKnowledge(), gateway, AutonomousAgentConfig(maxTurns = 6))
            .run("Build a tiny compiler in JavaScript")
        val last = events.last() as AutonomousAgentEvent.Completed
        assertTrue(calls >= 3)
        assertTrue(last.task.summary.contains("unfinished"))
        assertTrue(last.task.summary.contains("Nothing was saved"))
        assertTrue(events.none { it is AutonomousAgentEvent.ApprovalRequired })
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

    @Test
    fun aCompilerWritesTheFileEarlyAndTheStoredTitleIsTheOwnersWords() {
        val root = Files.createTempDirectory("round6-early-write").toFile()
        val requests = mutableListOf<ModelRequest>()
        var calls = 0
        val gateway = object : ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse {
                calls++
                requests += request
                val heard = request.transcript.joinToString("\n") { it.content }
                return if (heard.contains("Write the JavaScript file now")) {
                    ModelResponse.ToolCall(
                        "create_file",
                        JSONObject().put("path", "compiler.js").put("content", PASSING_COMPILER).toString()
                    )
                } else {
                    ModelResponse.ToolCall("list_files", """{"path":""}""")
                }
            }
        }
        val packaged = """
            OPEN JOB (do not claim there is no prior task):
            - id: job-1
            - status: open
            - goal: OPEN JOB (do not claim there is no prior task)
            Current request:
            Write a tiny compiler in JavaScript that turns arithmetic like (1+2)*3 into stack-machine instructions, run it, and test it on a few examples including a bad input
        """.trimIndent()
        val events = AutonomousAgent(root, emptyKnowledge(), gateway, AutonomousAgentConfig(maxTurns = 6))
            .run(packaged)
        assertTrue(calls in 2..6)
        assertTrue(requests.first().tools.any { it.name == "create_file" })
        assertTrue(requests.first().tools.none { it.name == "research_web" || it.name == "read_chat_history" })
        requests.forEach { sent ->
            val tokens = ContextBudget.estimateTokens(sent.system) +
                ContextBudget.estimateTokens(sent.user) +
                sent.transcript.sumOf { ContextBudget.estimateTokens(it.content) } +
                ContextBudget.toolTokens(sent.tools)
            assertTrue("request was $tokens tokens", tokens < 8_000)
        }
        val approval = events.last() as AutonomousAgentEvent.ApprovalRequired
        assertTrue(approval.task.summary.contains("compiler.js"))
        assertTrue(approval.task.summary.contains("I ran the examples and they matched."))
        assertFalse(approval.task.summary.contains("I have not run this yet"))
        assertFalse(approval.task.summary.contains("OPEN JOB"))
        assertFalse(approval.task.request.contains("OPEN JOB"))
        assertTrue(approval.task.request.contains("stack-machine"))
        val experience = root.resolve(".coding-agent/experience.tsv").readText()
        assertTrue(experience.contains("stack-machine"))
        assertFalse(experience.contains("OPEN JOB"))
        assertFalse(experience.contains("do not claim"))
        assertFalse(root.resolve("compiler.js").exists())
    }

    private fun emptyKnowledge() = object : AgentKnowledge {
        override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
    }

    private val PASSING_COMPILER = """
        var expr = String(process.argv[2] || "");
        function bad(s) {
          if (!s) return true;
          if (/[+\-*/]$/.test(s)) return true;
          if (s.indexOf("++") >= 0) return true;
          if (/[^0-9+\-*/().\s]/.test(s)) return true;
          return false;
        }
        if (bad(expr)) console.log("error");
        else if (expr === "(1+2)*3") console.log("PUSH 1\nPUSH 2\nADD\nPUSH 3\nMUL");
        else if (expr === "-3+1") console.log("PUSH -3\nPUSH 1\nADD");
        else console.log("PUSH 0");
    """.trimIndent()

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
