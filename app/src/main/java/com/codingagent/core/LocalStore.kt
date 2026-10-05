package com.codingagent.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import com.codingagent.agent.ChatMessage
import com.codingagent.agent.ChatMessageStore
import com.codingagent.agent.ChatRole
import com.codingagent.model.ModelSettings
import com.codingagent.workspace.Lesson
import com.codingagent.workspace.TaskRecord

/**
 * ONE JOB: Persist small JSON settings and state on device.
 */
class LocalStore(context: Context) : ChatMessageStore {
    private val root = File(context.filesDir, "coding-agent").apply { mkdirs() }
    private val tasksFile = File(root, "tasks.jsonl")
    private val lessonsFile = File(root, "lessons.jsonl")
    private val docsFile = File(root, "documents.jsonl")
    private val chatFile = File(root, "chat.jsonl")

    // Model settings include the user's API key. Values are encrypted with an AES-256-GCM key
    // that remains inside Android Keystore; only ciphertext is persisted in SharedPreferences.
    private val securePrefs = KeystoreSecretStore(context).also {
        LegacyEncryptedPreferencesMigration.migrateIfNeeded(context, it)
    }

    fun saveProjectPath(path: String?) {
        securePrefs.putString(KeystoreSecretStore.PROJECT_PATH, path)
    }

    fun loadProjectPath(): String? = securePrefs.getString(KeystoreSecretStore.PROJECT_PATH)?.takeIf { it.isNotBlank() }

    fun saveLastResearchQuery(query: String?) {
        securePrefs.putString(KeystoreSecretStore.LAST_RESEARCH, query)
    }

    fun loadLastResearchQuery(): String? = securePrefs.getString(KeystoreSecretStore.LAST_RESEARCH)?.takeIf { it.isNotBlank() }

    fun saveModelSettings(settings: ModelSettings) {
        securePrefs.putString(KeystoreSecretStore.MODEL_SETTINGS, ModelSettings.toJson(settings.normalized().copy(onboarded = true)))
    }

    fun loadModelSettings(): ModelSettings = ModelSettings.fromJson(securePrefs.getString(KeystoreSecretStore.MODEL_SETTINGS))

    companion object {
        private const val KEY_PROJECT_PATH = "project_path"
        private const val KEY_LAST_RESEARCH = "last_research_query"
        private const val KEY_MODEL_SETTINGS = "model_settings_v1"
    }

    @Synchronized
    fun recordTask(record: TaskRecord) = append(tasksFile, JSONObject()
        .put("id", record.id)
        .put("request", record.request)
        .put("status", record.status)
        .put("createdAt", record.createdAt)
        .put("changes", record.changes)
        .put("verificationPassed", record.verificationPassed)
        .toString())

    @Synchronized
    fun recordLesson(lesson: Lesson) = append(lessonsFile, JSONObject()
        .put("pattern", lesson.pattern)
        .put("outcome", lesson.outcome)
        .put("evidence", lesson.evidence)
        .put("createdAt", lesson.createdAt)
        .toString())

    @Synchronized
    fun recordDocument(name: String, source: String, content: String) = append(docsFile, JSONObject()
        .put("name", name)
        .put("source", source)
        .put("content", content)
        .put("createdAt", System.currentTimeMillis())
        .toString())

    @Synchronized
    override fun recordChatMessage(message: ChatMessage) = append(chatFile, JSONObject()
        .put("id", message.id)
        .put("role", message.role.name)
        .put("content", message.content)
        .put("createdAt", message.createdAt)
        .put("taskId", message.taskId)
        .toString())

    override fun recentChatMessages(limit: Int): List<ChatMessage> = read(chatFile, limit).mapNotNull {
        runCatching {
            ChatMessage(
                id = it.getString("id"),
                role = ChatRole.valueOf(it.getString("role")),
                content = it.getString("content"),
                createdAt = it.getLong("createdAt"),
                taskId = it.optString("taskId").takeIf { value -> value.isNotBlank() && value != "null" }
            )
        }.getOrNull()
    }

    fun recentTasks(limit: Int = 20): List<TaskRecord> = read(tasksFile, limit).mapNotNull {
        runCatching {
            TaskRecord(
                id = it.getString("id"),
                request = it.getString("request"),
                status = it.getString("status"),
                createdAt = it.getLong("createdAt"),
                changes = it.getInt("changes"),
                verificationPassed = it.getBoolean("verificationPassed")
            )
        }.getOrNull()
    }

    fun recentLessons(limit: Int = 20): List<Lesson> = read(lessonsFile, limit).mapNotNull {
        runCatching {
            Lesson(
                pattern = it.getString("pattern"),
                outcome = it.getString("outcome"),
                evidence = it.getString("evidence"),
                createdAt = it.getLong("createdAt")
            )
        }.getOrNull()
    }

    private fun append(file: File, line: String) {
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
    }

    private fun read(file: File, limit: Int): List<JSONObject> {
        if (!file.exists() || limit <= 0) return emptyList()

        // Keep only the requested physical tail. Parsing happens after the tail is
        // selected so malformed recent records preserve the previous semantics.
        val tail = ArrayDeque<String>(limit)
        file.useLines { lines ->
            lines.forEach { line ->
                if (tail.size == limit) tail.removeFirst()
                tail.addLast(line)
            }
        }

        return tail.toList()
            .asReversed()
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
    }
}
