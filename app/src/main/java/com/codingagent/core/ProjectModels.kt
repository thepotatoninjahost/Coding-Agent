package com.codingagent.core

/**
 * ONE JOB: Shared data models for indexed project files and workspace state.
 */
data class ProjectFile(
    val path: String,
    val bytes: Long,
    val language: String,
    val imports: List<String>,
    val symbols: List<String>,
    val lineCount: Int,
    val checksum: String
)

data class SearchHit(val path: String, val line: Int, val text: String)
data class VerificationIssue(val path: String, val line: Int, val message: String)
data class VerificationReport(
    val passed: Boolean,
    val issues: List<VerificationIssue>,
    val commands: List<CommandResult> = emptyList()
)
enum class ChangeOperation { CREATE, REPLACE, APPEND, REMOVE }

data class ChangeRecord(
    val path: String,
    val operation: ChangeOperation,
    val before: String?,
    val after: String?,
    val reason: String,
    val beforeChecksum: String,
    val afterChecksum: String
)

data class ChangeSet(
    val id: String,
    val changes: List<ChangeRecord>,
    val createdAt: Long,
    val reason: String
)
data class TaskRecord(
    val id: String,
    val request: String,
    val status: String,
    val createdAt: Long,
    val changes: Int,
    val verificationPassed: Boolean
)
data class Lesson(
    val pattern: String,
    val outcome: String,
    val evidence: String,
    val createdAt: Long
)
data class CommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean
)
data class TaskResult(
    val record: TaskRecord,
    val changes: List<ChangeRecord>,
    val verification: VerificationReport,
    val message: String
)
data class ProjectSummary(
    val files: List<ProjectFile>,
    val languages: Map<String, Int>,
    val symbols: Int,
    val imports: Int
)

data class KnowledgeChunk(
    val document: String,
    val section: String,
    val text: String,
    val terms: Set<String>
)

data class KnowledgeHit(
    val document: String,
    val section: String,
    val score: Int,
    val excerpt: String
)

data class AgentTask(
    val id: String,
    val request: String,
    val status: String,
    val plan: AgentPlan,
    val changes: List<ChangeRecord>,
    val verification: VerificationReport,
    val events: List<String>,
    val summary: String
)

data class TerminalEntry(
    val command: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean
)

data class EditorDocument(
    val path: String,
    val content: String,
    val checksum: String,
    val dirty: Boolean
)

data class ResearchHit(
    val title: String,
    val url: String,
    val excerpt: String
)

data class ResearchResult(
    val query: String,
    val hits: List<ResearchHit>,
    val error: String? = null
)

data class ResearchSource(
    val title: String,
    val url: String,
    val domain: String,
    val lane: String,
    val status: Int,
    val wordCount: Int,
    val content: String,
    val codeExamples: List<String> = emptyList(),
    val error: String? = null
)

data class ResearchSession(
    val id: String,
    val query: String,
    val createdAt: Long,
    val requestedSources: Int,
    val sources: List<ResearchSource>,
    val learnedChunks: Int,
    val errors: List<String> = emptyList(),
    val mode: String = "broad"
)

data class DeepResearchProgress(
    val stage: String,
    val completed: Int,
    val total: Int,
    val successful: Int,
    val failed: Int
)
