package com.codingagent.intake

import java.io.File
import com.codingagent.agent.AgentKnowledge
import com.codingagent.workspace.KnowledgeHit

/**
 * ONE JOB: Turn intake into a deterministic synthesis proposal when ops are explicit.
 */
sealed class SynthesisResult {
    data class Ready(val proposal: SynthesisProposal) : SynthesisResult()
    data class NeedsInput(val question: String) : SynthesisResult()
}

data class SynthesisProposal(
    val goal: String,
    val operations: List<TaskOperation>,
    val rationale: String,
    val knowledgeUsed: List<KnowledgeHit>
)

class CodeSynthesisEngine(
    private val root: File,
    private val knowledge: AgentKnowledge
) {
    fun synthesize(intake: TaskIntake): SynthesisResult {
        val evidence = knowledge.search(intake.contract.goal, 6)
        val operation = intake.operation
        if (operation.kind != OperationKind.NONE) {
            return SynthesisResult.Ready(
                SynthesisProposal(
                    goal = intake.contract.goal,
                    operations = listOf(operation),
                    rationale = "Preserved the explicit operation from the task request.",
                    knowledgeUsed = evidence
                )
            )
        }

        if (intake.intent == TaskIntent.CREATE) {
            val path = intake.contract.targetPaths.singleOrNull() ?: defaultCreatePath(intake.contract.goal)
            if (!isSafePath(path)) return SynthesisResult.NeedsInput("Choose a project-relative target file.")
            if (root.resolve(path).exists()) {
                return SynthesisResult.NeedsInput("$path already exists. Specify whether to replace it or edit it.")
            }
            val content = generateFile(path, intake.contract.goal)
            return SynthesisResult.Ready(
                SynthesisProposal(
                    goal = intake.contract.goal,
                    operations = listOf(TaskOperation(OperationKind.CREATE_FILE, path = path, text = content)),
                    rationale = "Staged $path from the create request so the owner can review a real file, not README.md.",
                    knowledgeUsed = evidence
                )
            )
        }

        return SynthesisResult.NeedsInput("Specify the exact file operation, target file, or requested code shape.")
    }

    private fun defaultCreatePath(goal: String): String {
        val stop = setOf(
            "create", "make", "build", "write", "add", "new", "a", "an", "the",
            "file", "project", "app", "please", "just", "simple", "my"
        )
        val parts = goal.split(Regex("[^A-Za-z0-9]+"))
            .map { it.trim() }
            .filter { it.length >= 3 && it.lowercase() !in stop }
            .take(3)
        val className = parts.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
            .ifBlank { "AppCore" }
        return "src/$className.kt"
    }

    private fun generateFile(path: String, goal: String): String {
        val name = File(path).nameWithoutExtension.replace(Regex("[^A-Za-z0-9_]"), "_").ifBlank { "AppCore" }
        val className = name.toClassName()
        val lower = goal.lowercase()
        return when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> if ("agent" in lower || "autonomous" in lower) {
                agentKotlin(className, goal)
            } else {
                "class $className {\n    fun run(input: String): String = input\n}\n"
            }
            "java" -> "public class $className {\n    public String run(String input) {\n        return input;\n    }\n}\n"
            "py" -> "def run(request: str) -> str:\n    return request\n"
            "js", "mjs", "cjs" -> "export function run(input) {\n  return input;\n}\n"
            "ts", "tsx" -> "export function run(input: string): string {\n  return input;\n}\n"
            "json" -> "{\n  \"goal\": \"${escape(goal.take(120))}\"\n}\n"
            "md" -> "# ${goal.replace(Regex("\\s+"), " ").trim().take(120)}\n\n"
            else -> "${goal.trim()}\n"
        }
    }

    private fun agentKotlin(className: String, goal: String): String {
        val dollar = "${'$'}"
        return """
/**
 * Goal: ${escape(goal.take(200))}
 * This is a staged starting spine, not a README and not a hello-world demo.
 */
class $className(
    private val tools: (String, String) -> String = { toolName, toolArgs -> "unsupported: " + toolName + " " + toolArgs }
) {
    fun run(request: String): String {
        val plan = plan(request)
        val evidence = gather(plan)
        return decide(request, evidence)
    }

    private fun plan(request: String): List<String> =
        listOf("intake", "gather", "change", "verify").filter { step ->
            request.isNotBlank() || step == "intake"
        }

    private fun gather(plan: List<String>): String =
        tools("list_files", "{}") + "\\n" + plan.joinToString(",")

    private fun decide(request: String, evidence: String): String {
        if (evidence.isBlank()) return "Need project evidence before changing files."
        return tools("create_file", request.take(240))
    }
}
""".trimIndent() + "\n"
    }

    private fun isSafePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith('/') && !path.contains("..") && !path.contains('\\')

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private fun String.toClassName(): String = split(Regex("[^A-Za-z0-9]+"))
        .filter { it.isNotBlank() }
        .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        .ifBlank { "AppCore" }
}
