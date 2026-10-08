package com.codingagent.intake

import java.io.File
import java.util.Properties

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

class TaskIntakeParser(
    private val root: File,
    private val executableAvailable: (String) -> Boolean = ::isExecutableAvailable,
    private val androidSdkAvailable: (File) -> Boolean = ::hasAndroidSdk
) {
    private val interpreter = GoalInterpreter(root)

    fun parse(request: String): TaskIntake {
        val normalized = request.trim()
        require(normalized.isNotEmpty()) { "A coding request is required" }
        val operation = parseOperation(normalized)
        val contract = interpreter.interpret(normalized, operation)
        val verification = detectChecks(normalized, contract.intent)
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

    private fun detectChecks(request: String, intent: TaskIntent): VerificationPlan {
        if (!explicitlyRequestsVerification(request, intent)) return VerificationPlan(emptyList())

        return when {
            root.resolve("gradlew").isFile && root.resolve("app/build.gradle.kts").isFile -> {
                if (!root.resolve("gradlew").canRead() ||
                    !executableAvailable("sh") ||
                    !executableAvailable("java")
                ) {
                    VerificationPlan(
                        emptyList(),
                        "Requested build/test checks were not run because the shell or Java runtime is unavailable."
                    )
                } else if (!androidSdkAvailable(root)) {
                    VerificationPlan(
                        emptyList(),
                        "Requested Android build/test checks were not run because a usable Android SDK was not found."
                    )
                } else {
                    VerificationPlan(
                        listOf(
                            listOf("sh", "./gradlew", ":app:compileDebugKotlin", ":app:testDebugUnitTest", "--no-daemon", "--console=plain"),
                            listOf("sh", "./gradlew", ":app:lintDebug", "--no-daemon", "--console=plain"),
                            listOf("sh", "./gradlew", ":app:assembleDebug", "--no-daemon", "--console=plain")
                        )
                    )
                }
            }
            root.resolve("gradlew").isFile -> {
                if (root.resolve("gradlew").canRead() && executableAvailable("sh") && executableAvailable("java")) {
                    VerificationPlan(listOf(listOf("sh", "./gradlew", "test", "--no-daemon")))
                } else {
                    VerificationPlan(
                        emptyList(),
                        "Requested Gradle checks were not run because the wrapper, shell, or Java runtime is unavailable."
                    )
                }
            }
            root.resolve("package.json").isFile ->
                if (executableAvailable("npm")) {
                    VerificationPlan(listOf(listOf("npm", "test", "--if-present")))
                } else {
                    VerificationPlan(emptyList(), "Requested Node.js tests were not run because npm is unavailable.")
                }
            root.resolve("pyproject.toml").isFile || root.resolve("pytest.ini").isFile -> {
                when {
                    executableAvailable("python") -> VerificationPlan(listOf(listOf("python", "-m", "pytest")))
                    executableAvailable("python3") -> VerificationPlan(listOf(listOf("python3", "-m", "pytest")))
                    else -> VerificationPlan(emptyList(), "Requested Python tests were not run because Python is unavailable.")
                }
            }
            root.resolve("Makefile").isFile ->
                if (executableAvailable("make")) {
                    VerificationPlan(listOf(listOf("make", "test")))
                } else {
                    VerificationPlan(emptyList(), "Requested Make checks were not run because make is unavailable.")
                }
            else -> VerificationPlan(emptyList(), "Requested verification was not run because no supported project test runner was detected.")
        }
    }

    private fun explicitlyRequestsVerification(request: String, intent: TaskIntent): Boolean {
        if (intent == TaskIntent.TEST) return true
        val normalized = request.lowercase()
        val runVerb = Regex("""\\b(run|execute|perform|rerun|re-run)\\b""").containsMatchIn(normalized)
        val checkTarget = Regex("""\\b(tests?|build|compile|lint|checks?|pytest|gradlew?|npm|make)\\b""")
            .containsMatchIn(normalized)
        return (runVerb && checkTarget) ||
            Regex("""\\b(build|compile|lint)\\b""").containsMatchIn(normalized)
    }
}

private fun isExecutableAvailable(name: String): Boolean {
    val path = System.getenv("PATH").orEmpty()
    return path.split(File.pathSeparator).filter { it.isNotBlank() }.any { directory ->
        val candidate = File(directory, name)
        candidate.isFile && candidate.canExecute()
    }
}

private fun hasAndroidSdk(root: File): Boolean {
    val candidates = linkedSetOf<String>()
    System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let(candidates::add)
    System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let(candidates::add)

    val localProperties = root.resolve("local.properties")
    if (localProperties.isFile && localProperties.canRead()) {
        runCatching {
            val properties = Properties()
            localProperties.inputStream().use { properties.load(it) }
            properties.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let { raw ->
                val path = File(raw)
                candidates += if (path.isAbsolute) path.path else root.resolve(path).path
            }
        }
    }

    return candidates.any { raw ->
        val sdk = File(raw)
        sdk.isDirectory && sdk.resolve("platforms").isDirectory && sdk.resolve("build-tools").isDirectory
    }
}
