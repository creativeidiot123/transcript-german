package com.creativeidiot.transcriptgerman.model

enum class AsrBackend(val wireValue: String) {
    PRIMELINE("primeline"),
    NEMOTRON("nemotron");

    companion object {
        fun fromWireValue(value: String?): AsrBackend? =
            entries.firstOrNull { it.wireValue == value }
    }
}
