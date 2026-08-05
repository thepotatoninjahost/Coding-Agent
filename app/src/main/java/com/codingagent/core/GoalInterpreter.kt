package com.codingagent.core

import java.io.File

data class GoalContract(
    val request: String,
    val goal: String,
    val intent: TaskIntent,
    val targetPaths: List<String>,
    val targetSymbols: List<String>,
    val constraints: List<String>,
    val acceptanceCriteria: List<String>,
    val ambiguity: List<String>,
    val confidence: Int,
    val ready: Boolean
)

class GoalInterpreter(private val root: File) {
    fun interpret(request: String, operation: TaskOperation = TaskOperation()): GoalContract {
        val normalized = request.replace(Regex("\\s+"), " ").trim()
        require(normalized.isNotEmpty()) { "A coding request is required" }
        val paths = pathTokens(normalized).distinct()
        val symbols = symbolTokens(normalized).distinct()
        val intent = intent(normalized, operation)
        val constraints = constraints(normalized)
        val acceptance = acceptance(normalized, intent)
        // Do not treat missing exact mutation syntax as blocking ambiguity.
        // The model can inspect files and propose changes via tools.
        val ambiguity = softAmbiguity(normalized, intent)
        val confidence = score(intent, operation, paths, symbols, ambiguity)
        return GoalContract(
            request = normalized,
            goal = normalized.take(500),
            intent = intent,
            targetPaths = paths,
            targetSymbols = symbols,
            constraints = constraints,
            acceptanceCriteria = acceptance,
            ambiguity = ambiguity,
            confidence = confidence,
            ready = ambiguity.isEmpty() && confidence >= 40
        )
    }

    private fun intent(request: String, operation: TaskOperation): TaskIntent {
        if (operation.kind != OperationKind.NONE) {
            return when {
                matches(request, "create|add|new") && !matches(request, "replace|append|remove") -> TaskIntent.CREATE
                matches(request, "refactor|restructure|rename|clean") -> TaskIntent.REFACTOR
                matches(request, "fix|debug|broken|error|crash|bug") -> TaskIntent.DEBUG
                else -> TaskIntent.CHANGE
            }
        }
        return when {
            matches(request, "create|add|new file|write a|generate") -> TaskIntent.CREATE
            matches(request, "test|tests|testing|verify|build") -> TaskIntent.TEST
            matches(request, "fix|debug|broken|error|crash|bug|repair|patch") -> TaskIntent.DEBUG
            matches(request, "refactor|restructure|rename|clean up|cleanup") -> TaskIntent.REFACTOR
            matches(request, "change|edit|update|modify|implement|add|remove|delete|insert") -> TaskIntent.CHANGE
            matches(request, "explain|why|what does|understand") -> TaskIntent.EXPLAIN
            matches(request, "inspect|show|list|search|find|analy[sz]e|analysis|review|audit|assess|evaluate|thoughts|opinion|feedback|look over|report") -> TaskIntent.INSPECT
            matches(request, "research|learn|study|investigate|compare|explore") -> TaskIntent.EXPLAIN
            else -> TaskIntent.CHANGE
        }
    }

    private fun constraints(request: String): List<String> = buildList {
        if (matches(request, "without changing|do not change|don't change|read only|readonly")) add("do not modify project files")
        if (matches(request, "keep tests|preserve tests|backward compatible|no breaking")) add("preserve existing behavior and tests")
        if (matches(request, "minimal|smallest change")) add("prefer the smallest change")
        if (matches(request, "offline|no internet|local only")) add("do not use network resources")
    }

    private fun acceptance(request: String, intent: TaskIntent): List<String> = buildList {
        when (intent) {
            TaskIntent.TEST -> add("requested checks complete successfully")
            TaskIntent.EXPLAIN, TaskIntent.INSPECT -> add("requested repository evidence is collected")
            TaskIntent.CHANGE, TaskIntent.CREATE, TaskIntent.REFACTOR, TaskIntent.DEBUG -> add("requested change is present or explained")
            TaskIntent.UNKNOWN -> add("goal is clarified before execution")
        }
        if (root.resolve("gradlew").isFile) add("Gradle tests pass when verification is run")
        else if (root.resolve("package.json").isFile) add("project test command passes when verification is run")
        else if (root.resolve("pyproject.toml").isFile || root.resolve("pytest.ini").isFile) add("pytest passes when verification is run")
        else add("static verification passes when verification is run")
    }

    /** Only hard-block on truly empty/too-short requests. File targets and exact ops are optional. */
    private fun softAmbiguity(request: String, intent: TaskIntent): List<String> = buildList {
        if (intent == TaskIntent.UNKNOWN) add("intent is unclear")
        if (request.length < 4) add("request is too short to establish a goal")
    }

    private fun score(intent: TaskIntent, operation: TaskOperation, paths: List<String>, symbols: List<String>, ambiguity: List<String>): Int {
        var value = if (intent == TaskIntent.UNKNOWN) 25 else 70
        if (operation.kind != OperationKind.NONE) value += 15
        if (paths.isNotEmpty()) value += 10
        if (symbols.isNotEmpty()) value += 5
        value -= ambiguity.size * 20
        return value.coerceIn(0, 99)
    }

    private fun pathTokens(request: String): List<String> = Regex(
        "(?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+|[A-Za-z0-9_.-]+\\.(?:kt|kts|java|py|js|ts|tsx|jsx|json|xml|md|toml|yaml|yml)",
        RegexOption.IGNORE_CASE
    ).findAll(request).map { it.value }.filter { it != "100%" }.toList()

    private fun symbolTokens(request: String): List<String> = Regex("(?:symbol|class|function|method|fun|def)\\s+[`]?([A-Za-z_][A-Za-z0-9_]*)[`]?", RegexOption.IGNORE_CASE)
        .findAll(request).map { it.groupValues[1] }.toList()

    private fun matches(value: String, pattern: String): Boolean = Regex("\\b(?:$pattern)\\b", RegexOption.IGNORE_CASE).containsMatchIn(value)
}
