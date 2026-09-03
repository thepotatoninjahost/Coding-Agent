package com.codingagent.model

/**
 * ONE JOB: Load the active [LiveModel]'s bytes into memory and serve them until reload() is
 * called. Extracted out of LiveModelStore.kt — storing/checksumming model packages on disk and
 * routing to the currently loaded one are different jobs.
 */
class LiveModelRouter(private val store: LiveModelStore) {
    private var loaded: LoadedModel? = null

    @Synchronized
    fun reload(): LiveModel? {
        val active = store.active() ?: run { loaded = null; return null }
        val bytes = store.modelBytes(active)
        loaded = LoadedModel(active, bytes, System.currentTimeMillis())
        return active
    }

    fun active(): LiveModel? = loaded?.model ?: reload()
    fun loadedBytes(): Int = loaded?.bytes?.size ?: reload()?.let { loaded?.bytes?.size ?: 0 } ?: 0
    fun loadedAt(): Long? = loaded?.loadedAt

    private data class LoadedModel(val model: LiveModel, val bytes: ByteArray, val loadedAt: Long)
}
