package com.codingagent.domain

enum class OperationKind { NONE, REPLACE, APPEND, REMOVE, CREATE_FILE }
enum class ChangeOperation { CREATE, REPLACE, APPEND, REMOVE }
data class TaskOperation(val kind: OperationKind = OperationKind.NONE, val path: String? = null, val oldText: String? = null, val newText: String? = null, val text: String? = null)
data class ChangeRecord(val path: String, val operation: ChangeOperation, val before: String?, val after: String?, val reason: String, val beforeChecksum: String, val afterChecksum: String)
data class ChangeSet(val id: String, val changes: List<ChangeRecord>, val createdAt: Long, val reason: String)
data class CommandResult(val command: String, val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)
data class ResearchHit(val title: String, val url: String, val excerpt: String)
data class ProjectFile(val path: String, val bytes: Long, val language: String, val imports: List<String>, val symbols: List<String>, val lineCount: Int, val checksum: String)
data class SearchHit(val path: String, val line: Int, val text: String)
data class VerificationIssue(val path: String, val line: Int, val message: String)
data class VerificationReport(val passed: Boolean, val issues: List<VerificationIssue>, val commands: List<CommandResult> = emptyList())
data class ProjectSummary(val files: List<ProjectFile>, val languages: Map<String, Int>, val symbols: Int, val imports: Int)
data class ChatRecord(val role: String, val content: String, val createdAt: Long)
