package com.codingagent.intake

import com.codingagent.domain.OperationKind
import com.codingagent.domain.TaskOperation
import java.io.File

class GoalInterpreter(private val projectRoot: File) {
    fun interpret(request: String, operation: TaskOperation = TaskOperation()): GoalContract {
        val normalized = request.replace(Regex("\\s+"), " ").trim()
        require(normalized.isNotEmpty()) { "A coding request is required" }
        val focus = currentRequestFocus(normalized)
        val paths = pathTokens(focus).distinct()
        val symbols = symbolTokens(focus).distinct()
        val intent = intent(focus, operation)
        val constraints = constraints(focus)
        val ambiguity = if (intent != TaskIntent.UNKNOWN && focus.length < 4) listOf("request is too short to establish a goal") else emptyList()
        val confidence = score(intent, operation, paths, symbols, ambiguity)
        return GoalContract(normalized, focus.take(500), intent, paths, symbols, constraints, acceptance(intent), ambiguity, confidence, ambiguity.isEmpty() && (confidence >= 40 || intent == TaskIntent.UNKNOWN))
    }

    private fun intent(request: String, operation: TaskOperation): TaskIntent {
        if (operation.kind != OperationKind.NONE) return when {
            matches(request, "create|add|new") && !matches(request, "replace|append|remove") -> TaskIntent.CREATE
            matches(request, "refactor|restructure|rename|clean") -> TaskIntent.REFACTOR
            matches(request, "fix|debug|broken|error|crash|bug") -> TaskIntent.DEBUG
            else -> TaskIntent.CHANGE
        }
        return when {
            matches(request, "create|add|new file|write a|generate") -> TaskIntent.CREATE
            matches(request, "test|tests|testing|verify|build") -> TaskIntent.TEST
            matches(request, "fix|debug|broken|error|crash|bug|repair|patch") -> TaskIntent.DEBUG
            matches(request, "refactor|restructure|rename|clean up|cleanup") -> TaskIntent.REFACTOR
            matches(request, "change|edit|update|modify|implement|add|remove|delete|insert") -> TaskIntent.CHANGE
            matches(request, "explain|why|what does|understand|research|learn|study|investigate|compare|explore") -> TaskIntent.EXPLAIN
            matches(request, "inspect|list files|search project|find file|analy[sz]e|analysis|review|audit|assess|evaluate|thoughts|look over|report on|summarize") -> TaskIntent.INSPECT
            isGreetingOrChat(request) -> TaskIntent.UNKNOWN
            else -> TaskIntent.CHANGE
        }
    }

    private fun currentRequestFocus(request: String): String {
        val marker = "Current request:"
        val index = request.lastIndexOf(marker, ignoreCase = true)
        return if (index >= 0) request.substring(index + marker.length).trim().ifBlank { request } else request
    }

    private fun isGreetingOrChat(request: String): Boolean {
        if (request.length <= 24 && !matches(request, "file|class|function|bug|test|build|code|kotlin|java|project|src/|app/")) {
            if (matches(request, "hi|hello|hey|yo|sup|thanks|thank you|ok|okay|cool|ping|test message")) return true
            if (request.split(Regex("\\s+")).size <= 3 && !request.contains('/') && !request.contains('.')) return true
        }
        return false
    }

    private fun constraints(request: String): List<String> = buildList {
        if (matches(request, "without changing|do not change|don't change|read only|readonly")) add("do not modify project files")
        if (matches(request, "keep tests|preserve tests|backward compatible|no breaking")) add("preserve existing behavior and tests")
        if (matches(request, "minimal|smallest change")) add("prefer the smallest change")
        if (matches(request, "offline|no internet|local only")) add("do not use network resources")
    }

    private fun acceptance(intent: TaskIntent): List<String> = listOf(when (intent) {
        TaskIntent.TEST -> "requested checks complete successfully"
        TaskIntent.EXPLAIN, TaskIntent.INSPECT -> "requested repository evidence is collected"
        TaskIntent.CHANGE, TaskIntent.CREATE, TaskIntent.REFACTOR, TaskIntent.DEBUG -> "requested change is present or explained"
        TaskIntent.UNKNOWN -> "goal is clarified before execution"
    }) + when {
        projectRoot.resolve("gradlew").isFile -> "Gradle tests pass when verification is run"
        projectRoot.resolve("package.json").isFile -> "project test command passes when verification is run"
        projectRoot.resolve("pyproject.toml").isFile || projectRoot.resolve("pytest.ini").isFile -> "pytest passes when verification is run"
        else -> "static verification passes when verification is run"
    }

    private fun score(intent: TaskIntent, operation: TaskOperation, paths: List<String>, symbols: List<String>, ambiguity: List<String>): Int = (if (intent == TaskIntent.UNKNOWN) 25 else 70 + if (operation.kind != OperationKind.NONE) 15 else 0 + if (paths.isNotEmpty()) 10 else 0 + if (symbols.isNotEmpty()) 5 else 0 - ambiguity.size * 20).coerceIn(0, 99)

    private fun pathTokens(request: String): List<String> = Regex("(?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+|[A-Za-z0-9_.-]+\\.(?:kt|kts|java|py|js|ts|tsx|jsx|json|xml|md|toml|yaml|yml)", RegexOption.IGNORE_CASE).findAll(request).map { it.value }.filter { it != "100%" }.toList()
    private fun symbolTokens(request: String): List<String> = Regex("(?:symbol|class|function|method|fun|def)\\s+[`]?([A-Za-z_][A-Za-z0-9_]*)[`]?", RegexOption.IGNORE_CASE).findAll(request).map { it.groupValues[1] }.toList()
    private fun matches(value: String, pattern: String): Boolean = Regex("\\b(?:$pattern)\\b", RegexOption.IGNORE_CASE).containsMatchIn(value)
}
