package com.creativeidiot.transcriptgerman.translation

import android.util.Log
import com.creativeidiot.transcriptgerman.model.ModelInstallFailure
import com.creativeidiot.transcriptgerman.model.ModelInstallState
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

class BergamotModelRepository(
    private val filesDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val downloadMutex: Mutex = Mutex(),
) {
    private val _state = MutableStateFlow<ModelInstallState>(
        if (BergamotModelInstallVerifier.installedFilesOrNull(filesDir) != null) {
            ModelInstallState.Ready
        } else {
            ModelInstallState.Missing
        },
    )

    val state: StateFlow<ModelInstallState> = _state.asStateFlow()

    internal fun installedFilesOrNull(): BergamotModelFiles? =
        BergamotModelInstallVerifier.installedFilesOrNull(filesDir)

    suspend fun download() {
        downloadMutex.withLock {
            if (installedFilesOrNull() != null) {
                _state.value = ModelInstallState.Ready
                return@withLock
            }

            try {
                withContext(ioDispatcher) {
                    install()
                }
                _state.value = ModelInstallState.Ready
            } catch (cancelled: CancellationException) {
                _state.value = ModelInstallState.Missing
                throw cancelled
            } catch (_: IntegrityException) {
                Log.e(TAG, "Bergamot model integrity verification failed")
                _state.value =
                    ModelInstallState.Failed(ModelInstallFailure.INTEGRITY)
            } catch (failure: IOException) {
                Log.e(
                    TAG,
                    "Bergamot model download/storage failure: " +
                        failure.javaClass.simpleName,
                )
                _state.value =
                    ModelInstallState.Failed(ModelInstallFailure.DOWNLOAD_OR_STORAGE)
            } catch (failure: RuntimeException) {
                Log.e(
                    TAG,
                    "Bergamot model install runtime failure: " +
                        failure.javaClass.simpleName,
                )
                _state.value =
                    ModelInstallState.Failed(ModelInstallFailure.DOWNLOAD_OR_STORAGE)
            }
        }
    }

    private suspend fun install() {
        val modelsDirectory = BergamotModelSpec.directory(filesDir).parentFile
            ?: throw IOException("Missing model parent directory")
        if (!modelsDirectory.exists() && !modelsDirectory.mkdirs()) {
            throw IOException("Could not create model directory")
        }

        val archive = File(
            modelsDirectory,
            BergamotModelSpec.DIRECTORY_NAME + ".tar.gz.part",
        )
        val staging = File(
            modelsDirectory,
            BergamotModelSpec.DIRECTORY_NAME + ".staging",
        )
        archive.delete()
        staging.deleteRecursively()

        try {
            downloadArchive(archive)
            verifyArchive(archive)
            extractArchive(archive, staging)

            val files = BergamotModelInstallVerifier.installedFilesInDirectoryOrNull(
                staging.apply {
                    File(this, BergamotModelInstallVerifier.INSTALL_MARKER)
                        .writeText(BergamotModelSpec.ARCHIVE_SHA256)
                },
            )
            if (files == null) {
                throw IntegrityException()
            }

            val destination = BergamotModelSpec.directory(filesDir)
            destination.deleteRecursively()
            if (!staging.renameTo(destination)) {
                throw IOException("Could not finalize Bergamot model directory")
            }
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    private suspend fun downloadArchive(target: File) {
        _state.value = ModelInstallState.Downloading(
            fileIndex = 1,
            totalFiles = 1,
            fileProgress = null,
        )

        val connection = (
            URI(BergamotModelSpec.ARCHIVE_URL)
                .toURL()
                .openConnection() as HttpURLConnection
            ).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
        }

        try {
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Unexpected HTTP status")
            }

            val declaredBytes = connection.contentLengthLong.takeIf { it > 0L }
            var written = 0L
            var nextProgressAt = PROGRESS_STEP_BYTES

            connection.inputStream.buffered().use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        written += count

                        if (written >= nextProgressAt) {
                            _state.value = ModelInstallState.Downloading(
                                fileIndex = 1,
                                totalFiles = 1,
                                fileProgress = declaredBytes?.let { size ->
                                    (written.toDouble() / size.toDouble())
                                        .coerceIn(0.0, 1.0)
                                        .toFloat()
                                },
                            )
                            nextProgressAt = written + PROGRESS_STEP_BYTES
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyArchive(archive: File) {
        if (!archive.isFile || archive.length() <= 0L) {
            throw IntegrityException()
        }

        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }

        if (digest.digest().toHex() != BergamotModelSpec.ARCHIVE_SHA256) {
            throw IntegrityException()
        }
    }

    private suspend fun extractArchive(
        archive: File,
        destination: File,
    ) {
        if (!destination.mkdirs()) {
            throw IOException("Could not create Bergamot staging directory")
        }
        val destinationPath = destination.canonicalFile.toPath()

        archive.inputStream().buffered().use { fileInput ->
            GzipCompressorInputStream(fileInput).use { gzipInput ->
                TarArchiveInputStream(gzipInput).use { tarInput ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = tarInput.nextTarEntry ?: break

                        if (entry.isSymbolicLink || entry.isLink) {
                            throw IntegrityException()
                        }

                        val output = File(destination, entry.name).canonicalFile
                        if (!output.toPath().startsWith(destinationPath)) {
                            throw IntegrityException()
                        }

                        if (entry.isDirectory) {
                            if (!output.exists() && !output.mkdirs()) {
                                throw IOException("Could not create model directory")
                            }
                        } else if (entry.isFile) {
                            val parent = output.parentFile
                                ?: throw IntegrityException()
                            if (!parent.exists() && !parent.mkdirs()) {
                                throw IOException("Could not create model directory")
                            }
                            output.outputStream().buffered().use { target ->
                                val buffer = ByteArray(BUFFER_BYTES)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = tarInput.read(buffer)
                                    if (count < 0) break
                                    target.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private class IntegrityException : IOException()

    private companion object {
        const val TAG = "BergamotModelRepository"
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_STEP_BYTES = 2L * 1024L * 1024L
    }
}
