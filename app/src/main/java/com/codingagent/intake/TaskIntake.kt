package com.codingagent.intake

import java.io.File

/**
 * ONE JOB: Free text → typed intake (intent, targets, operations).
 */
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
    val contract: GoalContract,
    val verificationNote: String? = null
)

class TaskIntakeParser(private val root: File) {
    private val interpreter = GoalInterpreter(root)

    fun parse(request: String): TaskIntake {
        val normalized = request.trim()
        require(normalized.isNotEmpty()) { "A coding request is required" }
        val operation = parseOperation(normalized)
        val contract = interpreter.interpret(normalized, operation)
        val verification = detectChecks(normalized)
        val ready = contract.ready
        val question = if (ready) null else clarification(contract, operation)
        return TaskIntake(
            originalRequest = normalized,
            goal = contract.goal,
            intent = contract.intent,
            operation = operation,
            verificationCommands = verification.commands,
            confidence = contract.confidence,
            executionReady = ready,
            clarificationQuestion = question,
            summary = "${contract.intent.name.lowercase()} task: ${contract.goal}",
            contract = contract,
            verificationNote = verification.note
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
    private data class VerificationPlan(
        val commands: List<List<String>>,
        val note: String? = null
    )

    private fun detectChecks(request: String): VerificationPlan {
        if (!explicitlyRequestsVerification(request)) return VerificationPlan(emptyList())

        // Imported build/test configuration is executable project-controlled code.
        // The autonomous path must not run wrappers, package scripts, pytest, or Makefiles.
        // The owner can review those files and run checks explicitly in the owner-controlled terminal.
        return VerificationPlan(
            commands = emptyList(),
            note = "Requested project build/test checks were not run automatically because imported wrappers, build files, package scripts, and test suites can execute arbitrary code. Review the project first, then run checks explicitly in the owner-controlled Terminal."
        )
    }

    private fun explicitlyRequestsVerification(request: String): Boolean {
        val normalized = request.lowercase()
        val runVerb = Regex("""\b(run|execute|perform|rerun|re-run)\b""").containsMatchIn(normalized)
        val checkTarget = Regex("""\b(tests?|build|compile|lint|checks?|pytest|gradlew?|npm|make)\b""")
            .containsMatchIn(normalized)
        val directVerificationVerb = Regex("""\b(test|verify|build|compile|lint|check)\b\s+(the|this|my|all|project|app|application|module|code|changes|it)\b""")
            .containsMatchIn(normalized)
        return (runVerb && checkTarget) || directVerificationVerb
    }
}

