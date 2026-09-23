package com.creativeidiot.transcriptgerman.model

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelInstallVerifierTest {
    private lateinit var filesDir: File

    private val bundle = ModelBundleSpec(
        revision = "test-revision",
        directoryName = "test-model",
        files = listOf(
            ModelFileSpec(
                name = "encoder.onnx",
                url = "https://example.invalid/encoder.onnx",
                exactBytes = 4,
            ),
            ModelFileSpec(
                name = "tokens.txt",
                url = "https://example.invalid/tokens.txt",
                minimumBytes = 3,
            ),
        ),
    )

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("model-verifier").toFile()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun productionBundles_pinExactAssetSizes() {
        assertTrue(
            listOf(
                PrimelineModelSpec.bundle,
                NemotronModelSpec.bundle,
            ).all { productionBundle ->
                productionBundle.files.all { it.exactBytes != null }
            },
        )
    }

    @Test
    fun productionOnnxAssets_pinDigests() {
        assertTrue(
            listOf(
                PrimelineModelSpec.bundle,
                NemotronModelSpec.bundle,
            ).all { productionBundle ->
                productionBundle.files
                    .filter { it.name.endsWith(".onnx") || it.name.endsWith(".onnx.data") }
                    .all { it.sha256 != null }
            },
        )
    }

    @Test
    fun backendCatalog_usesIndependentInstallDirectories() {
        assertNotEquals(
            ModelCatalog.bundleFor(AsrBackend.PRIMELINE).directoryName,
            ModelCatalog.bundleFor(AsrBackend.NEMOTRON).directoryName,
        )
    }

    @Test
    fun missingMarker_isNotInstalled() {
        val directory = bundle.directory(filesDir).apply { mkdirs() }
        File(directory, "encoder.onnx").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(directory, "tokens.txt").writeText("abc")

        assertFalse(ModelInstallVerifier.isInstalled(filesDir, bundle))
    }

    @Test
    fun completeBundleWithMatchingMarker_isInstalled() {
        val directory = bundle.directory(filesDir).apply { mkdirs() }
        File(directory, "encoder.onnx").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(directory, "tokens.txt").writeText("abc")
        File(directory, ModelInstallVerifier.INSTALL_MARKER).writeText(bundle.revision)

        assertTrue(ModelInstallVerifier.isInstalled(filesDir, bundle))
    }

    @Test
    fun wrongSizedFile_isNotInstalled() {
        val directory = bundle.directory(filesDir).apply { mkdirs() }
        File(directory, "encoder.onnx").writeBytes(byteArrayOf(1, 2, 3))
        File(directory, "tokens.txt").writeText("abc")
        File(directory, ModelInstallVerifier.INSTALL_MARKER).writeText(bundle.revision)

        assertFalse(ModelInstallVerifier.isInstalled(filesDir, bundle))
    }

    @Test
    fun staleRevisionMarker_isNotInstalled() {
        val directory = bundle.directory(filesDir).apply { mkdirs() }
        File(directory, "encoder.onnx").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(directory, "tokens.txt").writeText("abc")
        File(directory, ModelInstallVerifier.INSTALL_MARKER).writeText("old-revision")

        assertFalse(ModelInstallVerifier.isInstalled(filesDir, bundle))
    }
}
