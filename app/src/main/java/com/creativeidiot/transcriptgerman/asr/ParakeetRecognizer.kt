package com.creativeidiot.transcriptgerman.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

internal class ParakeetRecognizer(
    modelDirectory: File,
    private val onSpeechDetected: (Boolean) -> Unit,
    private val onTranscribing: (Boolean) -> Unit,
    private val onFinal: (String) -> Unit,
) : CaptionRecognizer {
    private val vad: Vad
    private val recognizer: OfflineRecognizer

    init {
        val createdVad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = File(modelDirectory, "silero_vad.onnx").absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.3f,
                    minSpeechDuration = 0.2f,
                    windowSize = 512,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            ),
        )
        vad = createdVad

        recognizer = try {
            OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = SAMPLE_RATE,
                        featureDim = 80,
                    ),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = File(modelDirectory, "encoder.int8.onnx").absolutePath,
                            decoder = File(modelDirectory, "decoder.int8.onnx").absolutePath,
                            joiner = File(modelDirectory, "joiner.int8.onnx").absolutePath,
                        ),
                        tokens = File(modelDirectory, "tokens.txt").absolutePath,
                        numThreads = 4,
                        provider = "cpu",
                        modelType = "nemo_transducer",
                    ),
                    decodingMethod = "greedy_search",
                ),
            )
        } catch (failure: Throwable) {
            try {
                createdVad.release()
            } catch (releaseFailure: Throwable) {
                failure.addSuppressed(releaseFailure)
            }
            throw failure
        }
    }

    private var speechDetected = false
    private var closed = false

    override fun accept(samples: FloatArray) {
        check(!closed) { "Recognizer is closed" }

        vad.acceptWaveform(samples)
        drainSegments()

        val nowDetected = vad.isSpeechDetected()
        if (nowDetected != speechDetected) {
            speechDetected = nowDetected
            onSpeechDetected(nowDetected)
        }
    }

    override suspend fun finish() {
        if (closed) return

        vad.flush()
        drainSegments()

        if (speechDetected) {
            speechDetected = false
            onSpeechDetected(false)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        vad.release()
        recognizer.release()
    }

    private fun drainSegments() {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()

            onTranscribing(true)
            try {
                val text = transcribe(segment.samples)
                if (text.isNotBlank()) {
                    onFinal(text)
                }
            } finally {
                onTranscribing(false)
            }
        }
    }

    private fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
