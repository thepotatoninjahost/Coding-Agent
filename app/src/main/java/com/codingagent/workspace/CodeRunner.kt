package com.codingagent.workspace

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ONE JOB: Run a program on this phone and return what it printed.
 * JavaScript runs in Rhino. QuickJS needs a newer Android build than this app uses.
 */
data class ProgramRequest(
    val language: String,
    val code: String,
    val args: List<String> = emptyList(),
    val stdin: String = "",
    val files: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = 20_000
)

data class CaseResult(
    val label: String,
    val expected: String,
    val actual: String,
    val passed: Boolean
)

data class RunReport(
    val runner: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val cases: List<CaseResult> = emptyList()
) {
    val ran: Boolean get() = runner != "none"
    val passed: Boolean get() = ran && !timedOut && cases.all { it.passed } && (cases.isNotEmpty() || exitCode == 0)
}

fun interface CodeRunner {
    fun run(request: ProgramRequest): RunReport
}

object DeviceCodeRunner : CodeRunner {
    override fun run(request: ProgramRequest): RunReport {
        val language = request.language.lowercase()
        if (language.isNotBlank() && language != "javascript" && language != "js") {
            return RunReport(
                runner = "none",
                exitCode = 127,
                stdout = "",
                stderr = "This phone runs JavaScript. Ask for the program in JavaScript.",
                timedOut = false
            )
        }
        val script = wrap(request)
        val timeout = request.timeoutMillis.coerceIn(1_000, 20_000)
        return runRhino(script, timeout)
    }

    fun cases(code: String, examples: List<Pair<String, String>>, argsFor: (String) -> List<String> = { emptyList() }, stdinFor: (String) -> String = { it }): RunReport {
        val results = examples.map { (input, expected) ->
            val report = run(ProgramRequest("javascript", code, args = argsFor(input), stdin = stdinFor(input)))
            val actual = report.stdout.trim()
            val crashed = report.stderr.contains("\n\tat ") || report.stderr.contains("Exception") ||
                actual.contains("\n\tat ") || actual.contains("Exception") || actual.contains("Error:") ||
                Regex("(?i)\\b(typeerror|syntaxerror|referenceerror)\\b").containsMatchIn(actual)
            val infinity = actual.equals("Infinity", ignoreCase = true)
            val passed = if (expected.equals("error", ignoreCase = true)) {
                !crashed && !infinity && (report.exitCode != 0 || actual.contains("error"))
            } else {
                report.exitCode == 0 && !report.timedOut && actual == expected.trim()
            }
            CaseResult(input, expected, if (report.timedOut) "timed out" else actual.ifBlank { report.stderr.trim() }, passed)
        }
        val failed = results.firstOrNull { !it.passed }
        return RunReport(
            runner = "javascript",
            exitCode = if (results.all { it.passed }) 0 else 1,
            stdout = results.joinToString("\n") { "${it.label} => ${it.actual}" },
            stderr = failed?.let { "Expected ${it.expected}. Got ${it.actual}." }.orEmpty(),
            timedOut = false,
            cases = results
        )
    }

    private fun wrap(request: ProgramRequest): String {
        val files = request.files.entries.joinToString(",") { (name, body) ->
            "${jsString(name)}:${jsString(body)}"
        }
        val args = request.args.joinToString(",") { jsString(it) }
        return """
            var __out = [];
            var console = { log: function(){ __out.push(Array.prototype.join.call(arguments, " ")); }, error: function(){ __out.push(Array.prototype.join.call(arguments, " ")); } };
            var __files = { $files };
            function readFile(name) { return Object.prototype.hasOwnProperty.call(__files, name) ? __files[name] : ""; }
            var __args = [$args];
            var __stdin = ${jsString(request.stdin)};
            var process = { argv: ["javascript", "program.js"].concat(__args) };
            ${request.code}
            __out.join("\n");
        """.trimIndent()
    }

    private fun jsString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    private fun runRhino(script: String, timeoutMillis: Long): RunReport {
        val start = System.currentTimeMillis()
        val timedOut = AtomicBoolean(false)
        val factory = object : ContextFactory() {
            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                if (System.currentTimeMillis() - start > timeoutMillis) {
                    timedOut.set(true)
                    throw RuntimeException("timed out")
                }
            }
        }
        return try {
            val cx = factory.enterContext()
            try {
                cx.optimizationLevel = -1
                cx.instructionObserverThreshold = 10_000
                val scope = cx.initStandardObjects()
                val value = cx.evaluateString(scope, script, "program.js", 1, null)
                RunReport("rhino", 0, Context.toString(value).orEmpty(), "", false)
            } finally {
                Context.exit()
            }
        } catch (error: Throwable) {
            if (timedOut.get() || error.message?.contains("timed out") == true) {
                RunReport("rhino", 124, "", "The program ran too long and was stopped.", true)
            } else {
                val message = error.message?.lineSequence()?.firstOrNull().orEmpty().ifBlank { "The program stopped with an error." }
                RunReport("rhino", 1, "", message, false)
            }
        }
    }
}
