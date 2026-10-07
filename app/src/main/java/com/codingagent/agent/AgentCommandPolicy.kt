package com.codingagent.agent

/**
 * ONE JOB: Limit model-invoked shell commands to read-only inspection and ordinary
 * project verification. The interactive owner terminal remains unrestricted.
 *
 * The model is remote/untrusted input. It must not be able to turn run_command into
 * arbitrary shell execution, command chaining, file deletion, network exfiltration,
 * or global Gradle configuration changes.
 */
object AgentCommandPolicy {
    private val shellMetacharacters = Regex("""[;&|><`\$'"\n\r]""")
    private val forbiddenPathTokens = Regex("""(^|/|\\)\.\.(?:/|\\|$)""")

    fun rejectionReason(raw: String): String? {
        val command = raw.trim()
        if (command.isBlank()) return "Command is empty"
        if (command.length > 1_000) return "Command is too long"
        if (shellMetacharacters.containsMatchIn(command)) {
            return "Shell chaining, redirection, substitution, quotes, and control characters are not allowed for model commands"
        }

        val tokens = command.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.any { it.startsWith("/") || it.startsWith("~") || forbiddenPathTokens.containsMatchIn(it) }) {
            return "Model commands may use only project-relative paths"
        }

        val executable = tokens.first().removePrefix("./")
        return when (executable) {
            "gradlew", "gradlew.bat", "gradle" -> validateGradle(tokens)
            "git" -> validateGit(tokens)
            "find" -> validateFind(tokens)
            "sed" -> validateSed(tokens)
            "cat", "head", "tail", "wc", "file", "grep", "rg", "ls", "pwd" -> null
            else -> "Model command '$executable' is not permitted; use project inspection tools or a standard verification command"
        }
    }

    private fun validateFind(tokens: List<String>): String? {
        val forbidden = setOf("-exec", "-execdir", "-delete", "-ok", "-okdir")
        return if (tokens.any { it.lowercase() in forbidden }) {
            "find execution actions are not allowed for model commands"
        } else null
    }

    private fun validateSed(tokens: List<String>): String? {
        return if (tokens.drop(1).any { it == "-i" || it.startsWith("-i") }) {
            "sed in-place writes are not allowed for model commands"
        } else null
    }

    private fun validateGit(tokens: List<String>): String? {
        val subcommand = tokens.getOrNull(1)?.removePrefix("-") ?: return "git requires a read-only subcommand"
        val allowed = setOf("status", "diff", "log", "branch", "rev-parse", "ls-files", "show", "grep")
        if (subcommand !in allowed) {
            return "git '$subcommand' is not permitted for model commands"
        }
        if (tokens.any { it == "-o" || it == "--output" || it.startsWith("--output=") }) {
            return "Writing git output to files is not allowed for model commands"
        }
        return null
    }

    private fun validateGradle(tokens: List<String>): String? {
        val dangerousOptions = setOf(
            "-I", "--init-script",
            "-p", "--project-dir",
            "-g", "--gradle-user-home",
            "-b", "--build-file",
            "--settings-file",
            "--include-build",
            "--scan",
            "--develocity-url",
            "--develocity-plugin-version"
        )
        if (tokens.any { token ->
                token in dangerousOptions ||
                    token.startsWith("-I=") ||
                    token.startsWith("--init-script=") ||
                    token.startsWith("-p=") ||
                    token.startsWith("--project-dir=") ||
                    token.startsWith("-g=") ||
                    token.startsWith("--gradle-user-home=") ||
                    token.startsWith("-b=") ||
                    token.startsWith("--build-file=") ||
                    token.startsWith("--settings-file=") ||
                    token.startsWith("--include-build=") ||
                    token.startsWith("-P") ||
                    token.startsWith("--project-prop") ||
                    token.startsWith("-D") ||
                    token.startsWith("--system-prop")
            }) {
            return "Global, external-project, initialization-script, system-property, project-property, and build-scan Gradle options are not allowed for model commands"
        }

        val taskTokens = tokens.drop(1).filterNot { it.startsWith("-") }
        if (taskTokens.isEmpty()) return null

        val safeTask = Regex(
            """^:?(?:[A-Za-z0-9_-]+:)*(?:build|assemble|check|test|lint|compile[A-Za-z0-9_-]*|test[A-Za-z0-9_-]*|lint[A-Za-z0-9_-]*|assemble[A-Za-z0-9_-]*|check[A-Za-z0-9_-]*|verify[A-Za-z0-9_-]*|tasks|projects|dependencies|properties)$"""
        )
        if (taskTokens.any { !safeTask.matches(it) }) {
            return "Only standard build, test, lint, compile, verification, and Gradle inspection tasks are allowed"
        }
        return null
    }
}
