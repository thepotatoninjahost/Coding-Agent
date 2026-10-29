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
        val fixed = EVALUATOR
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
        val trace = root.resolve(".coding-agent/run-trace.txt").readText()
        assertTrue(trace.contains("tool create_file"))
        assertTrue(trace.contains("end:"))
        assertFalse(trace.contains("OPEN JOB"))
        assertEquals("[redacted]", com.codingagent.agent.DebugRunTrace.redact("Bearer sk-live-secret").substringAfter("Bearer ").trim())
    }

    @Test
    fun aFailedDraftIsReplacedAndThePhoneErrorIsSentBack() {
        val root = Files.createTempDirectory("round6-replace-draft").toFile()
        val broken = """
            async function compile(text) {
              console.log(text);
            }
            console.log(process.argv[2]);
        """.trimIndent()
        val requests = mutableListOf<ModelRequest>()
        val gateway = object : ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse {
                requests += request
                val body = if (requests.size == 1) broken else PASSING_COMPILER
                val path = if (requests.size == 1) "compiler.js" else "program.js"
                return ModelResponse.ToolCall(
                    "create_file",
                    JSONObject().put("path", path).put("content", body).toString()
                )
            }
        }
        val events = AutonomousAgent(root, emptyKnowledge(), gateway, AutonomousAgentConfig(maxTurns = 6))
            .run("Write a tiny compiler in JavaScript that turns arithmetic like (1+2)*3 into stack-machine instructions, run it, and test it on a few examples including a bad input")
        val tools = events.filterIsInstance<AutonomousAgentEvent.ToolFinished>()
        assertTrue(tools.none { it.result.contains("Another change is waiting") })
        assertTrue(requests.size >= 2)
        val heard = requests[1].user + requests[1].transcript.joinToString("\n") { it.content }
        assertTrue(heard.contains("missing"))
        assertTrue(heard.contains("async function"))
        assertTrue(requests[1].transcript.size <= 2)
        val tokens = ContextBudget.estimateTokens(requests[1].system) +
            ContextBudget.estimateTokens(requests[1].user) +
            requests[1].transcript.sumOf { ContextBudget.estimateTokens(it.content) } +
            ContextBudget.toolTokens(requests[1].tools)
        assertTrue("second request was $tokens tokens", tokens < 8_000)
        val approval = events.last() as AutonomousAgentEvent.ApprovalRequired
        assertTrue(approval.task.summary.contains("I ran the examples and they matched."))
        assertFalse(approval.task.summary.contains("I have not run this yet"))
        assertFalse(root.resolve("compiler.js").exists())
        assertFalse(root.resolve("program.js").exists())
    }

    @Test
    fun phoneRunsTypicalGptOssJavaScriptAndRejectsAsync() {
        val code = """
            const add = (a, b) => a + b;
            let pair = [1, 2];
            let x = pair[0];
            let y = pair[1];
            const label = "sum " + add(x, y);
            let total = 0;
            var nums = process.argv.slice(2);
            for (var i = 0; i < nums.length; i++) total += Number(nums[i]);
            console.log(label);
            console.log(String(total));
            console.log(String(require("fs").readFileSync("in", "utf8")).trim());
        """.trimIndent()
        val report = com.codingagent.workspace.DeviceCodeRunner.run(
            com.codingagent.workspace.ProgramRequest("javascript", code, args = listOf("4", "5"), stdin = "hello")
        )
        assertEquals(report.stderr + " | " + report.stdout, 0, report.exitCode)
        assertTrue(report.stdout.contains("sum 3"))
        assertTrue(report.stdout.contains("9"))
        assertTrue(report.stdout.contains("hello"))
        val modern = """
            const label = `sum ${'$'}{1 + 2}`;
            let [x, y] = [1, 2];
            const add = (a, b) => a + b;
            let total = 0;
            for (let n of [4, 5]) total += n;
            console.log(label);
            console.log(String(add(x, y)));
            console.log(String(total));
        """.trimIndent()
        val es6 = com.codingagent.workspace.DeviceCodeRunner.run(
            com.codingagent.workspace.ProgramRequest("javascript", modern)
        )
        assertEquals(es6.stderr, 0, es6.exitCode)
        assertTrue(es6.stdout.contains("sum 3"))
        val async = com.codingagent.workspace.DeviceCodeRunner.run(
            com.codingagent.workspace.ProgramRequest("javascript", "async function compile(text) { console.log(text); }\n")
        )
        assertTrue(async.exitCode != 0)
        assertTrue(async.stderr.contains("missing ;"))
    }

    @Test
    fun aJavaExitCannotKillTheRunnerAndChecksMustMatchTheRequest() {
        val exit = com.codingagent.workspace.DeviceCodeRunner.run(
            com.codingagent.workspace.ProgramRequest("javascript", "java.lang.System.exit(0);\nconsole.log('still here');")
        )
        assertTrue(exit.exitCode != 0 || exit.stderr.isNotBlank() || !exit.stdout.contains("still here"))
        assertFalse(exit.stdout.contains("still here") && exit.exitCode == 0 && exit.stderr.isBlank())
        val stopped = com.codingagent.workspace.DeviceCodeRunner.run(
            com.codingagent.workspace.ProgramRequest("javascript", "console.log('bye');\nprocess.exit(3);\nconsole.log('no');")
        )
        assertEquals(stopped.stderr + " | " + stopped.stdout, 3, stopped.exitCode)
        assertTrue(stopped.stdout.contains("bye"))
        assertFalse(stopped.stdout.contains("no"))
        val loop = com.codingagent.workspace.DeviceCodeRunner.run(
            com.codingagent.workspace.ProgramRequest("javascript", "while (true) {}", timeoutMillis = 400)
        )
        assertTrue(loop.timedOut || loop.exitCode == 124)
        val huge = com.codingagent.workspace.DeviceCodeRunner.run(
            com.codingagent.workspace.ProgramRequest("javascript", "var i = 0; while (i < 100000) { console.log('abcdefghij'); i = i + 1; }")
        )
        assertTrue(huge.stderr.contains("too much") || huge.stdout.length <= 8_000)
        val file = com.codingagent.workspace.DeviceCodeRunner.run(
            com.codingagent.workspace.ProgramRequest("javascript", "var f = new java.io.File('/etc/passwd'); console.log(String(f));")
        )
        assertFalse(file.stdout.contains("root:"))
        assertTrue(file.exitCode != 0)
        val checks = com.codingagent.workspace.JsExampleGate.check(
            EVALUATOR,
            "Write a tiny compiler in JavaScript that turns arithmetic like (1+2)*3 into stack-machine instructions, run it, and test it on a few examples including a bad input"
        )
        assertTrue(checks.passed)
        assertTrue(checks.cases.any { it.label == "(1+2)*3" && it.expected == "9" && it.passed })
        assertTrue(checks.cases.any { it.label == "-3+1" && it.expected == "-2" && it.passed })
        assertTrue(checks.cases.any { it.label == "1/0" && it.expected == "error" && it.passed })
    }

    @Test
    fun aMissingChatReloadStillShowsTheRequestAndTheOutcome() {
        val onScreen = listOf(
            com.codingagent.agent.ChatMessage(role = com.codingagent.agent.ChatRole.USER, content = "Write a tiny compiler")
        )
        val stored = listOf(
            com.codingagent.agent.ChatMessage(role = com.codingagent.agent.ChatRole.AGENT, content = "This is unfinished. I have not run it, so nothing was saved.")
        )
        val merged = com.codingagent.agent.ChatVisibility.merge(onScreen, stored, "Write a tiny compiler")
        assertTrue(merged.any { it.role == com.codingagent.agent.ChatRole.USER && it.content == "Write a tiny compiler" })
        assertTrue(merged.any { it.content.contains("unfinished") })
    }

    @Test
    fun gptOssHarmonyAndNemotronToolTextBecomeRealFileWrites() {
        val compiler = JSONObject().put("path", "compiler.js").put("content", PASSING_COMPILER)
        val harmony = "<|channel|>analysis<|message|>I will write the program.<|end|>" +
            "<|channel|>commentary to=functions.create_file <|constrain|>json<|message|>" + compiler.toString()
        val harmonyCall = com.codingagent.model.JsonModelResponseParser().parse(harmony) as com.codingagent.model.ModelResponse.ToolCall
        assertEquals("create_file", harmonyCall.name)
        assertTrue(harmonyCall.arguments.contains("compiler.js"))

        val nemotron = """
            <tool_call>
            <function=create_file>
            <parameter=path>
            compiler.js
            </parameter>
            <parameter=content>
            $PASSING_COMPILER
            </parameter>
            </function>
            </tool_call>
        """.trimIndent()
        val nemotronCall = com.codingagent.model.JsonModelResponseParser().parse(nemotron) as com.codingagent.model.ModelResponse.ToolCall
        assertEquals("create_file", nemotronCall.name)
        assertTrue(nemotronCall.arguments.contains("console.log"))

        val wrapped = "<tool_call>\n" + JSONObject()
            .put("name", "create_file")
            .put("arguments", compiler)
            .toString() + "\n</tool_call>"
        val wrappedCall = com.codingagent.model.JsonModelResponseParser().parse(wrapped) as com.codingagent.model.ModelResponse.ToolCall
        assertEquals("create_file", wrappedCall.name)
    }

    private fun emptyKnowledge() = object : AgentKnowledge {
        override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
    }

    private val EVALUATOR = """
        var src = String(process.argv[2] || "");
        function parse(s) {
          var i = 0;
          function skip() { while (i < s.length && s.charAt(i) === " ") i++; }
          function expr() {
            var value = term();
            if (value === null) return null;
            while (true) {
              skip();
              var op = s.charAt(i);
              if (op !== "+" && op !== "-") break;
              i++;
              var right = term();
              if (right === null) return null;
              value = op === "+" ? value + right : value - right;
            }
            return value;
          }
          function term() {
            var value = factor();
            if (value === null) return null;
            while (true) {
              skip();
              var op = s.charAt(i);
              if (op !== "*" && op !== "/") break;
              i++;
              var right = factor();
              if (right === null) return null;
              if (op === "/" && right === 0) return null;
              value = op === "*" ? value * right : value / right;
            }
            return value;
          }
          function factor() {
            skip();
            if (s.charAt(i) === "-") { i++; var inner = factor(); return inner === null ? null : -inner; }
            if (s.charAt(i) === "(") { i++; var inner = expr(); skip(); if (s.charAt(i) !== ")") return null; i++; return inner; }
            if (i >= s.length || s.charAt(i) < "0" || s.charAt(i) > "9") return null;
            var number = 0;
            while (i < s.length && s.charAt(i) >= "0" && s.charAt(i) <= "9") { number = number * 10 + (s.charCodeAt(i) - 48); i++; }
            return number;
          }
          var value = expr();
          skip();
          if (value === null || i !== s.length) return null;
          return value;
        }
        var result = parse(src);
        if (result === null) console.log("error");
        else console.log(String(result));
    """.trimIndent()

    private val PASSING_COMPILER = EVALUATOR

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
