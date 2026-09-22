package com.creativeidiot.transcriptgerman.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

enum class AudioCaptureFailure {
    UNSUPPORTED,
    INITIALIZATION,
    READ,
    SECURITY,
    UNEXPECTED,
}

class AudioCapture(
    private val onChunk: (FloatArray) -> Unit,
    private val onFailure: (AudioCaptureFailure) -> Unit,
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var recorder: AudioRecord? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false

        thread = Thread(::captureLoop, "caption-audio").also { it.start() }
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { recorder?.stop() }

        val captureThread = thread
        if (captureThread != null && captureThread !== Thread.currentThread()) {
            captureThread.join(STOP_JOIN_MILLIS)
        }
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        try {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (minBuffer <= 0) {
                signalFailureIfRunning(AudioCaptureFailure.UNSUPPORTED)
                return
            }

            val localRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                maxOf(minBuffer, SAMPLE_RATE * 2),
            )
            recorder = localRecorder

            if (localRecorder.state != AudioRecord.STATE_INITIALIZED) {
                signalFailureIfRunning(AudioCaptureFailure.INITIALIZATION)
                return
            }
            if (!running.get()) return

            localRecorder.startRecording()
            val buffer = ShortArray(CHUNK_SAMPLES)

            while (running.get()) {
                val count = localRecorder.read(buffer, 0, buffer.size)
                when {
                    count > 0 -> {
                        val samples = FloatArray(count) { index -> buffer[index] / 32768f }
                        onChunk(samples)
                    }
                    count < 0 -> {
                        signalFailureIfRunning(AudioCaptureFailure.READ)
                        return
                    }
                }
            }
        } catch (_: SecurityException) {
            signalFailureIfRunning(AudioCaptureFailure.SECURITY)
        } catch (_: RuntimeException) {
            signalFailureIfRunning(AudioCaptureFailure.UNEXPECTED)
        } finally {
            running.set(false)
            val localRecorder = recorder
            recorder = null

            if (localRecorder != null) {
                runCatching {
                    if (localRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        localRecorder.stop()
                    }
                }
                localRecorder.release()
            }
        }
    }

    private fun signalFailureIfRunning(failure: AudioCaptureFailure) {
        if (running.get()) {
            onFailure(failure)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SAMPLES = 1_600
        const val STOP_JOIN_MILLIS = 1_000L
    }
}
