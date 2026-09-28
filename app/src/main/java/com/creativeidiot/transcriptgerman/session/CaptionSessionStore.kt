package com.creativeidiot.transcriptgerman.session

import com.creativeidiot.transcriptgerman.model.AsrBackend
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CaptionLine(
    val id: Long,
    val text: String,
    val englishText: String? = null,
    // Last live-partial English carried over at finalization; shown only until englishText lands.
    val previewEnglishText: String? = null,
)

enum class CaptionSessionStatus {
    IDLE,
    STARTING,
    LISTENING,
    SPEECH_DETECTED,
    TRANSCRIBING,
    STOPPING,
}

enum class CaptionFailure {
    MODEL_NOT_READY,
    TRANSLATION_MODEL_NOT_READY,
    AUDIO_UNAVAILABLE,
    ASR_INITIALIZATION,
    TRANSLATION_INITIALIZATION,
    GEMINI_API_KEY_NOT_CONFIGURED,
    GEMINI_AUTHENTICATION,
    GEMINI_CONNECTION,
    AUDIO_BACKPRESSURE,
    TRANSLATION,
    UNEXPECTED,
}

data class CaptionSessionState(
    val status: CaptionSessionStatus = CaptionSessionStatus.IDLE,
    val lines: List<CaptionLine> = emptyList(),
    val partialText: String = "",
    val partialEnglishText: String? = null,
    val partialEnglishSource: String? = null,
    val activeBackend: AsrBackend? = null,
    val failure: CaptionFailure? = null,
) {
    // Older English stays visible while a newer German hypothesis is translated, but only
    // counts as the partial's translation once its source matches the current German text.
    val partialEnglishCurrent: Boolean
        get() = partialEnglishText != null && partialEnglishSource == partialText
}

class CaptionSessionStore {
    private val nextId = AtomicLong(0)
    private val _state = MutableStateFlow(CaptionSessionState())

    val state: StateFlow<CaptionSessionState> = _state.asStateFlow()

    fun markStarting(backend: AsrBackend) {
        _state.update {
            it.copy(
                status = CaptionSessionStatus.STARTING,
                partialText = "",
                partialEnglishText = null,
                partialEnglishSource = null,
                activeBackend = backend,
                failure = null,
            )
        }
    }

    fun markListening() {
        _state.update { current ->
            if (current.status == CaptionSessionStatus.STOPPING) {
                current
            } else {
                current.copy(status = CaptionSessionStatus.LISTENING)
            }
        }
    }

    fun markStopping() {
        _state.update { it.copy(status = CaptionSessionStatus.STOPPING) }
    }

    fun markSpeechDetected(detected: Boolean) {
        _state.update { current ->
            if (current.status == CaptionSessionStatus.STOPPING) {
                current
            } else {
                current.copy(
                    status = if (detected) {
                        CaptionSessionStatus.SPEECH_DETECTED
                    } else {
                        CaptionSessionStatus.LISTENING
                    },
                )
            }
        }
    }

    fun markTranscribing(transcribing: Boolean) {
        _state.update { current ->
            if (current.status == CaptionSessionStatus.STOPPING) {
                current
            } else {
                current.copy(
                    status = if (transcribing) {
                        CaptionSessionStatus.TRANSCRIBING
                    } else {
                        CaptionSessionStatus.LISTENING
                    },
                )
            }
        }
    }

    fun updatePartial(text: String) {
        val trimmed = text.trim()
        _state.update { current ->
            if (current.partialText == trimmed) {
                current
            } else {
                // Keep the last English (now stale) so the card does not flash back to pending.
                val keepEnglish = trimmed.isNotEmpty()
                current.copy(
                    status = if (current.status == CaptionSessionStatus.STOPPING) {
                        CaptionSessionStatus.STOPPING
                    } else if (trimmed.isNotEmpty()) {
                        CaptionSessionStatus.SPEECH_DETECTED
                    } else {
                        CaptionSessionStatus.LISTENING
                    },
                    partialText = trimmed,
                    partialEnglishText = current.partialEnglishText.takeIf { keepEnglish },
                    partialEnglishSource = current.partialEnglishSource.takeIf { keepEnglish },
                )
            }
        }
    }

    fun updatePartialTranslation(
        sourceGerman: String,
        english: String,
    ) {
        val source = sourceGerman.trim()
        val translated = english.trim()
        if (source.isEmpty() || translated.isEmpty()) return

        _state.update { current ->
            if (current.partialText != source) {
                current
            } else {
                current.copy(
                    partialEnglishText = translated,
                    partialEnglishSource = source,
                )
            }
        }
    }

    fun appendFinal(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val lineId = nextId.getAndIncrement()
        _state.update { current ->
            val line = CaptionLine(
                id = lineId,
                text = trimmed,
                previewEnglishText = current.partialEnglishText,
            )
            current.copy(
                status = if (current.status == CaptionSessionStatus.STOPPING) {
                    CaptionSessionStatus.STOPPING
                } else {
                    CaptionSessionStatus.LISTENING
                },
                lines = (current.lines + line).takeLast(MAX_LINES),
                partialText = "",
                partialEnglishText = null,
                partialEnglishSource = null,
            )
        }
        return lineId
    }

    fun updateFinalTranslation(
        lineId: Long,
        english: String,
    ) {
        val translated = english.trim()
        if (translated.isEmpty()) return

        _state.update { current ->
            val index = current.lines.indexOfFirst { it.id == lineId }
            if (index < 0) {
                current
            } else {
                val updated = current.lines.toMutableList()
                updated[index] = updated[index].copy(
                    englishText = translated,
                    previewEnglishText = null,
                )
                current.copy(lines = updated)
            }
        }
    }

    fun markFailure(failure: CaptionFailure) {
        _state.update {
            it.copy(
                status = CaptionSessionStatus.STOPPING,
                partialText = "",
                partialEnglishText = null,
                partialEnglishSource = null,
                failure = failure,
            )
        }
    }

    fun markStopped(clearFailure: Boolean) {
        _state.update {
            it.copy(
                status = CaptionSessionStatus.IDLE,
                partialText = "",
                partialEnglishText = null,
                partialEnglishSource = null,
                activeBackend = null,
                failure = if (clearFailure) null else it.failure,
            )
        }
    }

    fun clearTranscript() {
        _state.update { it.copy(lines = emptyList()) }
    }

    private companion object {
        const val MAX_LINES = 200
    }
}
