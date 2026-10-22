package com.codingagent.core

import com.codingagent.agent.AgentKnowledge
import com.codingagent.agent.AgentPlan
import com.codingagent.agent.AgentStep
import com.codingagent.agent.AutonomousAgent
import com.codingagent.agent.AutonomousAgentEvent
import com.codingagent.agent.ContextBudget
import com.codingagent.agent.ModelCallWithRetry
import com.codingagent.agent.ModelFailure
import com.codingagent.agent.PlainEnglish
import com.codingagent.agent.PlanningLoop
import com.codingagent.agent.StagedSourceCheck
import com.codingagent.agent.ToolKind
import com.codingagent.agent.ToolResultPager
import com.codingagent.model.AgentModelProtocol
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse
import com.codingagent.model.ProviderQuotaBook
import com.codingagent.research.HackerNewsResearchProvider
import com.codingagent.research.ResearchErrors
import com.codingagent.research.WikipediaResearchProvider
import com.codingagent.workspace.ChangeDiff
import com.codingagent.workspace.KnowledgeHit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class LiveRunFixesTest {
    @Test
    fun plainEnglishReplacementLeavesFileNamesAndPathsAlone() {
        val named = ChangeDiff.ownerFacingReason("Update compiler.py so the compiler can read text.")
        assertTrue(named.contains("compiler.py"))
        assertFalse(named.contains("can run.py"))
        assertFalse(named.contains("a tiny a "))
        val tiny = ChangeDiff.ownerFacingReason("Build a tiny compiler.")
        assertFalse(tiny.contains("a tiny a "))
        assertTrue(tiny.contains("program that turns written instructions"))
        val coded = ChangeDiff.ownerFacingReason("Keep `compiler` and src/compiler.py as written.")
        assertTrue(coded.contains("`compiler`"))
        assertTrue(coded.contains("src/compiler.py"))
    }

    @Test
    fun approvalCardDoesNotClaimTheFileWasRun() {
        val text = ChangeDiff.ownerFacingReason("The compiler works and the tests passed.")
        assertFalse(text.contains("works"))
        assertFalse(text.contains("tests passed"))
        assertTrue(text.contains("has not been run"))
    }

    @Test
    fun shortRateLimitIsRetriedAndATooBigRequestIsNotASlowDown() {
        val slow = "Model HTTP 429: Rate limit exceeded. tokens per minute"
        assertTrue(ModelFailure.isRetryable(slow))
        assertFalse(ModelFailure.isRetryable("Model HTTP 503: ResourceExhausted: Worker local total request limit reached"))
        val tooBig = "Model HTTP 413: Request too large. Limit 8000, Requested 8007. code rate_limit_exceeded"
        assertTrue(ModelFailure.isRequestTooLarge(tooBig))
        assertFalse(ModelFailure.isRateLimit(tooBig))
        assertFalse(ModelFailure.isRetryable(tooBig))
        assertTrue(ModelFailure.humanize(tooBig).contains("too big"))
        assertFalse(ModelFailure.humanize(tooBig).contains("slow down"))
        val funds = "Model HTTP 403: You are out of funds"
        assertTrue(ModelFailure.isOutOfFunds(funds))
        assertFalse(ModelFailure.isRateLimit(funds))
        assertTrue(ModelFailure.humanize(funds).contains("out of funds"))
        val gaveUp = "ALL_HELPERS_EXHAUSTED\nresume-at-ms:1893456000000\nresume-gave-up"
        val line = ModelFailure.humanize(gaveUp)
        assertTrue(line.contains("Send the request again"))
        assertFalse(line.contains("do not need to send", ignoreCase = true))
    }

    @Test
    fun aSingleSlowDownIsTriedAgain() {
        val calls = AtomicInteger()
        val gateway = object : ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse {
                val n = calls.incrementAndGet()
                return if (n == 1) {
                    ModelResponse.Failure("Model HTTP 429: Rate limit exceeded. try again in 1")
                } else {
                    ModelResponse.Text("continued")
                }
            }
        }
        val response = ModelCallWithRetry.call(gateway, { ModelRequest("s", "u", emptyList()) }, { false }, {})
        assertTrue(response is ModelResponse.Text)
        assertEquals("continued", (response as ModelResponse.Text).content)
        assertEquals(2, calls.get())
    }

    @Test
    fun requestTooLargeDoesNotLockTheHelperAndOutOfFundsSkipsTheProviderForADay() {
        val quota = ProviderQuotaBook()
        val now = 1_700_000_000_000L
        quota.noteFailure(
            "groq",
            "openai/gpt-oss-120b",
            "Model HTTP 413: Request too large. code rate_limit_exceeded",
            now
        )
        assertTrue(quota.isAvailable("groq", "openai/gpt-oss-120b", now + 1_000))
        quota.noteFailure("aiml", "stealth/union-alpha", "Model HTTP 403: insufficient quota, out of funds", now)
        assertFalse(quota.isAvailable("aiml", "stealth/union-alpha", now + 60_000))
        assertFalse(quota.isAvailable("aiml", "another-model", now + 60_000))
        assertTrue(quota.isAvailable("groq", "openai/gpt-oss-120b", now + 60_000))
        assertTrue(quota.isAvailable("aiml", "stealth/union-alpha", now + ProviderQuotaBook.FUNDS_WAIT_MS + 1))
    }

    @Test
    fun sentPromptIsSmallerThanTheFullInstructionsAndKeepsAnOwnerAddition() {
        val full = AgentModelProtocol.DEFAULT_SYSTEM
        val sent = ContextBudget.systemForRequest(full + " OWNER-PROVIDED ADDITIONAL INSTRUCTIONS: Prefer concise Kotlin examples.")
        assertTrue(sent.length < full.length)
        assertTrue(sent.contains("Code changes (create_file, replace_text) only STAGE a proposal"))
        assertTrue(sent.contains("Prefer concise Kotlin examples."))
        val huge = "Evidence so far:\n" + "x".repeat(20_000)
        val fitted = ContextBudget.fit(sent, "Request:\nhello\n\n$huge", emptyList(), 800)
        assertTrue(ContextBudget.estimateTokens(fitted.system + fitted.user) <= 800)
        assertTrue(fitted.user.contains("shortened"))
    }

    @Test
    fun cutOffSourceIsRejectedAndAFinishedFileIsNot() {
        val cutOff = "def compile_source(text):\n    \"\"\"Turn written instructions into\n"
        assertTrue(StagedSourceCheck.problem("compiler.py", cutOff)!!.contains("did not save"))
        val whole = "def compile_source(text):\n    \"\"\"Read the text.\"\"\"\n    return text\n"
        assertNull(StagedSourceCheck.problem("compiler.py", whole))
        assertNull(StagedSourceCheck.problem("notes.md", cutOff))
    }

    @Test
    fun missingToolResultSaysItIsGone() {
        val pager = ToolResultPager(4)
        val page = pager.present("listing", "abcdefghij")
        assertTrue(page.contains("RESULT_ID="))
        assertTrue(page.contains("MORE=true"))
        val gone = pager.continueResult("tool-result-99", 0)
        assertTrue(gone.contains("saved result is gone"))
        assertTrue(gone.contains("Do not guess"))
    }

    @Test
    fun verifyIsAllowedAfterTheRepositorySummaryInThePlan() {
        val loop = PlanningLoop(
            AgentPlan("test", listOf(AgentStep("intake", "intake"), AgentStep("understand", "understand"), AgentStep("verify", "verify")), emptyList())
        )
        loop.completePhase("intake", "parsed")
        loop.completePhase("understand", "repository summary established")
        assertEquals(null, loop.authorizeTool("verify", ToolKind.VERIFY))
    }

    @Test
    fun jargonIsCaughtAndResearchErrorsStayPlain() {
        assertTrue(PlainEnglish.hasJargon("The agentic loop uses checksum-backed rollback."))
        val plain = PlainEnglish.soften("The agentic loop uses checksum-backed rollback.")
        assertFalse(plain.contains("agentic"))
        assertFalse(plain.contains("checksum"))
        assertFalse(plain.contains("rollback"))
        val explained = ResearchErrors.plain("PKIX path building failed | HTTP 429 | Validation Failed")
        assertFalse(explained.contains("PKIX"))
        assertTrue(explained.contains("certificate"))
        assertTrue(explained.contains("slow down"))
        assertTrue(explained.contains("GitHub did not accept"))
    }

    @Test
    fun wikipediaAndHackerNewsParsersKeepAHit() {
        val wiki = WikipediaResearchProvider { _ ->
            jsonConnection(
                """{"query":{"search":[{"title":"Compiler","snippet":"A compiler turns text into a program."}]}}"""
            )
        }
        val wikiHit = wiki.search("compiler", 2).hits.single()
        assertEquals("https://en.wikipedia.org/wiki/Compiler", wikiHit.url)
        assertTrue(wikiHit.excerpt.contains("compiler"))

        val news = HackerNewsResearchProvider { _ ->
            jsonConnection(
                """{"hits":[{"title":"Kotlin 2.1","url":"https://example.com/kotlin","objectID":"9","story_text":"A release note."}]}"""
            )
        }
        val newsHit = news.search("kotlin release", 2).hits.single()
        assertEquals("https://example.com/kotlin", newsHit.url)
        assertEquals("Kotlin 2.1", newsHit.title)
    }

    @Test
    fun truncatedCompilerIsNotStagedAndIsNotCalledWorking() {
        val root = Files.createTempDirectory("truncated-compiler").toFile()
        val reason = """
            What I want to do: Add compiler.py, a small program that reads simple instructions.
            Why: You asked for a tiny compiler.
            What will change: Create compiler.py.
            Possible effects: A new file appears after you say yes.
            Risks: The file might not do what you expect.
            What your approval means: Nothing is written until you say yes twice.
        """.trimIndent()
        val content = "def compile_source(text):\n    \"\"\"Turn written instructions into\n"
        val createArgs = JSONObject()
            .put("path", "compiler.py")
            .put("content", content)
            .put("reason", reason)
            .toString()
        var step = 0
        val gateway = object : ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse {
                step++
                return when (step) {
                    1 -> ModelResponse.ToolCall("list_files", """{"path":""}""")
                    2 -> ModelResponse.ToolCall("create_file", createArgs)
                    else -> ModelResponse.Text("The compiler is working and the tests passed.")
                }
            }
        }
        val knowledge = object : AgentKnowledge {
            override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
        }
        val agent = AutonomousAgent(root, knowledge, gateway)
        val events = agent.run("Build me a tiny compiler")
        assertTrue(events.none { it is AutonomousAgentEvent.ApprovalRequired })
        assertTrue(agent.pendingProposals().isEmpty())
        assertFalse(root.resolve("compiler.py").exists())
        val rejected = events.filterIsInstance<AutonomousAgentEvent.ToolFinished>()
            .filter { it.name == "create_file" }
        assertTrue(rejected.isNotEmpty())
        assertTrue(rejected.all { !it.success })
        assertTrue(rejected.first().result.contains("did not save"))
        val last = events.last()
        assertTrue(last is AutonomousAgentEvent.Completed)
        val task = (last as AutonomousAgentEvent.Completed).task
        assertEquals("needs-input", task.status)
        assertFalse(task.summary.contains("working", ignoreCase = true))
        assertFalse(task.summary.contains("tests passed", ignoreCase = true))
    }

    @Test
    fun explainAnswerWithJargonIsSentBackForPlainEnglish() {
        val root = Files.createTempDirectory("plain-explain").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        var step = 0
        val gateway = object : ModelGateway {
            override fun complete(request: ModelRequest): ModelResponse {
                step++
                return when (step) {
                    1 -> ModelResponse.ToolCall("search_project", """{"query":"main"}""")
                    2 -> ModelResponse.Text("The agentic loop uses checksum-backed rollback to stay safe.")
                    else -> ModelResponse.Text("It reads the project and keeps a saved copy you can go back to.")
                }
            }
        }
        val knowledge = object : AgentKnowledge {
            override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
        }
        val events = AutonomousAgent(root, knowledge, gateway)
            .run("Explain this project in one short paragraph. Do not change any files.")
        val last = events.last() as AutonomousAgentEvent.Completed
        assertFalse(last.task.summary.contains("agentic"))
        assertFalse(last.task.summary.contains("checksum"))
        assertTrue(last.task.summary.contains("saved copy"))
        assertTrue(step >= 3)
    }

    private fun jsonConnection(body: String): HttpURLConnection =
        object : HttpURLConnection(java.net.URL("https://example.com/research")) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getInputStream() = body.byteInputStream()
            override fun getErrorStream() = "".byteInputStream()
        }
}
