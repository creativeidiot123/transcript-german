package com.creativeidiot.transcriptgerman.gemini

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveProtocolTest {
    @Test
    fun setup_requestsGermanTextTranscription() {
        val root = JSONObject(GeminiLiveProtocol.setupMessage())
        val setup = root.getJSONObject("setup")

        assertEquals(
            "models/gemini-3.5-transcribe-live",
            setup.getString("model"),
        )
        assertEquals(
            "TEXT",
            setup.getJSONObject("generationConfig")
                .getJSONArray("responseModalities")
                .getString(0),
        )
        assertEquals(
            "de-DE",
            setup.getJSONObject("inputAudioTranscription")
                .getJSONArray("languageCodes")
                .getString(0),
        )
    }

    @Test
    fun audioMessage_encodesLittleEndianPcm16At16Khz() {
        val root = JSONObject(
            GeminiLiveProtocol.audioMessage(
                floatArrayOf(-1f, 0f, 1f),
            ),
        )
        val audio = root
            .getJSONObject("realtimeInput")
            .getJSONObject("audio")

        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))

        val bytes = Base64.getDecoder().decode(audio.getString("data"))
        val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-32_767, pcm.short.toInt())
        assertEquals(0, pcm.short.toInt())
        assertEquals(32_767, pcm.short.toInt())
    }

    @Test
    fun serverMessages_distinguishSetupInterimAndFinalTranscripts() {
        val setup = GeminiLiveProtocol.parseServerMessage(
            """{"setupComplete":{}}""",
        )
        assertTrue(setup.setupComplete)

        val interim = GeminiLiveProtocol.parseServerMessage(
            """{"serverContent":{"interimInputTranscription":{"text":"Guten"}}}""",
        )
        assertEquals("Guten", interim.interimText)
        assertEquals(null, interim.finalText)

        val final = GeminiLiveProtocol.parseServerMessage(
            """{"serverContent":{"inputTranscription":{"text":"Guten Morgen"}}}""",
        )
        assertFalse(final.setupComplete)
        assertEquals(null, final.interimText)
        assertEquals("Guten Morgen", final.finalText)
    }
}
