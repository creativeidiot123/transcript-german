package com.creativeidiot.transcriptgerman.asr

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.VadModelConfig

internal fun localConversationVadConfig(modelPath: String): VadModelConfig =
    VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = modelPath,
            threshold = 0.5f,
            minSilenceDuration = 0.4f,
            minSpeechDuration = 0.2f,
            windowSize = 512,
            maxSpeechDuration = 10.0f,
        ),
        sampleRate = 16_000,
        numThreads = 1,
        provider = "cpu",
    )

private const val NEMOTRON_FINAL_PADDING_MILLIS = 300

internal fun nemotronFinalPadding(sampleRate: Int): FloatArray {
    require(sampleRate > 0) { "sampleRate must be positive" }
    return FloatArray(sampleRate * NEMOTRON_FINAL_PADDING_MILLIS / 1_000)
}

internal fun nemotronEndpointConfig(): EndpointConfig =
    EndpointConfig(
        rule1 = EndpointRule(
            mustContainNonSilence = false,
            minTrailingSilence = 2.4f,
            minUtteranceLength = 0.0f,
        ),
        rule2 = EndpointRule(
            mustContainNonSilence = true,
            minTrailingSilence = 0.8f,
            minUtteranceLength = 0.0f,
        ),
        rule3 = EndpointRule(
            mustContainNonSilence = false,
            minTrailingSilence = 0.0f,
            minUtteranceLength = 20.0f,
        ),
    )
