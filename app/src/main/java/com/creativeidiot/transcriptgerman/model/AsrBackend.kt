package com.creativeidiot.transcriptgerman.model

enum class AsrBackend(
    val wireValue: String,
    val requiresLocalModel: Boolean,
) {
    PRIMELINE("primeline", true),
    NEMOTRON("nemotron", true),
    CANARY("canary", true),
    GEMINI("gemini", false);

    companion object {
        fun fromWireValue(value: String?): AsrBackend? =
            entries.firstOrNull { it.wireValue == value }
    }
}

internal val DEFAULT_ASR_BACKEND = AsrBackend.NEMOTRON
