package com.codingagent.core

/**
 * Progress for optional on-device model fetch UI.
 * Remote-only builds leave this unused (null); kept so the status bar can compile without Nexa.
 */
data class ModelDownloadProgress(
    val phase: String = "",
    val percent: Int = 0,
    val detail: String = ""
)
