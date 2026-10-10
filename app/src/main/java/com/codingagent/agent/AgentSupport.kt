package com.codingagent.agent

import java.io.File
import org.json.JSONObject
import com.codingagent.intake.GoalContract
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskIntake
import com.codingagent.intake.TaskIntakeParser
import com.codingagent.workspace.KnowledgeHit
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.AgentTask

/** ONE JOB: Shared support types — knowledge search, planning, journaling. */
interface AgentKnowledge {
    fun search(query: String, limit: Int = 8): List<KnowledgeHit>
}

data class AgentStep(val phase: String, val detail: String)
data class AgentPlan(
    val request: String,
    val steps: List<AgentStep>,
    val checks: List<List<String>>,
    val contract: GoalContract? = null
)

class AgentPlanner(private val workspace: ProjectWorkspace) {
    fun plan(request: String): AgentPlan = plan(TaskIntakeParser(workspace.projectRoot()).parse(request))

    fun plan(intake: TaskIntake): AgentPlan {
        val contract = intake.contract
        val steps = buildList {
            add(AgentStep("intake", "Interpret request as ${contract.intent.name.lowercase()} with ${contract.confidence}% confidence"))
            add(AgentStep("understand", "Index repository files, symbols, imports, and checksums"))
            add(AgentStep("research", "Search local coding knowledge and prior lessons for the goal"))
            if (contract.targetPaths.isNotEmpty()) add(AgentStep("target", "Resolve target paths: ${contract.targetPaths.joinToString()}"))
            if (contract.targetSymbols.isNotEmpty()) add(AgentStep("scope", "Resolve target symbols: ${contract.targetSymbols.joinToString()}"))
            add(AgentStep("constraints", if (contract.constraints.isEmpty()) "No explicit constraints" else contract.constraints.joinToString("; ")))
            if (intake.operation.kind != OperationKind.NONE) add(AgentStep("change", contract.goal))
            else add(AgentStep("inspect", contract.goal))
            add(AgentStep("acceptance", contract.acceptanceCriteria.joinToString("; ")))
            add(AgentStep("verify", if (intake.verificationCommands.isEmpty()) "Run static verification" else "Run static verification and detected project checks"))
            add(AgentStep("learn", "Persist the interpreted contract, outcome, and evidence for later tasks"))
        }
        return AgentPlan(intake.originalRequest, steps, intake.verificationCommands, contract)
    }
}

class AgentJournal(private val root: File) {
    private val file = root.resolve(".coding-agent/tasks.tsv")
    private val personalLog = root.resolve(".coding-agent/personal-log.jsonl")
    private val pendingModelText = mutableMapOf<String, StringBuilder>()

    @Synchronized
    fun record(task: AgentTask) {
        flushModelText(task.id)
        file.parentFile?.mkdirs()
        val line = listOf(task.id, task.status, task.request, task.changes.size, task.verification.passed, task.summary, task.events.joinToString(" | "))
            .joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') }
        file.appendText(line + "\n")
    }

    /** Persist each observable event for later owner inspection. */
    @Synchronized
    fun recordEvent(taskId: String, event: AutonomousAgentEvent) {
        if (event is AutonomousAgentEvent.ModelDelta) {
            pendingModelText.getOrPut(taskId) { StringBuilder() }.append(event.text)
            return
        }
        flushModelText(taskId)
        writeEvent(taskId, event.javaClass.simpleName, event.toString())
    }

    private fun flushModelText(taskId: String) {
        val text = pendingModelText.remove(taskId)?.toString() ?: return
        if (text.isNotEmpty()) writeEvent(taskId, "ModelStream", text)
    }

    private fun writeEvent(taskId: String, type: String, details: String) {
        personalLog.parentFile?.mkdirs()
        val entry = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("taskId", taskId)
            .put("type", type)
            .put("details", details)
        personalLog.appendText(entry.toString() + "\n")
    }

    fun recentEvents(limit: Int = 100): List<String> = recentLines(personalLog, limit)

    fun recent(limit: Int = 20): List<String> = recentLines(file, limit)

    private fun recentLines(source: File, limit: Int): List<String> {
        if (!source.isFile || limit <= 0) return emptyList()
        val result = ArrayDeque<String>(limit)
        java.io.RandomAccessFile(source, "r").use { raf ->
            var position = raf.length() - 1
            val bytes = java.io.ByteArrayOutputStream()
            while (position >= 0 && result.size < limit) {
                raf.seek(position--)
                val value = raf.read()
                if (value == '\n'.code) {
                    val line = bytes.toByteArray().reversedArray().toString(Charsets.UTF_8).trim()
                    if (line.isNotBlank()) {
                        result.addFirst(line)
                        if (result.size > limit) result.removeFirst()
                    }
                    bytes.reset()
                } else {
                    bytes.write(value)
                }
            }
            if (bytes.size() > 0 && result.size < limit) {
                val line = bytes.toByteArray().reversedArray().toString(Charsets.UTF_8).trim()
                if (line.isNotBlank()) result.addFirst(line)
            }
        }
        return result.asReversed()
    }
}
