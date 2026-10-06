package com.codingagent.core

import android.content.Context
import com.codingagent.agent.UserMemory
import com.codingagent.agent.UserMemoryIndex
import com.codingagent.agent.UserMemoryStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * ONE JOB: Persist the local user's durable memories in encrypted app-private storage.
 */
internal class DeviceUserMemoryStore(context: Context) : UserMemoryStore {
    private val securePrefs = KeystoreSecretStore(context)
    private val index = UserMemoryIndex()
    private val lock = Any()

    override fun remember(
        text: String,
        kind: UserMemory.Kind,
        importance: Int
    ): UserMemory? = synchronized(lock) {
        val current = load()
        val next = index.remember(
            existing = current,
            text = text,
            kind = kind,
            importance = importance,
            now = System.currentTimeMillis()
        )
        if (next == current) return null
        save(next)
        next.firstOrNull { it.text == text.replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?') }
    }

    override fun relevant(query: String, limit: Int): List<UserMemory> = synchronized(lock) {
        index.relevant(load(), query, limit)
    }

    override fun all(): List<UserMemory> = synchronized(lock) {
        load().sortedByDescending { it.updatedAt }
    }

    override fun forget(query: String): Int = synchronized(lock) {
        val current = load()
        val next = index.forget(current, query)
        val removed = current.size - next.size
        if (removed > 0) save(next)
        removed
    }

    private fun load(): List<UserMemory> {
        val raw = securePrefs.getString(KeystoreSecretStore.USER_MEMORY) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        UserMemory(
                            id = item.getString("id"),
                            text = item.getString("text"),
                            kind = UserMemory.Kind.valueOf(item.getString("kind")),
                            importance = item.optInt("importance", 50).coerceIn(1, 99),
                            createdAt = item.getLong("createdAt"),
                            updatedAt = item.getLong("updatedAt")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(memories: List<UserMemory>) {
        val array = JSONArray()
        memories.forEach { memory ->
            array.put(
                JSONObject()
                    .put("id", memory.id)
                    .put("text", memory.text)
                    .put("kind", memory.kind.name)
                    .put("importance", memory.importance)
                    .put("createdAt", memory.createdAt)
                    .put("updatedAt", memory.updatedAt)
            )
        }
        securePrefs.putString(KeystoreSecretStore.USER_MEMORY, array.toString())
    }
}
