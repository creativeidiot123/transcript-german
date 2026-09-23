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
    AUDIO_UNAVAILABLE,
    ASR_INITIALIZATION,
    AUDIO_BACKPRESSURE,
    UNEXPECTED,
}

data class CaptionSessionState(
    val status: CaptionSessionStatus = CaptionSessionStatus.IDLE,
    val lines: List<CaptionLine> = emptyList(),
    val partialText: String = "",
    val activeBackend: AsrBackend? = null,
    val failure: CaptionFailure? = null,
)

class CaptionSessionStore {
    private val nextId = AtomicLong(0)
    private val _state = MutableStateFlow(CaptionSessionState())

    val state: StateFlow<CaptionSessionState> = _state.asStateFlow()

    fun markStarting(backend: AsrBackend) {
        _state.update {
            it.copy(
                status = CaptionSessionStatus.STARTING,
                partialText = "",
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
                current.copy(
                    status = if (current.status == CaptionSessionStatus.STOPPING) {
                        CaptionSessionStatus.STOPPING
                    } else if (trimmed.isNotEmpty()) {
                        CaptionSessionStatus.SPEECH_DETECTED
                    } else {
                        CaptionSessionStatus.LISTENING
                    },
                    partialText = trimmed,
                )
            }
        }
    }

    fun appendFinal(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val line = CaptionLine(nextId.getAndIncrement(), trimmed)
        _state.update { current ->
            current.copy(
                status = if (current.status == CaptionSessionStatus.STOPPING) {
                    CaptionSessionStatus.STOPPING
                } else {
                    CaptionSessionStatus.LISTENING
                },
                lines = (current.lines + line).takeLast(MAX_LINES),
                partialText = "",
            )
        }
    }

    fun markFailure(failure: CaptionFailure) {
        _state.update {
            it.copy(
                status = CaptionSessionStatus.STOPPING,
                partialText = "",
                failure = failure,
            )
        }
    }

    fun markStopped(clearFailure: Boolean) {
        _state.update {
            it.copy(
                status = CaptionSessionStatus.IDLE,
                partialText = "",
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
