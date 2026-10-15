package com.codingagent.agent

/** Complete saved owner context; older records can be retrieved in pages when needed. */
data class AgentOwnerContext(
    val history: List<ChatMessage> = emptyList(),
    val memories: List<UserMemory> = emptyList()
)
