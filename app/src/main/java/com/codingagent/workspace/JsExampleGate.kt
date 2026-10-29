package com.codingagent.workspace

/**
 * ONE JOB: Run staged JavaScript on this phone before it can be saved.
 * A compiler request is also checked on bad input and on a negative number.
 */
object JsExampleGate {
    fun check(code: String, request: String): RunReport {
        val examples = examplesFor(request)
        val results = examples.map { (input, expected) ->
            val report = DeviceCodeRunner.run(
                ProgramRequest("javascript", code, args = listOf(input), stdin = input)
            )
            val actual = report.stdout.trim().ifBlank { report.stderr.trim() }
            val passed = matches(expected, actual, report)
            CaseResult(input, expected, actual.ifBlank { "(no output)" }, passed)
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

    private fun examplesFor(request: String): List<Pair<String, String>> {
        val named = request.lineSequence().mapNotNull { line ->
            if ("=>" !in line) return@mapNotNull null
            val input = line.substringBefore("=>").trim()
            val expected = line.substringAfter("=>").trim()
            if (input.isEmpty() || expected.isEmpty()) null else input to expected
        }.toList()
        val strict = request.contains("stack", ignoreCase = true) ||
            request.contains("bad input", ignoreCase = true) ||
            request.contains("(1+2)*3")
        val builtIn = if (strict) {
            listOf(
                "(1+2)*3" to "PUSH and MUL",
                "1+" to "error",
                "abc" to "error",
                "1++2" to "error",
                "-3+1" to "PUSH -3"
            )
        } else {
            listOf("1+1" to "some output")
        }
        return (named + builtIn).distinctBy { it.first }
    }

    private fun matches(expected: String, actual: String, report: RunReport): Boolean {
        val lines = actual.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val crashed = actual.contains("Exception") || report.stderr.contains("Exception") || actual.contains("\n\tat ")
        return when (expected) {
            "error" -> !crashed && lines.any { it.equals("error", ignoreCase = true) } && lines.none { it.startsWith("PUSH") }
            "PUSH and MUL" -> !crashed && actual.contains("PUSH") && actual.contains("MUL") &&
                lines.none { it.equals("error", ignoreCase = true) }
            "PUSH -3" -> !crashed && lines.any { it == "PUSH -3" }
            "some output" -> !crashed && report.exitCode == 0 && actual.isNotBlank()
            else -> !crashed && report.exitCode == 0 && actual.trim() == expected.trim()
        }
    }
}
