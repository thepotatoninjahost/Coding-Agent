package com.codingagent.agent

import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.VerificationIssue
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Resolve a named project file and package local evidence for it.
 */
object LocalFileEvidence {
    data class Report(
        val path: String,
        val content: String,
        val policyIssues: List<VerificationIssue>,
        val structureNotes: List<String>,
        val report: VerificationReport
    ) {
        fun asUserText(includePolicy: Boolean, includeStructure: Boolean): String = buildString {
            append("File: $path\n")
            append("Size: ${content.length} chars, ${content.lines().size} lines\n")
            if (includePolicy) {
                append("\nPolicy scan (unfinished-work markers — not compiler errors):\n")
                if (policyIssues.isEmpty()) {
                    append("- No TODO / FIXME / stub markers in this file.\n")
                } else {
                    policyIssues.forEach { issue ->
                        append("- line ${issue.line}: ${issue.message}\n")
                    }
                }
            }
            if (includeStructure) {
                append("\nStructure heuristics (not a compiler):\n")
                if (structureNotes.isEmpty()) {
                    append("- No obvious brace/paren imbalance detected.\n")
                } else {
                    structureNotes.forEach { append("- $it\n") }
                }
            }
        }
    }

    fun resolve(nameOrPath: String, files: ProjectFileService, workspace: ProjectWorkspace): String? {
        val normalized = nameOrPath.trim().trimStart('/').replace('\\', '/')
        if (normalized.isBlank()) return null
        runCatching {
            files.readChunk(normalized, 0, 1)
            return normalized
        }
        val base = java.io.File(normalized).name
        val hits = workspace.sourcePaths().filter {
            java.io.File(it).name.equals(base, ignoreCase = true)
        }
        return when {
            hits.isEmpty() -> null
            hits.size == 1 -> hits[0]
            else -> hits.minByOrNull { it.length }
        }
    }

    fun readComplete(path: String, files: ProjectFileService): String {
        val content = StringBuilder()
        var offset = 0
        while (true) {
            val part = files.readChunk(path, offset, 12_000)
            content.append(part.content)
            if (!part.hasMore) return content.toString()
            check(part.nextCharacter > offset) { "File reader made no progress at character $offset" }
            offset = part.nextCharacter
        }
    }

    fun report(
        nameOrPath: String,
        files: ProjectFileService,
        workspace: ProjectWorkspace
    ): Report? {
        val resolved = resolve(nameOrPath, files, workspace) ?: return null
        val content = runCatching { readComplete(resolved, files) }.getOrNull() ?: return null
        val all = workspace.verify().issues
        val policy = all.filter { issue ->
            val ip = issue.path.replace('\\', '/')
            ip == resolved || ip.endsWith("/$resolved") ||
                java.io.File(ip).name.equals(java.io.File(resolved).name, ignoreCase = true)
        }
        return Report(
            path = resolved,
            content = content,
            policyIssues = policy,
            structureNotes = StructureScan.notes(content),
            report = VerificationReport(policy.isEmpty(), policy)
        )
    }
}
