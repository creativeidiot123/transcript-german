package com.creativeidiot.transcriptgerman.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognizerTuningTest {
    @Test
    fun localConversationVad_keepsShortPausesWithoutUnboundedSegments() {
        val config = localConversationVadConfig("silero_vad.onnx")
        val silero = config.sileroVadModelConfig

        assertEquals("silero_vad.onnx", silero.model)
        assertEquals(0.5f, silero.threshold, 0.0f)
        assertEquals(0.4f, silero.minSilenceDuration, 0.0f)
        assertEquals(0.2f, silero.minSpeechDuration, 0.0f)
        assertEquals(10.0f, silero.maxSpeechDuration, 0.0f)
        assertEquals(512, silero.windowSize)
        assertEquals(16_000, config.sampleRate)
        assertEquals(1, config.numThreads)
        assertEquals("cpu", config.provider)
    }

    @Test
    fun nemotronEndpoint_prioritizesResponsiveFinalsAfterDecodedSpeech() {
        val config = nemotronEndpointConfig()

        assertFalse(config.rule1.mustContainNonSilence)
        assertEquals(2.4f, config.rule1.minTrailingSilence, 0.0f)

        assertTrue(config.rule2.mustContainNonSilence)
        assertEquals(0.8f, config.rule2.minTrailingSilence, 0.0f)

        assertFalse(config.rule3.mustContainNonSilence)
        assertEquals(20.0f, config.rule3.minUtteranceLength, 0.0f)
    }
}
