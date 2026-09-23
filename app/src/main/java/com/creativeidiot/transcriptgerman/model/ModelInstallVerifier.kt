package com.creativeidiot.transcriptgerman.model

import java.io.File

internal object ModelInstallVerifier {
    const val INSTALL_MARKER = ".installed-revision"

    fun isInstalled(filesDir: File, bundle: ModelBundleSpec): Boolean {
        val directory = bundle.directory(filesDir)
        if (!directory.isDirectory) return false

        val marker = File(directory, INSTALL_MARKER)
        if (!marker.isFile || marker.readText().trim() != bundle.revision) return false

        return bundle.files.all { spec ->
            val file = File(directory, spec.name)
            if (!file.isFile) return@all false

            val size = file.length()
            val exactMatches = spec.exactBytes?.let { expected -> size == expected } ?: true
            exactMatches && size >= spec.minimumBytes
        }
    }
}
