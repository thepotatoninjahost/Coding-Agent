package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.nio.file.Files

class NexaModelProvisionerTest {
    @Test
    fun `bundled metadata is copied and verified files are reused`() {
        val root = Files.createTempDirectory("nexa-provision").toFile()
        val assets = FakeAssetManager(mapOf(
            "models/qwen3-4b-npu-mobile/nexa.manifest" to "{\"ModelName\":\"qwen3-4b\"}".toByteArray()
        ))
        val provisioner = NexaModelProvisioner({ name -> assets.open(name) }, root, { error("network should not be called by this test") })
        val destination = root.resolve(Qwen3NpuPackage.packageName).apply { mkdirs() }
        assertTrue(Qwen3NpuPackage.totalBytes > 4_000_000_000L)
        assertEquals("qwen3-4b-npu-mobile", Qwen3NpuPackage.packageName)
        assertEquals("qwen3-4b", Qwen3NpuPackage.modelName)
        assertEquals("models/qwen3-4b-npu-mobile", Qwen3NpuPackage.assetRoot)
    }

    private class FakeAssetManager(private val values: Map<String, ByteArray>) {
        fun open(fileName: String): java.io.InputStream = values.getValue(fileName).inputStream()
    }
}
