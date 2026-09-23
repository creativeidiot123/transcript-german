package com.creativeidiot.transcriptgerman.translation

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BergamotModelInstallVerifierTest {
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("bergamot-verifier").toFile()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun completePinnedBundle_isResolved() {
        val directory = BergamotModelSpec.directory(filesDir)
        val modelDirectory = File(directory, "deen.student.base").apply { mkdirs() }

        File(modelDirectory, BergamotModelSpec.MODEL_FILE).writeText("model")
        File(modelDirectory, BergamotModelSpec.VOCAB_FILE).writeText("vocab")
        File(modelDirectory, BergamotModelSpec.SHORTLIST_FILE).writeText("lex")
        File(modelDirectory, BergamotModelSpec.CONFIG_FILE).writeText("config")
        File(directory, BergamotModelInstallVerifier.INSTALL_MARKER)
            .writeText(BergamotModelSpec.ARCHIVE_SHA256)

        val files = BergamotModelInstallVerifier.installedFilesOrNull(filesDir)

        assertEquals(BergamotModelSpec.MODEL_FILE, files?.model?.name)
        assertEquals(BergamotModelSpec.VOCAB_FILE, files?.vocab?.name)
        assertEquals(BergamotModelSpec.SHORTLIST_FILE, files?.shortlist?.name)
        assertEquals(BergamotModelSpec.CONFIG_FILE, files?.config?.name)
    }

    @Test
    fun staleMarker_isRejected() {
        val directory = BergamotModelSpec.directory(filesDir).apply { mkdirs() }
        File(directory, BergamotModelInstallVerifier.INSTALL_MARKER).writeText("stale")

        assertNull(BergamotModelInstallVerifier.installedFilesOrNull(filesDir))
    }

    @Test
    fun duplicateRequiredFile_isRejected() {
        val directory = BergamotModelSpec.directory(filesDir)
        val first = File(directory, "first").apply { mkdirs() }
        val second = File(directory, "second").apply { mkdirs() }

        listOf(first, second).forEach { modelDirectory ->
            File(modelDirectory, BergamotModelSpec.MODEL_FILE).writeText("model")
        }
        File(first, BergamotModelSpec.VOCAB_FILE).writeText("vocab")
        File(first, BergamotModelSpec.SHORTLIST_FILE).writeText("lex")
        File(first, BergamotModelSpec.CONFIG_FILE).writeText("config")
        File(directory, BergamotModelInstallVerifier.INSTALL_MARKER)
            .writeText(BergamotModelSpec.ARCHIVE_SHA256)

        assertNull(BergamotModelInstallVerifier.installedFilesOrNull(filesDir))
    }
}
