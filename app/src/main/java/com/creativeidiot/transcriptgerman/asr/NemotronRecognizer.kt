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
    private val onTranscribing: (Boolean) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
) : CaptionRecognizer {
    private val recognizer: OnlineRecognizer
    private val stream: OnlineStream

    private var lastPartial = ""
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
                endpointConfig = nemotronEndpointConfig(),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                blankPenalty = NEMOTRON_BLANK_PENALTY,
            ),
        )
        var createdStream: OnlineStream? = null
        try {
            val streamCandidate = createdRecognizer.createStream()
            createdStream = streamCandidate
            streamCandidate.setOption(LANGUAGE_OPTION, GERMAN_LANGUAGE)
        } catch (failure: Throwable) {
            try {
                createdStream?.release()
            } catch (releaseFailure: Throwable) {
                failure.addSuppressed(releaseFailure)
            }
            try {
                createdRecognizer.release()
            } catch (releaseFailure: Throwable) {
                failure.addSuppressed(releaseFailure)
            }
            throw failure
        }

        recognizer = createdRecognizer
        stream = requireNotNull(createdStream)
    }

    override fun accept(samples: FloatArray) {
        check(!closed) { "Recognizer is closed" }

        stream.acceptWaveform(samples, SAMPLE_RATE)
        decodeAvailable()

        val isEndpoint = recognizer.isEndpoint(stream)
        val text = recognizer.getResult(stream).text.trim()

        if (isEndpoint) {
            publishFinal(text)
            recognizer.reset(stream)
        } else if (text != lastPartial) {
            lastPartial = text
            onPartial(text)
        }
    }

    override suspend fun finish() {
        if (closed) return

        // sherpa-onnx's Nemotron streaming example feeds a short silence tail before EOF so
        // the final acoustic frames can be decoded instead of clipping the last spoken word.
        stream.acceptWaveform(nemotronFinalPadding(SAMPLE_RATE), SAMPLE_RATE)
        stream.inputFinished()
        decodeAvailable()
        publishFinal(recognizer.getResult(stream).text.trim())
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

    private fun decodeAvailable() {
        if (!recognizer.isReady(stream)) return

        onTranscribing(true)
        try {
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }
        } finally {
            onTranscribing(false)
        }
    }

    private fun publishFinal(text: String) {
        if (text.isNotBlank()) {
            onFinal(text)
        } else if (lastPartial.isNotEmpty()) {
            onPartial("")
        }
        lastPartial = ""
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val LANGUAGE_OPTION = "language"
        const val GERMAN_LANGUAGE = "de"
    }
}
