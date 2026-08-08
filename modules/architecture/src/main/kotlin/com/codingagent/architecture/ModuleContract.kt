package com.codingagent.architecture

data class ModuleContract(
    val name: String,
    val responsibility: String,
    val allowedDependencies: Set<String>,
    val forbiddenResponsibilities: Set<String> = emptySet()
)

object CodingAgentArchitecture {
    val modules = listOf(
        ModuleContract("domain", "Stable business types and pure task contracts", emptySet(), setOf("Android", "filesystem", "HTTP", "process execution", "Compose")),
        ModuleContract("workspace", "Project files, checksums, transactions, rollback, and verification", setOf("domain", "terminal"), setOf("model calls", "web research", "command execution", "Compose")),
        ModuleContract("research", "Internet search, source retrieval, and evidence extraction", setOf("domain"), setOf("code edits", "model inference", "terminal execution", "Compose")),
        ModuleContract("model", "Model request/response protocols, transport, and provider adapters", setOf("domain"), setOf("filesystem mutation", "web search", "UI state", "Compose")),
        ModuleContract("terminal", "Command execution, cancellation, and terminal history", setOf("domain"), setOf("code mutation", "model calls", "web search", "Compose")),
        ModuleContract("persistence", "Durable event and conversation storage", setOf("domain"), setOf("orchestration policy", "network", "UI")),
        ModuleContract("orchestration", "One coding workflow coordinating the capability modules", setOf("domain", "intake", "workspace", "research", "model", "terminal", "persistence", "knowledge", "policy"), setOf("low-level storage", "HTTP transport", "Compose")),
        ModuleContract("intake", "Pure request interpretation and verification-command detection", setOf("domain"), setOf("Android", "filesystem", "HTTP", "process execution", "Compose")),
        ModuleContract("knowledge", "Local document ingestion, indexing, and knowledge retrieval", setOf("domain"), setOf("model calls", "web research", "terminal execution", "Compose")),
        ModuleContract("policy", "Agent constitution, approval rules, and permission ledger", setOf("domain"), setOf("filesystem", "network", "Compose")),
        ModuleContract("live", "Live module and model evolution, staged promotion, and rollback", setOf("domain", "policy", "workspace", "knowledge"), setOf("Compose", "direct model inference", "unapproved file mutation")),
        ModuleContract("ui", "Android Compose screens and user interaction", setOf("orchestration", "domain", "intake", "knowledge", "policy", "live"), setOf("duplicate core business logic"))
    )

    fun validate(): List<String> = buildList {
        val names = modules.map { it.name }
        if (names.size != names.toSet().size) add("Module names must be unique")
        modules.forEach { module ->
            module.allowedDependencies.filterNot(names::contains).forEach { dependency ->
                add("${module.name} depends on undeclared module $dependency")
            }
            if (module.name == "ui" && modules.count { it.responsibility.contains("screens") } != 1) {
                add("Exactly one module owns presentation screens")
            }
            module.forbiddenResponsibilities.forEach { forbidden ->
                if (module.responsibility.contains(forbidden, ignoreCase = true)) {
                    add("${module.name} owns forbidden responsibility $forbidden")
                }
            }
        }
    }
}
