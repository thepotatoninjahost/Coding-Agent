package com.codingagent.core

import com.codingagent.domain.*

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
data class TaskResult(
    val record: TaskRecord,
    val changes: List<ChangeRecord>,
    val verification: VerificationReport,
    val message: String
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


data class EditorDocument(
    val path: String,
    val content: String,
    val checksum: String,
    val dirty: Boolean
)
