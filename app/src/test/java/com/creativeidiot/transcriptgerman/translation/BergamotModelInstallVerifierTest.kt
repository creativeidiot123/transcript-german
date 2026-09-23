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

        File(modelDirectory, "model.intgemm.alphas.bin").writeText("model")
        File(modelDirectory, "vocab.deen.spm").writeText("vocab")
        File(modelDirectory, "lex.s2t.bin").writeText("lex")
        File(modelDirectory, BergamotModelSpec.CONFIG_FILE).writeText("config")
        File(directory, BergamotModelInstallVerifier.INSTALL_MARKER)
            .writeText(BergamotModelSpec.ARCHIVE_SHA256)

        val files = BergamotModelInstallVerifier.installedFilesOrNull(filesDir)

        assertEquals("model.intgemm.alphas.bin", files?.model?.name)
        assertEquals(listOf("vocab.deen.spm"), files?.vocabs?.map { it.name })
        assertEquals("lex.s2t.bin", files?.shortlist?.name)
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
            File(modelDirectory, "model.intgemm.alphas.bin").writeText("model")
        }
        File(first, "vocab.deen.spm").writeText("vocab")
        File(first, "lex.s2t.bin").writeText("lex")
        File(first, BergamotModelSpec.CONFIG_FILE).writeText("config")
        File(directory, BergamotModelInstallVerifier.INSTALL_MARKER)
            .writeText(BergamotModelSpec.ARCHIVE_SHA256)

        assertNull(BergamotModelInstallVerifier.installedFilesOrNull(filesDir))
    }
}
