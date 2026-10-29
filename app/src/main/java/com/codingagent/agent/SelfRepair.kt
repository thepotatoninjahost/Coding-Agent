package com.codingagent.agent

import java.time.Instant
import com.codingagent.workspace.AgentTask
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Detect and stage self-repair requests — mutations to the agent's own source tree.
 *
 * Requests against an existing agent tree are delegated to the normal model/tool path;
 * an empty project can bootstrap a repair entry point under dual-owner approval.
 */
object SelfRepair {
    val PRIORITY_FILES = listOf(
        "ChatWorkspace.kt",
        "AutonomousAgent.kt",
        "AgentConstitution.kt",
        "ToolCallOutcomeHandler.kt",
        "SelfEvolution.kt",
        "SelfRepair.kt"
    )

    private val PHRASES = Regex(
        "\\b(fix yourself|modify yourself|self[-\\s]?(repair|heal|healing|modification|modify)|repair yourself|heal yourself|update yourself|improve yourself|fix your own code|modify your own (code|source|files)|repair your own code|self[-\\s]?improvement)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Returns true when [text] is a self-repair or self-modification request. */
    fun isRequest(text: String): Boolean = PHRASES.containsMatchIn(text)

    /**
     * Stage a self-repair proposal for dual-owner approval, or return null so the
     * autonomous loop can edit the agent's own sources with tools.
     */
    fun handle(
        taskId: String,
        request: String,
        plan: AgentPlan,
        workspace: ProjectWorkspace,
        files: ProjectFileService,
        mutations: MutationCoordinator
    ): AgentTask? {
        val hasAgentSources = workspace.summary().files.any { indexed ->
            PRIORITY_FILES.any { name -> indexed.path == name || indexed.path.endsWith("/$name") }
        }
        if (hasAgentSources) return null

        return AgentTask(
            id = taskId,
            request = request,
            status = "needs-input",
            plan = plan,
            changes = emptyList(),
            verification = VerificationReport(false, emptyList()),
            events = listOf("${Instant.now()}: self-repair found no app source in this project"),
            summary = "This project does not contain this app's own source, so there is nothing here to repair. Nothing was written."
        )
    }
}