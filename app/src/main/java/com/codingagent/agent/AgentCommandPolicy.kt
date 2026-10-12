package com.codingagent.agent

/**
 * ONE JOB: Limit model-invoked shell commands to read-only project inspection.
 * The interactive owner terminal remains unrestricted.
 *
 * The model is remote/untrusted input. Project-controlled build scripts and Git
 * configuration are not trusted execution boundaries.
 */
object AgentCommandPolicy {
    private val shellMetacharacters = Regex("""[;&|><`\$'"\n\r*?\[\]]""")
    private val forbiddenPathTokens = Regex("""(^|/|\\)\.\.(?:/|\\|$)""")

    fun rejectionReason(raw: String): String? = rejectionReason(raw, null)

    fun rejectionReason(raw: String, projectRoot: java.io.File?): String? {
        val command = raw.trim()
        if (command.isBlank()) return "Command is empty"
        if (command.length > 1_000) return "Command is too long"
        if (command.contains('\\')) {
            return "Backslash escaping is not allowed for model commands"
        }
        if (shellMetacharacters.containsMatchIn(command)) {
            return "Shell chaining, redirection, substitution, quotes, and control characters are not allowed for model commands"
        }

        val tokens = command.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.any { it.startsWith("/") || it.startsWith("~") || forbiddenPathTokens.containsMatchIn(it) }) {
            return "Model commands may use only project-relative paths"
        }

        val executable = tokens.first()
        if (executable.contains('/') || executable.contains('\\')) {
            return "Path-qualified executables are not allowed for model commands"
        }
        if (projectRoot != null) {
            validateFilesystemOperands(executable, tokens, projectRoot)?.let { return it }
        }
        return when (executable) {
            "gradlew", "gradlew.bat", "gradle" ->
                "Project-controlled build scripts are not executed through the autonomous command channel; review and run them in the owner-controlled Terminal"
            "git" ->
                "Git commands are disabled in the autonomous command channel because repository configuration can invoke external helpers"
            "find" ->
                "Recursive filesystem traversal is disabled in the autonomous command channel; use the project indexer or file-list tools"
            "file" ->
                "The file command is disabled in the autonomous command channel"
            "rg" -> validateRgOptions(tokens)
            "grep" -> validateGrepOptions(tokens)
            "ls" -> validateLs(tokens)
            "wc" -> validateWc(tokens)
            "cat", "head", "tail", "pwd", "printf" -> null
            else -> "Model command '$executable' is not permitted; use project inspection tools"
        }
    }

    private fun validateRgOptions(tokens: List<String>): String? {
        val restrictedLongOptions = listOf("--pre", "--hidden", "--follow", "--files-from", "--no-ignore", "--config-path")
        val restricted = tokens.any { token ->
            val option = token.takeWhile { it != '=' }
            (option.startsWith("--") && restrictedLongOptions.any { it.startsWith(option) || option.startsWith(it) }) ||
                token == "-L" || Regex("""^-u{1,3}$""").matches(token) ||
                (token.startsWith("-") && !token.startsWith("--") && token.drop(1).contains('u'))
        }
        return if (restricted) "Ripgrep options that expose hidden metadata, follow symlinks, or execute preprocessors are not allowed" else null
    }

    private fun validateGrepOptions(tokens: List<String>): String? {
        val restricted = tokens.any { token ->
            token == "--recursive" || token == "--dereference-recursive" ||
                token == "--directories" || token.startsWith("--directories=") ||
                token == "-d" ||
                (token.startsWith("-") && !token.startsWith("--") && token.drop(1).any { it == 'd' || it == 'r' || it == 'R' })
        }
        return if (restricted) "Recursive grep and directory traversal are not allowed in the autonomous command channel" else null
    }

    private fun validateLs(tokens: List<String>): String? {
        val restricted = tokens.drop(1).any { token ->
            token in setOf("--all", "--almost-all", "--recursive", "--dereference", "--dereference-command-line") ||
                (token.startsWith("-") && !token.startsWith("--") && token.drop(1).any { it in "aAfRLH" })
        }
        return if (restricted) "ls options that expose hidden metadata, recurse, or follow symlinks are not allowed" else null
    }

    private fun validateWc(tokens: List<String>): String? =
        if (tokens.drop(1).any { it == "--files0-from" || it.startsWith("--files0-from=") }) {
            "wc file-list options are not allowed; specify explicit project-relative files"
        } else null

    private fun validateFilesystemOperands(executable: String, tokens: List<String>, root: java.io.File): String? {
        val optionTerminator = tokens.indexOf("--")
        val operands = when (executable) {
            "cat", "head", "tail", "wc", "ls" ->
                if (optionTerminator >= 0) tokens.drop(optionTerminator + 1)
                else tokens.drop(1).filterNot { it.startsWith("-") }
            "grep", "rg" ->
                if (optionTerminator >= 0) tokens.drop(optionTerminator + 2)
                else tokens.drop(2).filterNot { it.startsWith("-") }
            else -> emptyList()
        }
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { return "Project root could not be resolved safely" }
        for (operand in operands) {
            if (operand.isBlank()) continue
            val requestedParts = operand.replace('\\', '/').split('/')
            if (requestedParts.any { it.equals(".git", true) || it.equals(".coding-agent", true) }) {
                return "Private Git and agent metadata are not accessible to model commands"
            }
            val candidate = root.resolve(operand)
            if (!candidate.exists()) continue
            val canonical = runCatching { candidate.canonicalFile }.getOrElse {
                return "Model commands may not access filesystem paths that cannot be resolved safely"
            }
            if (!canonical.toPath().startsWith(canonicalRoot.toPath())) {
                return "Model commands may not follow a symlink or filesystem path outside the project"
            }
            val relativeParts = canonicalRoot.toPath().relativize(canonical.toPath()).toString().replace('\\', '/').split('/')
            if (relativeParts.any { it.equals(".git", true) || it.equals(".coding-agent", true) }) {
                return "Private Git and agent metadata are not accessible to model commands"
            }
        }
        return null
    }
}
