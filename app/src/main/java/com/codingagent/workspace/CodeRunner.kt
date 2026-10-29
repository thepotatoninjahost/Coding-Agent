package com.codingagent.workspace

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.JavaScriptException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrappedException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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

object PhoneJavaScript {
    /**
     * Rhino 1.7.15 at ES6. Checked by running each feature through this engine.
     * let, const, arrows, template strings, for-of, destructuring, rest, and ** work.
     * class, spread, default parameters, async/await, ?., ??, and import do not.
     */
    fun rules(): String = """
        This phone runs JavaScript in Rhino at ES6. You may use var, let, const, function, arrow functions, template strings, for-of with var or let, destructuring, rest parameters, and **.
        Do not use class, for (const ... of ...), the spread operator, default parameters, async, await, import, export, optional chaining (?.), nullish coalescing (??), or private fields (#). async fails with "missing ; before statement".
        Do not use Java, java.lang, or System.exit. process.exit(code) stops only this program and records the exit code.
        console.log prints a line. process.argv[2] is the text to compile. require("fs").readFileSync() returns that same text. Write the whole program in one create_file. A later create_file replaces the draft.
    """.trimIndent()
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
        val timeout = request.timeoutMillis.coerceIn(200, 20_000)
        return runSandboxed(request, timeout)
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

    private class ScriptExit(val code: Int) : RuntimeException("exit $code")
    private class OutputCap : RuntimeException("The program printed too much and was stopped.")

    private fun runSandboxed(request: ProgramRequest, timeoutMillis: Long): RunReport {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "coding-agent-js").apply { isDaemon = true }
        }
        val future = executor.submit<RunReport> { runOnWorker(request, timeoutMillis) }
        return try {
            future.get(timeoutMillis + 400L, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            RunReport("rhino", 124, "", "The program ran too long and was stopped.", true)
        } catch (error: java.util.concurrent.ExecutionException) {
            val cause = error.cause ?: error
            RunReport("rhino", 1, "", cause.message?.lineSequence()?.firstOrNull().orEmpty().ifBlank { "The program stopped with an error." }, false)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun runOnWorker(request: ProgramRequest, timeoutMillis: Long): RunReport {
        val start = System.currentTimeMillis()
        val timedOut = AtomicBoolean(false)
        val printed = StringBuilder()
        val factory = object : ContextFactory() {
            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                if (Thread.currentThread().isInterrupted || System.currentTimeMillis() - start > timeoutMillis) {
                    timedOut.set(true)
                    throw RuntimeException("timed out")
                }
            }
        }
        return try {
            val cx = factory.enterContext()
            try {
                cx.optimizationLevel = -1
                cx.languageVersion = Context.VERSION_ES6
                cx.instructionObserverThreshold = 10_000
                cx.setClassShutter(ClassShutter { false })
                val scope = cx.initSafeStandardObjects()
                installHost(cx, scope, request, printed)
                cx.evaluateString(scope, request.code, "program.js", 1, null)
                RunReport("rhino", 0, printed.toString(), "", false)
            } finally {
                Context.exit()
            }
        } catch (error: Throwable) {
            val cause = unwrap(error)
            when {
                cause is ScriptExit -> RunReport("rhino", cause.code, printed.toString(), "", false)
                cause is OutputCap -> RunReport("rhino", 1, printed.toString().take(OUTPUT_CAP), cause.message.orEmpty(), false)
                timedOut.get() || cause.message?.contains("timed out") == true ->
                    RunReport("rhino", 124, printed.toString().take(OUTPUT_CAP), "The program ran too long and was stopped.", true)
                else -> {
                    val detail = cause.message?.lineSequence()?.firstOrNull().orEmpty()
                        .ifBlank { "The program stopped with an error." }
                    RunReport("rhino", 1, printed.toString().take(OUTPUT_CAP), detail, false)
                }
            }
        }
    }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        repeat(8) {
            val next = when (current) {
                is WrappedException -> current.wrappedException ?: current.cause
                is JavaScriptException -> current.cause
                else -> current.cause
            } ?: return current
            current = next
        }
        return current
    }

    private fun installHost(cx: Context, scope: Scriptable, request: ProgramRequest, printed: StringBuilder) {
        val console = cx.newObject(scope)
        val log = LogFunction(printed)
        ScriptableObject.putProperty(console, "log", log)
        ScriptableObject.putProperty(console, "error", log)
        ScriptableObject.putProperty(scope, "console", console)

        val elements = Array<Any?>(request.args.size + 2) { index ->
            when (index) {
                0 -> "javascript"
                1 -> "program.js"
                else -> request.args[index - 2]
            }
        }
        val argv = cx.newArray(scope, elements)
        val process = cx.newObject(scope)
        ScriptableObject.putProperty(process, "argv", argv)
        ScriptableObject.putProperty(process, "exit", ExitFunction())
        ScriptableObject.putProperty(process, "env", cx.newObject(scope))
        ScriptableObject.putProperty(scope, "process", process)

        val fs = cx.newObject(scope)
        ScriptableObject.putProperty(fs, "readFileSync", ReadFunction(request.stdin))
        ScriptableObject.putProperty(fs, "writeFileSync", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any = Undefined.instance
        })
        ScriptableObject.putProperty(scope, "require", RequireFunction(fs, request.files))
        ScriptableObject.putProperty(scope, "__stdin", request.stdin)
        val blocked = cx.newObject(scope)
        listOf("java", "Packages", "JavaAdapter", "JavaImporter").forEach { name ->
            ScriptableObject.putProperty(scope, name, blocked)
        }
    }

    private class LogFunction(private val printed: StringBuilder) : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any {
            val line = args.joinToString(" ") { Context.toString(it) }
            if (printed.length > OUTPUT_CAP) throw OutputCap()
            if (printed.isNotEmpty()) printed.append('\n')
            printed.append(line.take(OUTPUT_CAP))
            if (printed.length > OUTPUT_CAP) throw OutputCap()
            return Undefined.instance
        }
    }

    private class ExitFunction : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any {
            val code = args.firstOrNull()?.let { Context.toNumber(it).toInt() } ?: 0
            throw ScriptExit(code)
        }
    }

    private class ReadFunction(private val stdin: String) : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any = stdin
    }

    private class RequireFunction(private val fs: Scriptable, private val files: Map<String, String>) : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any {
            val name = args.firstOrNull()?.let { Context.toString(it) }.orEmpty()
            if (name == "fs") return fs
            val text = files[name] ?: return cx.newObject(scope)
            return text
        }
    }

}

private const val OUTPUT_CAP = 8_000
