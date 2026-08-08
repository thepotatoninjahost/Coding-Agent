package com.codingagent.core

import com.codingagent.model.ModelDownloadProgress
import com.codingagent.model.ModelShard
import com.codingagent.model.NexaModelProvisioner as ModuleNexaModelProvisioner
import com.codingagent.model.ProvisionedModel
import com.codingagent.model.Qwen3NpuPackage
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

fun ProvisionedModel.modelPath(): File = directory

class NexaModelProvisioner(
    private val assetOpener: (String) -> java.io.InputStream,
    private val root: File,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val maxAttempts: Int = 8
) {
    private val delegate = ModuleNexaModelProvisioner(assetOpener, root, connectionFactory, maxAttempts)

    fun ensure(onProgress: (ModelDownloadProgress) -> Unit = {}): ProvisionedModel = delegate.ensure(onProgress)

    fun remainingDownloadBytes(directory: File = root.resolve(Qwen3NpuPackage.packageName)): Long =
        delegate.remainingDownloadBytes(directory)
}

typealias ModelDownloadProgress = com.codingagent.model.ModelDownloadProgress
typealias Qwen3NpuPackage = com.codingagent.model.Qwen3NpuPackage
typealias ProvisionedModel = com.codingagent.model.ProvisionedModel

typealias ModelShard = com.codingagent.model.ModelShard
