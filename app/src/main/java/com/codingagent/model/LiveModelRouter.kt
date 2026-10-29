package com.codingagent.model

/**
 * ONE JOB: Load and expose the active on-device model from LiveModelStore.
 */
class LiveModelRouter(private val store: LiveModelStore) {
    private var loaded: LoadedModel? = null

    @Synchronized
    fun reload(): LiveModel? {
        val active = store.active() ?: run { loaded = null; return null }
        loaded = LoadedModel(active, store.modelSize(active), System.currentTimeMillis())
        return active
    }

    fun active(): LiveModel? = loaded?.model ?: reload()
    fun loadedBytes(): Int = loaded?.size ?: reload()?.let { loaded?.size ?: 0 } ?: 0
    fun loadedAt(): Long? = loaded?.loadedAt

    private data class LoadedModel(val model: LiveModel, val size: Int, val loadedAt: Long)
}
