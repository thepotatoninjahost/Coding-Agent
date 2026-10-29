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
        val runIt = request.contains("run it", ignoreCase = true) ||
            request.contains("run them", ignoreCase = true) ||
            request.contains("and run", ignoreCase = true)
        val arithmetic = request.contains("arithmetic", ignoreCase = true) ||
            request.contains("stack", ignoreCase = true) ||
            request.contains("compiler", ignoreCase = true)
        val builtIn = if (runIt && arithmetic) {
            val mentioned = Regex("""(?:-?\d+|\([^()\n]+\))(?:\s*[+\-*/]\s*(?:-?\d+|\([^()\n]+\)))+""")
                .findAll(request)
                .map { it.value.filter { char -> !char.isWhitespace() } }
                .filter { ArithmeticValue.of(it) != null || it.endsWith("+") || it.endsWith("-") || it.endsWith("*") || it.endsWith("/") }
                .map { expr -> expr to (ArithmeticValue.of(expr)?.toString() ?: "error") }
                .toList()
            mentioned + listOf(
                "2+3*4" to "14",
                "10/(5-3)" to "5",
                "-3+1" to "-2",
                "1+" to "error",
                "1/0" to "error"
            )
        } else if (request.contains("stack", ignoreCase = true) || request.contains("(1+2)*3")) {
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
        val crashed = actual.contains("Exception") || report.stderr.contains("Exception") || actual.contains("\n\tat ") ||
            report.timedOut || report.stderr.contains("too long") || report.stderr.contains("too much") ||
            report.stderr.contains("Java", ignoreCase = true)
        return when (expected) {
            "error" -> !crashed && lines.size == 1 && lines[0].equals("error", ignoreCase = true)
            "PUSH and MUL" -> !crashed && actual.contains("PUSH") && actual.contains("MUL") &&
                lines.none { it.equals("error", ignoreCase = true) }
            "PUSH -3" -> !crashed && lines.any { it == "PUSH -3" }
            "some output" -> !crashed && report.exitCode == 0 && actual.isNotBlank()
            else -> !crashed && report.exitCode == 0 && actual.trim() == expected.trim()
        }
    }
}

/** The value of a small arithmetic expression, computed here so the phone does not trust the model's claim. */
object ArithmeticValue {
    fun of(raw: String): Int? = Parser(raw.trim()).parse()

    private class Parser(private val text: String) {
        private var index = 0

        fun parse(): Int? {
            if (text.isEmpty()) return null
            val value = parseExpr() ?: return null
            skip()
            return if (index == text.length) value else null
        }

        private fun skip() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun parseExpr(): Int? {
            var value = parseTerm() ?: return null
            while (true) {
                skip()
                val op = text.getOrNull(index) ?: break
                if (op != '+' && op != '-') break
                index++
                val right = parseTerm() ?: return null
                value = if (op == '+') value + right else value - right
            }
            return value
        }

        private fun parseTerm(): Int? {
            var value = parseFactor() ?: return null
            while (true) {
                skip()
                val op = text.getOrNull(index) ?: break
                if (op != '*' && op != '/') break
                index++
                val right = parseFactor() ?: return null
                if (op == '/' && right == 0) return null
                value = if (op == '*') value * right else value / right
            }
            return value
        }

        private fun parseFactor(): Int? {
            skip()
            if (text.getOrNull(index) == '-') {
                index++
                val inner = parseFactor() ?: return null
                return -inner
            }
            if (text.getOrNull(index) == '(') {
                index++
                val inner = parseExpr() ?: return null
                skip()
                if (text.getOrNull(index) != ')') return null
                index++
                return inner
            }
            if (text.getOrNull(index)?.isDigit() != true) return null
            var number = 0
            while (text.getOrNull(index)?.isDigit() == true) {
                number = number * 10 + (text[index] - '0')
                index++
            }
            return number
        }
    }
}
