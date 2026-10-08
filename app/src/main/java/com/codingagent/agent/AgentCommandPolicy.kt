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
    private val shellGlobCharacters = Regex("""[*?\[\]]""")
    private val forbiddenPathTokens = Regex("""(^|/|\\)\.\.(?:/|\\|$)""")

    fun rejectionReason(raw: String): String? = rejectionReason(raw, null)

    fun rejectionReason(raw: String, projectRoot: java.io.File?): String? {
        val command = raw.trim()
        if (command.isBlank()) return "Command is empty"
        if (command.length > 1_000) return "Command is too long"
        if (shellMetacharacters.containsMatchIn(command)) {
            return "Shell chaining, redirection, substitution, quotes, and control characters are not allowed for model commands"
        }
        if (shellGlobCharacters.containsMatchIn(command)) {
            return "Shell glob expansion is not allowed for model commands; specify explicit project-relative paths"
        }

        val tokens = command.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.any { it.startsWith("/") || it.startsWith("~") || forbiddenPathTokens.containsMatchIn(it) }) {
            return "Model commands may use only project-relative paths"
        }

        val executable = tokens.first().removePrefix("./")
        if (projectRoot != null) {
            validateFilesystemOperands(executable, tokens, projectRoot)?.let { return it }
        }
        return when (executable) {
            "gradlew", "gradlew.bat", "gradle" -> validateGradle(tokens)
            "git" -> validateGit(tokens)
            "find" -> validateFind(tokens)
            "cat", "head", "tail", "wc", "file", "grep", "rg", "ls", "pwd", "printf" -> null
            else -> "Model command '$executable' is not permitted; use project inspection tools or a standard verification command"
        }
    }

    private fun validateFind(tokens: List<String>): String? {
        val forbidden = setOf("-exec", "-execdir", "-delete", "-ok", "-okdir", "-L", "--follow", "--dereference")
        return if (tokens.any { it.lowercase() in forbidden }) {
            "find execution actions are not allowed for model commands"
        } else null
    }

    private fun validateFilesystemOperands(executable: String, tokens: List<String>, root: java.io.File): String? {
        val operands = when (executable) {
            "cat", "head", "tail", "wc", "file", "ls" -> tokens.drop(1).filterNot { it.startsWith("-") }
            "grep", "rg" -> tokens.drop(1).filterNot { it.startsWith("-") }
            "find" -> tokens.drop(1).filterNot { it.startsWith("-") }.take(1)
            else -> emptyList()
        }
        if (executable == "rg" && tokens.any { it == "--pre" || it.startsWith("--pre=") }) {
            return "ripgrep preprocessors are not allowed for model commands"
        }
        if (executable == "grep" && tokens.any { it == "-R" || it == "--dereference-recursive" }) {
            return "grep recursive symlink following is not allowed for model commands"
        }
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { return "Project root could not be resolved safely" }
        for (operand in operands) {
            if (operand.isBlank()) continue
            val candidate = root.resolve(operand)
            val canonical = runCatching { candidate.canonicalFile }.getOrElse {
                return "Model commands may not access filesystem paths that cannot be resolved safely"
            }
            if (!canonical.toPath().startsWith(canonicalRoot.toPath())) {
                return "Model commands may not follow a symlink or filesystem path outside the project"
            }
        }
        return null
    }

    private fun validateGit(tokens: List<String>): String? {
        val subcommand = tokens.getOrNull(1)?.removePrefix("-") ?: return "git requires a read-only subcommand"
        val allowed = setOf("status", "diff", "log", "branch", "rev-parse", "ls-files", "show", "grep")
        if (subcommand !in allowed) {
            return "git '$subcommand' is not permitted for model commands"
        }
        val executionOrEscapeOptions = setOf(
            "--ext-diff",
            "--textconv",
            "--no-index",
            "--open-files-in-pager"
        )
        if (tokens.any { token ->
                token in executionOrEscapeOptions ||
                    token.startsWith("--open-files-in-pager=")
            }) {
            return "External diff tools, textconv filters, pager commands, and no-index filesystem access are not allowed for model commands"
        }
        if (tokens.any { it == "-o" || it == "--output" || it.startsWith("--output=") }) {
            return "Writing git output to files is not allowed for model commands"
        }
        if (subcommand == "branch") {
            return validateGitBranch(tokens)
        }
        return null
    }

    private fun validateGitBranch(tokens: List<String>): String? {
        val args = tokens.drop(2)
        if (args.isEmpty()) return null

        val forbidden = setOf(
            "-d", "--delete", "-D",
            "-m", "--move", "-M",
            "-c", "--copy", "-C",
            "-f", "--force",
            "-u", "--set-upstream-to", "--unset-upstream",
            "--track", "--no-track",
            "--edit-description", "--create-reflog",
            "--delete-merged"
        )
        if (args.any { token ->
                token in forbidden ||
                    token.startsWith("--set-upstream-to=") ||
                    token.startsWith("--delete-merged=")
            }) {
            return "git branch mutations are not permitted for model commands"
        }

        val readModes = setOf(
            "-l", "--list", "--show-current",
            "-r", "--remotes", "-a", "--all",
            "--merged", "--no-merged", "--contains", "--no-contains",
            "--points-at", "--format",
            "--sort", "--column", "--no-column",
            "-v", "-vv", "--verbose",
            "--abbrev", "--no-abbrev",
            "--color", "--no-color", "--omit-empty",
            "--ignore-case", "--forked"
        )
        val hasReadMode = args.any { token ->
            token in readModes ||
                token.startsWith("--format=") ||
                token.startsWith("--sort=") ||
                token.startsWith("--column=") ||
                token.startsWith("--abbrev=") ||
                token.startsWith("--color=")
        }
        if (!hasReadMode) {
            return "git branch creation and other mutating forms are not permitted for model commands"
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

        val safeTasks = setOf(
            "build",
            "assemble",
            "check",
            "test",
            "tasks",
            "projects",
            "dependencies",
            "properties",
            ":app:build",
            ":app:assemble",
            ":app:assembleDebug",
            ":app:assembleRelease",
            ":app:check",
            ":app:test",
            ":app:testDebugUnitTest",
            ":app:testReleaseUnitTest",
            ":app:lint",
            ":app:lintDebug",
            ":app:lintRelease",
            ":app:compileDebugKotlin",
            ":app:compileReleaseKotlin"
        )
        if (taskTokens.any { it !in safeTasks }) {
            return "Only explicitly allowlisted build, test, lint, compile, verification, and Gradle inspection tasks are allowed"
        }
        return null
    }
}
