package com.codingagent.core

import android.content.Context
import com.codingagent.agent.AgentAction
import com.codingagent.agent.AgentActionCategory
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Install the default coding live-module for a fresh app-private store.
 * Extracted out of LiveModules.kt (storage in LiveModuleStore.kt, execution in
 * LiveModuleRuntime.kt).
 */
class BuiltInModules(context: Context) {
    private val store = LiveModuleStore(context.filesDir)

    fun installDefault(): ModuleInstallResult = store.install(
        """
        {"kind":"coding","version":1,"steps":[
          {"op":"emit","value":"Live coding module active for: ${'$'}{input}"},
          {"op":"knowledge","value":"${'$'}{input}","argument":"4"},
          {"op":"project_search","value":"${'$'}{input}"},
          {"op":"verify"}
        ]}
        """.trimIndent(), "coding", 1,
        AgentAction("Install built-in coding module", AgentActionCategory.CODE_CHANGE, ownerVerified = true, approvalCount = 2),
        VerificationReport(true, emptyList())
    )
}
