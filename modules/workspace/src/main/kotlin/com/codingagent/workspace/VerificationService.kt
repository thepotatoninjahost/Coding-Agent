package com.codingagent.workspace

import com.codingagent.domain.*
import com.codingagent.terminal.CommandExecutor
import java.io.File

class VerificationService(private val root: File, private val index: ProjectIndex = ProjectIndex()) {
    fun verify(): VerificationReport {
        val issues = mutableListOf<VerificationIssue>()
        index.index(root)
            .filterNot { it.path.contains("/test/") || it.path.endsWith("Test.kt") || it.path.endsWith("Tests.kt") }
            .forEach { metadata ->
                File(root, metadata.path).readLines().forEachIndexed { line, text ->
                    val marker = when {
                        Regex("\\bTODO\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "TODO marker remains"
                        Regex("\\bFIXME\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "FIXME marker remains"
                        text.contains("IMPLEMENT_ME") -> "IMPLEMENT_ME marker remains"
                        text.contains("throw NotImplementedError") -> "unimplemented code remains"
                        else -> null
                    }
                    if (marker != null) issues += VerificationIssue(metadata.path, line + 1, marker)
                }
            }
        return VerificationReport(issues.isEmpty(), issues)
    }

    fun runChecks(commands: List<List<String>>, timeoutSeconds: Long = 90): VerificationReport {
        val results = commands.map { CommandExecutor(root).run(it, timeoutSeconds) }
        val issues = results
            .filter { it.timedOut || it.exitCode != 0 }
            .map { VerificationIssue("<command>", 0, "${it.command}: exit=${it.exitCode} ${it.stderr.take(400)}") }
        val static = verify()
        return VerificationReport(static.passed && issues.isEmpty(), static.issues + issues, results)
    }

    fun verifyProposal(changeSet: ChangeSet): VerificationReport {
        val issues = changeSet.changes.flatMap { record ->
            record.after.orEmpty().lineSequence().mapIndexedNotNull { index, line ->
                when {
                    Regex("\\b(TODO|FIXME)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line) -> VerificationIssue(record.path, index + 1, "unfinished implementation marker remains")
                    line.contains("IMPLEMENT_ME") -> VerificationIssue(record.path, index + 1, "unfinished implementation marker remains")
                    else -> null
                }
            }.toList()
        }
        return VerificationReport(issues.isEmpty(), issues)
    }

}
