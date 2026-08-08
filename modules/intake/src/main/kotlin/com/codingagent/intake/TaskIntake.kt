package com.codingagent.intake

import com.codingagent.domain.OperationKind
import com.codingagent.domain.TaskOperation

enum class TaskIntent { INSPECT, CHANGE, CREATE, REFACTOR, DEBUG, TEST, EXPLAIN, UNKNOWN }

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

data class TaskIntake(
    val originalRequest: String,
    val goal: String,
    val intent: TaskIntent,
    val operation: TaskOperation,
    val verificationCommands: List<List<String>>,
    val confidence: Int,
    val executionReady: Boolean,
    val clarificationQuestion: String?,
    val summary: String,
    val contract: GoalContract
)

class TaskIntakeParser(private val projectRoot: java.io.File) {
    private val interpreter = GoalInterpreter(projectRoot)

    fun parse(request: String): TaskIntake {
        val normalized = request.trim()
        require(normalized.isNotEmpty()) { "A coding request is required" }
        val operation = TaskOperationParser.parse(normalized)
        val contract = interpreter.interpret(normalized, operation)
        val question = if (contract.ready) null else contract.ambiguity.joinToString("; ").ifBlank { "Clarify the intended outcome before execution." }
        return TaskIntake(
            originalRequest = normalized,
            goal = contract.goal,
            intent = contract.intent,
            operation = operation,
            verificationCommands = VerificationCommandDetector.detect(projectRoot),
            confidence = contract.confidence,
            executionReady = contract.ready,
            clarificationQuestion = question,
            summary = "${contract.intent.name.lowercase()} task: ${contract.goal}",
            contract = contract
        )
    }
}

private object TaskOperationParser {
    fun parse(request: String): TaskOperation {
        Regex("(?is)^\\s*replace\\s+(.+?)\\s+with\\s+(.+?)\\s+in\\s+([A-Za-z0-9_./-]+)\\s*$").matchEntire(request)?.let {
            return TaskOperation(OperationKind.REPLACE, it.groupValues[3], it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        Regex("(?is)^\\s*append\\s+(.+?)\\s+to\\s+([A-Za-z0-9_./-]+)\\s*$").matchEntire(request)?.let {
            return TaskOperation(OperationKind.APPEND, it.groupValues[2], text = it.groupValues[1].trimEnd())
        }
        Regex("(?is)^\\s*remove\\s+(.+?)\\s+from\\s+([A-Za-z0-9_./-]+)\\s*$").matchEntire(request)?.let {
            return TaskOperation(OperationKind.REMOVE, it.groupValues[2], oldText = it.groupValues[1].trim())
        }
        Regex("(?is)^\\s*create\\s+(?:file\\s+)?([A-Za-z0-9_./-]+)\\s+with\\s+(.+)\\s*$").matchEntire(request)?.let {
            return TaskOperation(OperationKind.CREATE_FILE, it.groupValues[1], text = it.groupValues[2])
        }
        return TaskOperation()
    }
}

private object VerificationCommandDetector {
    fun detect(root: java.io.File): List<List<String>> = when {
        root.resolve("gradlew").isFile -> listOf(listOf("sh", "-c", "./gradlew test --no-daemon"))
        root.resolve("package.json").isFile -> listOf(listOf("sh", "-c", "npm test --if-present"))
        root.resolve("pyproject.toml").isFile || root.resolve("pytest.ini").isFile -> listOf(listOf("python", "-m", "pytest"))
        root.resolve("Makefile").isFile -> listOf(listOf("make", "test"))
        else -> emptyList()
    }
}
