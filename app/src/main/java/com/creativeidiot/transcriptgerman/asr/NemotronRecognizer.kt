package com.creativeidiot.transcriptgerman.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

internal class NemotronRecognizer(
    modelDirectory: File,
    private val onSpeechDetected: (Boolean) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
) : CaptionRecognizer {
    private val recognizer: OnlineRecognizer
    private val stream: OnlineStream

    private var lastPartial = ""
    private var speechDetected = false
    private var closed = false

    init {
        val createdRecognizer = OnlineRecognizer(
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80,
                ),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(modelDirectory, "encoder.int8.onnx").absolutePath,
                        decoder = File(modelDirectory, "decoder.int8.onnx").absolutePath,
                        joiner = File(modelDirectory, "joiner.int8.onnx").absolutePath,
                    ),
                    tokens = File(modelDirectory, "tokens.txt").absolutePath,
                    numThreads = 4,
                    provider = "cpu",
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            ),
        )
        recognizer = createdRecognizer

        stream = try {
            createdRecognizer.createStream().also {
                it.setOption("language", GERMAN_LANGUAGE)
            }
        } catch (failure: Throwable) {
            try {
                createdRecognizer.release()
            } catch (releaseFailure: Throwable) {
                failure.addSuppressed(releaseFailure)
            }
            throw failure
        }
    }

    override fun accept(samples: FloatArray) {
        check(!closed) { "Recognizer is closed" }

        stream.acceptWaveform(samples, SAMPLE_RATE)
        drainReadyFrames()
        publishPartial()

        if (recognizer.isEndpoint(stream)) {
            finalizeCurrent(resetStream = true)
        }
    }

    override fun finish() {
        if (closed) return

        stream.inputFinished()
        drainReadyFrames()
        publishPartial()
        finalizeCurrent(resetStream = false)
    }

    override fun close() {
        if (closed) return
        closed = true

        try {
            stream.release()
        } finally {
            recognizer.release()
        }
    }

    private fun drainReadyFrames() {
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
    }

    private fun publishPartial() {
        val text = recognizer.getResult(stream).text.trim()
        if (text == lastPartial) return

        lastPartial = text
        onPartial(text)
        setSpeechDetected(text.isNotEmpty())
    }

    private fun finalizeCurrent(resetStream: Boolean) {
        val text = recognizer.getResult(stream).text.trim()
        if (text.isNotEmpty()) {
            onFinal(text)
        }

        if (lastPartial.isNotEmpty()) {
            lastPartial = ""
            onPartial("")
        }
        setSpeechDetected(false)

        if (resetStream) {
            recognizer.reset(stream)
        }
    }

    private fun setSpeechDetected(detected: Boolean) {
        if (speechDetected == detected) return
        speechDetected = detected
        onSpeechDetected(detected)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val GERMAN_LANGUAGE = "de-DE"
    }
}
