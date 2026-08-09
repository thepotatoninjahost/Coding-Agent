package com.codingagent.model

import com.codingagent.domain.*
import com.codingagent.policy.AgentAction
import com.codingagent.policy.AgentConstitution

import java.io.File
import java.security.MessageDigest
import java.util.UUID

sealed class ModelInstallResult {
    data class Installed(val model: LiveModel) : ModelInstallResult()
    data class Rejected(val reason: String) : ModelInstallResult()
}

data class LiveModel(
    val id: String,
    val name: String,
    val format: String,
    val sourcePath: String,
    val checksum: String,
    val sizeBytes: Long,
    val createdAt: Long
)

class LiveModelStore(private val root: File) {
    private val modelRoot = root.resolve(".coding-agent/models")
    private val activeFile = modelRoot.resolve("active-model")
    private val historyFile = modelRoot.resolve("history.tsv")

    init { modelRoot.mkdirs() }


    fun install(source: File, name: String, format: String, action: AgentAction, evaluation: VerificationReport): ModelInstallResult {
        if (!source.isFile) return ModelInstallResult.Rejected("Model file does not exist")
        val violations = AgentConstitution.check(action.copy(sandboxPassed = true, ownerVerified = true, approvalCount = maxOf(2, action.approvalCount), clearPermission = true))
        if (violations.isNotEmpty()) return ModelInstallResult.Rejected(violations.joinToString("; ") { "${it.rule}: ${it.message}" })
        val id = "${name.replace(Regex("[^A-Za-z0-9_-]"), "-")}-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val destination = modelRoot.resolve(id).apply { mkdirs() }.resolve(source.name)
        source.copyTo(destination)
        val model = LiveModel(id, name, format, destination.absolutePath, checksum(destination), destination.length(), System.currentTimeMillis())
        historyFile.appendText(listOf(model.id, model.name, model.format, model.checksum, model.sizeBytes, model.createdAt).joinToString("\t") + "\n")
        activeFile.writeText(model.id)
        return ModelInstallResult.Installed(model)
    }

    fun active(): LiveModel? {
        val id = activeFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (id.isBlank()) return null
        val directory = modelRoot.resolve(id)
        if (!directory.isDirectory) return null
        val fields = history().firstOrNull { it.id == id } ?: return null
        val source = directory.listFiles()?.firstOrNull { it.isFile } ?: return null
        return fields.copy(sourcePath = source.absolutePath)
    }

    fun history(): List<LiveModel> = if (!historyFile.isFile) emptyList() else historyFile.readLines().mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size == 6) LiveModel(fields[0], fields[1], fields[2], "", fields[3], fields[4].toLongOrNull() ?: 0L, fields[5].toLongOrNull() ?: 0L) else null
    }

    fun modelBytes(model: LiveModel): ByteArray = File(model.sourcePath).walkTopDown().filter { it.isFile }.sortedBy { it.absolutePath }.fold(ByteArray(0)) { acc, file -> acc + file.readBytes() }

    private fun checksum(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

}

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
