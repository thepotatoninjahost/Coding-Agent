package com.codingagent.model

/**
 * ONE JOB: Choose how many tokens a helper may write back.
 * The number comes from the model list when we have it. Otherwise it is 4096.
 */
object OutputBudget {
    fun forModel(modelId: String): Int = ModelCaps.maxOutput(modelId, writingFile = false)

    fun forRequest(modelId: String, writingFile: Boolean, expanded: Boolean = false, promptTokens: Int = 0): Int =
        ModelCaps.maxOutput(modelId, writingFile, promptTokens, expanded)

    fun writesFile(toolNames: Collection<String>): Boolean =
        toolNames.any { it == "create_file" || it == "replace_text" || it == "edit" || it == "append_chunk" }
}
