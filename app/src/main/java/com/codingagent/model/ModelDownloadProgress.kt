package com.codingagent.model

/**
 * ONE JOB: Progress state for optional on-device model fetch UI.
 */
data class ModelDownloadProgress(
    val phase: String = "",
    val percent: Int = 0,
    val detail: String = ""
)
