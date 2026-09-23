package com.creativeidiot.transcriptgerman.translation

import java.io.File

internal data class BergamotModelFiles(
    val model: File,
    val vocabs: List<File>,
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
    const val CONFIG_FILE = "config.intgemm8bitalpha.yml"

    fun directory(filesDir: File): File =
        File(File(filesDir, "models"), DIRECTORY_NAME)
}

internal object BergamotModelInstallVerifier {
    const val INSTALL_MARKER = ".installed-archive-sha256"

    fun installedFilesOrNull(filesDir: File): BergamotModelFiles? =
        installedFilesInDirectoryOrNull(BergamotModelSpec.directory(filesDir))

    fun installedFilesInDirectoryOrNull(directory: File): BergamotModelFiles? {
        if (!directory.isDirectory) return null

        val marker = File(directory, INSTALL_MARKER)
        if (
            !marker.isFile ||
            marker.readText().trim() != BergamotModelSpec.ARCHIVE_SHA256
        ) {
            return null
        }

        val allFiles = directory.walkTopDown()
            .filter { it.isFile && it.length() > 0L }
            .toList()

        val model = allFiles.singleOrNull { file ->
            file.name.startsWith("model") &&
                file.name.endsWith(".bin") &&
                !file.name.startsWith("lex")
        } ?: return null

        val vocabs = allFiles
            .filter { it.name.endsWith(".spm") }
            .sortedBy { it.name }
        if (vocabs.size !in 1..2) return null

        val shortlist = allFiles.singleOrNull { file ->
            file.name.startsWith("lex") && file.name.endsWith(".bin")
        } ?: return null

        val config = allFiles.singleOrNull {
            it.name == BergamotModelSpec.CONFIG_FILE
        } ?: return null

        return BergamotModelFiles(
            model = model,
            vocabs = vocabs,
            shortlist = shortlist,
            config = config,
        )
    }
}
