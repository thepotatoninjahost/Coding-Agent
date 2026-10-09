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
    private val shellGlobCharacters = Regex("""[*?{}()!+@\[\]]""")
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

        val rawExecutable = tokens.first()
        if (rawExecutable.contains('/') || rawExecutable.contains('\\')) {
            return "Project-local or path-qualified executables are not allowed for model commands"
        }
        val executable = rawExecutable
        if (projectRoot != null) {
            validateFilesystemOperands(executable, tokens, projectRoot)?.let { return it }
        }
        return when (executable) {
            "gradlew", "gradlew.bat", "gradle" ->
                "Build tools execute project-controlled scripts and are not permitted through the autonomous command channel; use the owner-controlled Terminal after reviewing the project scripts"
            "git" -> "Git commands are disabled in the autonomous command channel because Git can execute configured helpers from repository, global, or system configuration"
            "find" -> "Find is disabled in the autonomous command channel because recursive traversal can expose private metadata; use the project indexer or file-list tools"
            "ls" -> validateLs(tokens)
            "cat", "head", "tail", "wc", "file", "grep", "rg", "pwd", "printf" -> null
            else -> "Model command '$executable' is not permitted; use project inspection tools or a standard verification command"
        }
    }

    private fun validateLs(tokens: List<String>): String? {
        val exposesPrivateMetadata = tokens.drop(1).any { token ->
            token == "--all" || token == "--almost-all" || token == "--recursive" ||
                token == "--dereference" || token == "--dereference-command-line" ||
                token == "--dereference-command-line-symlink-to-dir" ||
                (token.startsWith("-") && !token.startsWith("--") &&
                    token.drop(1).any { it == 'a' || it == 'A' || it == 'f' || it == 'R' || it == 'L' || it == 'H' })
        }
        return if (exposesPrivateMetadata) {
            "ls options that reveal hidden metadata, recurse into directories, or dereference symlinks are not allowed; use the project file-list tools"
        } else null
    }

    private fun validateFilesystemOperands(executable: String, tokens: List<String>, root: java.io.File): String? {
        val operands = when (executable) {
            "cat", "head", "tail", "wc", "file", "ls" -> tokens.drop(1).filterNot { it.startsWith("-") }
            "grep", "rg" -> tokens.drop(1).filterNot { it.startsWith("-") }
            else -> emptyList()
        }
        if (executable == "rg" && tokens.any { token ->
                token == "--pre" || token.startsWith("--pre=") ||
                    token == "--hidden" || token == "--follow" || token == "-L" ||
                    token.startsWith("--no-ignore") ||
                    Regex("""^-u{1,3}$""").matches(token) ||
                    (token.startsWith("-") && !token.startsWith("--") && token.drop(1).contains('u'))
            }) {
            return "Ripgrep preprocessors, hidden agent metadata, and symlink-following or ignore-bypass options are not allowed for model commands"
        }
        if (executable == "grep" && tokens.any { token ->
                token == "--recursive" || token == "--dereference-recursive" ||
                    token == "--directories" || token.startsWith("--directories=") ||
                    token == "-d" ||
                    (token.startsWith("-") && !token.startsWith("--") &&
                        token.drop(1).any { it == 'd' || it == 'r' || it == 'R' })
            }) {
            return "Recursive grep can expose private agent metadata and is not allowed for model commands; use explicit project-relative file paths"
        }
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { return "Project root could not be resolved safely" }
        for (operand in operands) {
            if (operand.isBlank()) continue
            val requestedParts = operand.replace('\\', '/').split('/')
            if (requestedParts.any { it.equals(".git", ignoreCase = true) } ||
                requestedParts.any { it.equals(".coding-agent", ignoreCase = true) }
            ) {
                return "Private agent and Git metadata are not accessible to model commands"
            }
            val candidate = root.resolve(operand)
            val canonical = runCatching { candidate.canonicalFile }.getOrElse {
                return "Model commands may not access filesystem paths that cannot be resolved safely"
            }
            if (!canonical.toPath().startsWith(canonicalRoot.toPath())) {
                return "Model commands may not follow a symlink or filesystem path outside the project"
            }
            val relative = canonicalRoot.toPath().relativize(canonical.toPath())
                .toString().replace('\\', '/')
            val parts = relative.split('/').filter { it.isNotEmpty() }
            if (parts.any { it.equals(".git", ignoreCase = true) } ||
                parts.any { it.equals(".coding-agent", ignoreCase = true) }
            ) {
                return "Private agent and Git metadata are not accessible to model commands"
            }
        }
        return null
    }
}
