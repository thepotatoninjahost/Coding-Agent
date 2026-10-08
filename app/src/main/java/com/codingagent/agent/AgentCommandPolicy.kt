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
            "git" -> validateGit(tokens, projectRoot)
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
                    (token.startsWith("-") && !token.startsWith("--") &&
                        token.drop(1).any { it == 'r' || it == 'R' })
            }) {
            return "Recursive grep can expose private agent metadata and is not allowed for model commands; use explicit project-relative file paths"
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
            val relative = canonicalRoot.toPath().relativize(canonical.toPath())
                .toString().replace('\\', '/')
            val first = relative.substringBefore('/')
            if (first.equals(".coding-agent", ignoreCase = true) || first.equals(".git", ignoreCase = true)) {
                return "Private agent and Git metadata are not accessible to model commands"
            }
        }
        return null
    }

    private fun gitConfigUsesExecutableHelpers(root: java.io.File): Boolean {
        val gitDirectory = root.resolve(".git")
        if (!gitDirectory.exists()) return false
        if (java.nio.file.Files.isSymbolicLink(gitDirectory.toPath()) || !gitDirectory.isDirectory) return true
        val config = gitDirectory.resolve("config")
        if (!config.exists()) return false
        if (java.nio.file.Files.isSymbolicLink(config.toPath())) return true
        val canonicalRoot = runCatching { root.canonicalFile.toPath() }.getOrElse { return true }
        val canonicalConfig = runCatching { config.canonicalFile.toPath() }.getOrElse { return true }
        if (!canonicalConfig.startsWith(canonicalRoot)) return true
        val content = runCatching { config.readText() }.getOrElse { return true }
        val unsafeConfig = Regex(
            """(?im)^\s*(?:\[\s*(?:include(?:if)?|pager)\b|\[\s*diff\s+"[^"]+"|(?:fsmonitor|external|textconv|clean|smudge|process|pager|path|sshcommand|hookspath|worktree|worktreeconfig)\s*=)"""
        )
        return unsafeConfig.containsMatchIn(content)
    }

    private fun validateGit(tokens: List<String>, projectRoot: java.io.File?): String? {
        if (projectRoot != null && gitConfigUsesExecutableHelpers(projectRoot)) {
            return "Git commands are disabled because .git/config enables external helpers or includes; review the repository configuration in the owner-controlled Terminal"
        }
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


}
