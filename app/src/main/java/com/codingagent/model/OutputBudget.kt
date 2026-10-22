package com.codingagent.model

/**
 * ONE JOB: Choose how many tokens a helper may write back.
 * A small free helper fails the whole request when the reply allowance is left unset.
 */
object OutputBudget {
    fun forModel(modelId: String): Int {
        val id = modelId.lowercase()
        return when {
            "qwen3.8-27b" in id || "qwen3-27b" in id || "qwen/qwen3.8" in id -> 256
            "gpt-oss-20b" in id -> 512
            "gemma" in id -> 768
            else -> 1024
        }
    }
}
