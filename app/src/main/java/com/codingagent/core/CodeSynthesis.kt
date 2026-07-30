package com.codingagent.core

import java.io.File

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

        if (intake.intent == TaskIntent.CREATE && intake.contract.targetPaths.size == 1) {
            val path = intake.contract.targetPaths.single()
            if (!isSafePath(path)) return SynthesisResult.NeedsInput("Choose a project-relative target file.")
            if (root.resolve(path).exists()) return SynthesisResult.NeedsInput("$path already exists. Specify whether to replace it or edit it.")
            val content = generateFile(path, intake.contract.goal)
            return SynthesisResult.Ready(
                SynthesisProposal(
                    goal = intake.contract.goal,
                    operations = listOf(TaskOperation(OperationKind.CREATE_FILE, path = path, text = content)),
                    rationale = "Generated a compilable starter for ${language(path)} from the interpreted goal.",
                    knowledgeUsed = evidence
                )
            )
        }

        return SynthesisResult.NeedsInput("Specify the exact file operation, target file, or requested code shape.")
    }

    private fun generateFile(path: String, goal: String): String {
        val name = File(path).nameWithoutExtension.replace(Regex("[^A-Za-z0-9_]"), "_").ifBlank { "GeneratedCode" }
        return when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "class ${name.toClassName()} {\n    fun run(): String = \"${escape(goal.take(120))}\"\n}\n"
            "java" -> "public class ${name.toClassName()} {\n    public String run() {\n        return \"${escape(goal.take(120))}\";\n    }\n}\n"
            "py" -> "def main():\n    return ${goal.take(120).quotedPython()}\n\n\nif __name__ == \"__main__\":\n    main()\n"
            "js", "mjs", "cjs" -> "export function run() {\n  return \"${escape(goal.take(120))}\";\n}\n"
            "ts", "tsx" -> "export function run(): string {\n  return \"${escape(goal.take(120))}\";\n}\n"
            "json" -> "{\n  \"goal\": \"${escape(goal.take(120))}\"\n}\n"
            "md" -> "# ${goal.replace(Regex("\\s+"), " ").trim().take(120)}\n\n"
            else -> "${goal.trim()}\n"
        }
    }

    private fun language(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "Kotlin"
        "java" -> "Java"
        "py" -> "Python"
        "js", "mjs", "cjs" -> "JavaScript"
        "ts", "tsx" -> "TypeScript"
        "json" -> "JSON"
        "md" -> "Markdown"
        else -> "text"
    }

    private fun isSafePath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') && !path.contains("..") && !path.contains('\\')

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private fun String.toClassName(): String = split(Regex("[^A-Za-z0-9]+"))
        .filter { it.isNotBlank() }
        .joinToString("") { it.replaceFirstChar(Char::uppercase) }
        .ifBlank { "GeneratedCode" }

    private fun String.quotedPython(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")}\""
}
