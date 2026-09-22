package com.creativeidiot.transcriptgerman.model

import android.util.Log
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

sealed interface ModelInstallState {
    data object Missing : ModelInstallState

    data class Downloading(
        val fileIndex: Int,
        val totalFiles: Int,
        val fileProgress: Float?,
    ) : ModelInstallState

    data object Ready : ModelInstallState
    data class Failed(val reason: ModelInstallFailure) : ModelInstallState
}

enum class ModelInstallFailure {
    DOWNLOAD_OR_STORAGE,
    INTEGRITY,
}

class ModelRepository(
    private val filesDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val bundle = PrimelineModelSpec.bundle
    private val downloadMutex = Mutex()

    private val _state = MutableStateFlow<ModelInstallState>(
        if (ModelInstallVerifier.isInstalled(filesDir, bundle)) {
            ModelInstallState.Ready
        } else {
            ModelInstallState.Missing
        },
    )

    val state: StateFlow<ModelInstallState> = _state.asStateFlow()

    fun installedDirectoryOrNull(): File? =
        bundle.directory(filesDir).takeIf {
            ModelInstallVerifier.isInstalled(filesDir, bundle)
        }

    suspend fun download() {
        downloadMutex.withLock {
            if (ModelInstallVerifier.isInstalled(filesDir, bundle)) {
                _state.value = ModelInstallState.Ready
                return@withLock
            }

            try {
                withContext(ioDispatcher) {
                    installBundle()
                }
                _state.value = ModelInstallState.Ready
            } catch (cancelled: CancellationException) {
                _state.value = ModelInstallState.Missing
                throw cancelled
            } catch (_: IntegrityException) {
                Log.e(TAG, "Model integrity verification failed")
                _state.value = ModelInstallState.Failed(ModelInstallFailure.INTEGRITY)
            } catch (failure: IOException) {
                Log.e(TAG, "Model download/storage failure: " + failure.javaClass.simpleName)
                _state.value =
                    ModelInstallState.Failed(ModelInstallFailure.DOWNLOAD_OR_STORAGE)
            } catch (failure: RuntimeException) {
                Log.e(TAG, "Model install runtime failure: " + failure.javaClass.simpleName)
                _state.value =
                    ModelInstallState.Failed(ModelInstallFailure.DOWNLOAD_OR_STORAGE)
            }
        }
    }

    private suspend fun installBundle() {
        val directory = bundle.directory(filesDir)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create model directory")
        }

        File(directory, ModelInstallVerifier.INSTALL_MARKER).delete()

        bundle.files.forEachIndexed { index, spec ->
            currentCoroutineContext().ensureActive()

            val finalFile = File(directory, spec.name)
            if (isVerifiedExistingFile(spec, finalFile)) {
                Log.i(TAG, "Reusing verified model asset " + spec.name)
                return@forEachIndexed
            }

            downloadAndVerify(
                directory = directory,
                spec = spec,
                fileIndex = index + 1,
                totalFiles = bundle.files.size,
            )
        }

        val marker = File(directory, ModelInstallVerifier.INSTALL_MARKER)
        val partialMarker = File(directory, ModelInstallVerifier.INSTALL_MARKER + ".part")
        partialMarker.delete()
        partialMarker.writeText(bundle.revision)
        marker.delete()

        if (!partialMarker.renameTo(marker)) {
            partialMarker.delete()
            throw IOException("Could not finalize install marker")
        }
    }

    private fun isVerifiedExistingFile(spec: ModelFileSpec, file: File): Boolean {
        if (!file.isFile) return false

        val size = file.length()
        if (size < spec.minimumBytes) return false
        if (spec.exactBytes != null && size != spec.exactBytes) return false

        val expectedSha = spec.sha256 ?: return true
        return fileSha256(file) == expectedSha
    }

    private suspend fun downloadAndVerify(
        directory: File,
        spec: ModelFileSpec,
        fileIndex: Int,
        totalFiles: Int,
    ) {
        val target = File(directory, spec.name)
        val partial = File(directory, spec.name + ".part")
        partial.delete()

        _state.value = ModelInstallState.Downloading(
            fileIndex = fileIndex,
            totalFiles = totalFiles,
            fileProgress = null,
        )
        Log.i(TAG, "Downloading model asset " + spec.name)

        val connection = (URI(spec.url).toURL().openConnection() as HttpURLConnection).apply {
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

            val digest = MessageDigest.getInstance("SHA-256")
            val declaredBytes = connection.contentLengthLong.takeIf { it > 0 }
            var written = 0L
            var nextProgressAt = PROGRESS_STEP_BYTES

            connection.inputStream.buffered().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue

                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        written += count

                        if (written >= nextProgressAt) {
                            val progress = declaredBytes?.let { length ->
                                (written.toDouble() / length.toDouble())
                                    .coerceIn(0.0, 1.0)
                                    .toFloat()
                            }
                            _state.value = ModelInstallState.Downloading(
                                fileIndex = fileIndex,
                                totalFiles = totalFiles,
                                fileProgress = progress,
                            )
                            nextProgressAt = written + PROGRESS_STEP_BYTES
                        }
                    }
                }
            }

            verifyDownloadedFile(spec, partial, written, digest.digest())

            target.delete()
            if (!partial.renameTo(target)) {
                throw IOException("Could not finalize model asset")
            }
            Log.i(TAG, "Verified model asset " + spec.name)
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    private fun verifyDownloadedFile(
        spec: ModelFileSpec,
        file: File,
        written: Long,
        digest: ByteArray,
    ) {
        if (!file.isFile || written < spec.minimumBytes) {
            throw IntegrityException()
        }
        if (spec.exactBytes != null && written != spec.exactBytes) {
            throw IntegrityException()
        }

        val expectedSha = spec.sha256 ?: return
        if (digest.toHex() != expectedSha) {
            throw IntegrityException()
        }
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private class IntegrityException : IOException()

    private companion object {
        const val TAG = "ModelRepository"
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val PROGRESS_STEP_BYTES = 2L * 1024L * 1024L
    }
}
