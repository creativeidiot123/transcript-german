package com.creativeidiot.transcriptgerman.gemini

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.roundToInt
import org.json.JSONObject

internal data class GeminiLiveEvent(
    val setupComplete: Boolean = false,
    val interimText: String? = null,
    val finalText: String? = null,
)

internal object GeminiLiveProtocol {
    const val MODEL_NAME = "gemini-3.5-transcribe-live"
    const val ENDPOINT =
        "https://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    fun setupMessage(): String =
        JSONObject()
            .put(
                "setup",
                JSONObject()
                    .put("model", "models/$MODEL_NAME")
                    .put(
                        "generationConfig",
                        JSONObject().put(
                            "responseModalities",
                            listOf("TEXT"),
                        ),
                    )
                    .put(
                        "inputAudioTranscription",
                        JSONObject().put(
                            "languageCodes",
                            listOf("de-DE"),
                        ),
                    ),
            )
            .toString()

    fun audioMessage(samples: FloatArray): String {
        val pcm = ByteBuffer
            .allocate(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)

        samples.forEach { sample ->
            val pcm16 = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm.putShort(pcm16.toShort())
        }

        return JSONObject()
            .put(
                "realtimeInput",
                JSONObject().put(
                    "audio",
                    JSONObject()
                        .put(
                            "data",
                            Base64.getEncoder().encodeToString(pcm.array()),
                        )
                        .put("mimeType", "audio/pcm;rate=16000"),
                ),
            )
            .toString()
    }

    fun audioStreamEndMessage(): String =
        JSONObject()
            .put(
                "realtimeInput",
                JSONObject().put("audioStreamEnd", true),
            )
            .toString()

    fun parseServerMessage(message: String): GeminiLiveEvent {
        val root = JSONObject(message)
        val serverContent = root.optJSONObject("serverContent")

        return GeminiLiveEvent(
            setupComplete = root.has("setupComplete"),
            interimText = serverContent
                ?.optJSONObject("interimInputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotEmpty() },
            finalText = serverContent
                ?.optJSONObject("inputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotEmpty() },
        )
    }
}
