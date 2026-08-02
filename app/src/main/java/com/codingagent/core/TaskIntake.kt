package com.codingagent.core

import java.io.File

enum class TaskIntent { INSPECT, CHANGE, CREATE, REFACTOR, DEBUG, TEST, EXPLAIN, UNKNOWN }
enum class OperationKind { NONE, REPLACE, APPEND, REMOVE, CREATE_FILE }

data class TaskOperation(
    val kind: OperationKind = OperationKind.NONE,
    val path: String? = null,
    val oldText: String? = null,
    val newText: String? = null,
    val text: String? = null
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

class TaskIntakeParser(private val root: File) {
    private val interpreter = GoalInterpreter(root)

    fun parse(request: String): TaskIntake {
        val normalized = request.trim()
        require(normalized.isNotEmpty()) { "A coding request is required" }
        val operation = parseOperation(normalized)
        val contract = interpreter.interpret(normalized, operation)
        val verification = detectChecks()
        // Plain English is enough. Exact replace/append syntax is optional;
        // the model inspects the project and proposes changes through tools.
        val ready = contract.ready && contract.intent != TaskIntent.UNKNOWN
        val question = if (ready) null else clarification(contract, operation)
        return TaskIntake(
            originalRequest = normalized,
            goal = contract.goal,
            intent = contract.intent,
            operation = operation,
            verificationCommands = verification,
            confidence = contract.confidence,
            executionReady = ready,
            clarificationQuestion = question,
            summary = "${contract.intent.name.lowercase()} task: ${contract.goal}",
            contract = contract
        )
    }

    private fun clarification(contract: GoalContract, operation: TaskOperation): String {
        if (contract.ambiguity.isNotEmpty()) return contract.ambiguity.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
        return "Clarify the intended outcome before execution."
    }

    private fun parseOperation(request: String): TaskOperation {
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

    private fun detectChecks(): List<List<String>> {
        return when {
            root.resolve("gradlew").isFile -> listOf(listOf("sh", "-c", "./gradlew test --no-daemon"))
            root.resolve("package.json").isFile -> listOf(listOf("sh", "-c", "npm test --if-present"))
            root.resolve("pyproject.toml").isFile || root.resolve("pytest.ini").isFile -> listOf(listOf("python", "-m", "pytest"))
            root.resolve("Makefile").isFile -> listOf(listOf("make", "test"))
            else -> emptyList()
        }
    }
}
