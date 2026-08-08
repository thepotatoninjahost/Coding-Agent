package com.codingagent.core

import com.codingagent.domain.SearchHit
import com.codingagent.knowledge.KnowledgeHit
import com.codingagent.knowledge.KnowledgeProvider
import com.codingagent.policy.AgentAction
import java.io.File

typealias ModuleInstallResult = com.codingagent.live.ModuleInstallResult
typealias ModuleExecution = com.codingagent.live.ModuleExecution
typealias ModulePatchResult = com.codingagent.live.ModulePatchResult
typealias LiveModule = com.codingagent.live.LiveModule
typealias ModuleStep = com.codingagent.live.ModuleStep
typealias ParsedModule = com.codingagent.live.ParsedModule
typealias LiveModuleStore = com.codingagent.live.LiveModuleStore

class LiveModuleRuntime(
    workspace: ProjectWorkspace,
    knowledge: KnowledgeProvider,
    store: LiveModuleStore
) {
    private val delegate = com.codingagent.live.LiveModuleRuntime(
        object : com.codingagent.live.LiveProjectPort {
            override fun projectRoot(): File = workspace.projectRoot()
            override fun verify() = workspace.verify()
            override fun search(query: String): List<SearchHit> = workspace.search(query)
            override fun runChecks(commands: List<List<String>>, timeoutSeconds: Long) = workspace.runChecks(commands, timeoutSeconds)
            override fun recordLesson(request: String, status: String, evidence: String) = workspace.recordLesson(request, status, evidence)
        },
        object : com.codingagent.live.LiveKnowledgePort {
            override fun search(query: String, limit: Int): List<KnowledgeHit> = knowledge.search(query, limit)
        },
        store
    )

    fun reload(): LiveModule? = delegate.reload()
    fun active(): LiveModule? = delegate.active()
    fun execute(input: String): ModuleExecution = delegate.execute(input)
    fun applyPatch(transform: (String) -> String, action: AgentAction, input: String = ""): ModulePatchResult = delegate.applyPatch(transform, action, input)
}
