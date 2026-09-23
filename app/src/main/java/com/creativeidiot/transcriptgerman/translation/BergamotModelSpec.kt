package com.creativeidiot.transcriptgerman.translation

import java.io.File

internal data class BergamotModelFiles(
    val model: File,
    val vocab: File,
    val shortlist: File,
    val config: File,
)

internal object BergamotModelSpec {
    const val REVISION = "de-en-base-v2-caa7c0ce3c8eaf05"
    const val ARCHIVE_SHA256 =
        "caa7c0ce3c8eaf05d333dc9458683f4b0375e5eeb604f6fb2c8585f7b70d398b"
    const val ARCHIVE_URL =
        "https://data.statmt.org/bergamot/models/deen/" +
            "deen.student.base.v2.caa7c0ce3c8eaf05.tar.gz"
    const val DIRECTORY_NAME = "bergamot-de-en-base-v2"

    const val MODEL_FILE = "model.intgemm.alphas.bin"
    const val VOCAB_FILE = "vocab.deen.spm"
    const val SHORTLIST_FILE = "lex.s2t.bin"
    const val CONFIG_FILE = "config.intgemm8bitalpha.yml"

    fun directory(filesDir: File): File =
        File(File(filesDir, "models"), DIRECTORY_NAME)
}

internal object BergamotModelInstallVerifier {
    const val INSTALL_MARKER = ".installed-archive-sha256"

    fun installedFilesOrNull(filesDir: File): BergamotModelFiles? =
        installedFilesOrNull(BergamotModelSpec.directory(filesDir))

    fun installedFilesOrNull(directory: File): BergamotModelFiles? {
        if (!directory.isDirectory) return null

        val marker = File(directory, INSTALL_MARKER)
        if (
            !marker.isFile ||
            marker.readText().trim() != BergamotModelSpec.ARCHIVE_SHA256
        ) {
            return null
        }

        val allFiles = directory.walkTopDown()
            .filter(File::isFile)
            .toList()

        fun required(name: String): File? =
            allFiles.singleOrNull { file ->
                file.name == name && file.length() > 0L
            }

        val model = required(BergamotModelSpec.MODEL_FILE) ?: return null
        val vocab = required(BergamotModelSpec.VOCAB_FILE) ?: return null
        val shortlist = required(BergamotModelSpec.SHORTLIST_FILE) ?: return null
        val config = required(BergamotModelSpec.CONFIG_FILE) ?: return null

        return BergamotModelFiles(
            model = model,
            vocab = vocab,
            shortlist = shortlist,
            config = config,
        )
    }
}
