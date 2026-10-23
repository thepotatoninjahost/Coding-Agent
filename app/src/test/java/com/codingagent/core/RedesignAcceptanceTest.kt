package com.codingagent.core

import com.codingagent.agent.AgentKnowledge
import com.codingagent.agent.AutonomousAgent
import com.codingagent.agent.AutonomousAgentEvent
import com.codingagent.agent.StuckDetector
import com.codingagent.model.MessageAssembler
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelMessage
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse
import com.codingagent.research.StructuredSources
import com.codingagent.workspace.ChangeDiff
import com.codingagent.workspace.DeviceCodeRunner
import com.codingagent.workspace.ProgramRequest
import com.codingagent.workspace.RunReport
import com.codingagent.workspace.SearchReplace
import com.codingagent.workspace.CaseResult
import com.codingagent.workspace.KnowledgeHit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.nio.file.Files

class RedesignAcceptanceTest {
    @Test
    fun javascriptRunnerHandlesASumAndABadInput() {
        val code = """
            var text = String(__stdin || "").trim();
            if (!text || /[+\-*/]${'$'}/.test(text)) console.log("error");
            else console.log(String(Function("return (" + text + ")")()));
        """.trimIndent()
        val good = DeviceCodeRunner.run(ProgramRequest("javascript", code, stdin = "1+1\n"))
        assertEquals(0, good.exitCode)
        assertTrue(good.stdout.contains("2"))
        val bad = DeviceCodeRunner.cases(code, listOf("1+" to "error", "(1+2" to "error"))
        assertTrue(bad.passed)
        assertFalse(bad.stderr.contains("Traceback"))
    }

    @Test
    fun searchReplaceMatchesOnceAndReportsAMiss() {
        val updated = SearchReplace.apply("fun main() = 1\n", "fun main() = 1\n", "fun main() = 2\n")
        assertTrue(updated is SearchReplace.ApplyResult.Applied)
        val missed = SearchReplace.apply("fun main() = 1\n", "missing", "x")
        assertTrue(missed is SearchReplace.ApplyResult.Failed)
    }

    @Test
    fun endOfLifeAnswerUsesTheDatedRelease() {
        val fact = StructuredSources.answer("What is the latest version of Kotlin?") { _ ->
            json("""[{"cycle":"2.4","latest":"2.4.21","releaseDate":"2026-10-08"}]""")
        }
        assertTrue(fact!!.contains("2.4.21"))
        assertTrue(fact.contains("October 8, 2026"))
        assertTrue(fact.contains("endoflife.date"))
        assertFalse(fact.contains("Skip to content"))
    }

    @Test
    fun stuckDetectorStopsARepeatedFailedStep() {
        val step = StuckDetector.Step("edit|compiler.js", "same error", error = true, textOnly = false)
        assertNull(StuckDetector.check(List(2) { step }))
        val nudge = StuckDetector.check(List(3) { step })
        assertTrue(nudge!!.contains("three"))
        val repeated = StuckDetector.check(List(4) { step.copy(error = false) })
        assertTrue(repeated!!.contains("repeated"))
    }

    @Test
    fun messageOrderRejectsAToolCallBeforeTheOwner() {
        val good = MessageAssembler.messages(
            ModelRequest(
                "system",
                "question",
                emptyList(),
                listOf(
                    ModelMessage("assistant", "looking", "call_1", "list_files", "{}"),
                    ModelMessage("tool", "a.kt", "call_1")
                )
            )
        )
        assertNull(MessageAssembler.problem(good))
        val broken = org.json.JSONArray()
            .put(JSONObject().put("role", "assistant").put("tool_calls", org.json.JSONArray().put(JSONObject().put("id", "x"))))
        assertTrue(MessageAssembler.problem(broken) != null)
    }

    @Test
    fun failedRunIsShownAsFailedOnTheCard() {
        val run = RunReport(
            "quickjs",
            1,
            "1+ => 0",
            "Expected 2. Got 0.",
            false,
            listOf(CaseResult("1+", "2", "0", false))
        )
        assertTrue(ChangeDiff.runSentence(run).contains("did not match"))
        assertFalse(ChangeDiff.runSentence(null).contains("matched"))
    }

    @Test
    fun explainStaysPlainAndResearchUsesTheDatedSource() {
        val root = Files.createTempDirectory("redesign-accept").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val knowledge = object : AgentKnowledge {
            override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
        }
        var explainCalls = 0
        val explain = AutonomousAgent(root, knowledge, object : ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse {
                explainCalls++
                return ModelResponse.Text("This project is a small program that starts in Main.kt.")
            }
        })
        val explained = explain.run("Explain this project in one short paragraph. Do not change any files.")
        val explainSummary = (explained.last() as AutonomousAgentEvent.Completed).task.summary
        assertTrue(explainCalls <= 2)
        assertFalse(explainSummary.contains("agentic"))
        assertTrue(explainSummary.contains("Main.kt"))

        var researchCalls = 0
        val research = AutonomousAgent(
            root,
            knowledge,
            object : ModelGateway {
                override fun complete(request: ModelRequest): ModelResponse {
                    researchCalls++
                    return if (researchCalls == 1) {
                        ModelResponse.ToolCall("research_web", """{"query":"latest version of Kotlin"}""")
                    } else {
                        ModelResponse.Text("I think Kotlin 1.9 is current.")
                    }
                }
            },
            sourceConnect = { _ ->
                json("""[{"cycle":"2.4","latest":"2.4.21","releaseDate":"2026-10-08"}]""")
            }
        )
        val researched = research.run("What is the latest version of Kotlin?")
        val summary = (researched.last() as AutonomousAgentEvent.Completed).task.summary
        assertTrue(summary.contains("2.4.21"))
        assertTrue(summary.contains("October 8, 2026"))
        assertFalse(summary.contains("1.9"))
        assertFalse(summary.contains("Skip to content"))
        assertFalse(summary.contains("Model HTTP"))
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
