package com.creativeidiot.transcriptgerman.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.creativeidiot.transcriptgerman.AppContainer
import com.creativeidiot.transcriptgerman.model.AsrBackend
import com.creativeidiot.transcriptgerman.model.ModelInstallState
import com.creativeidiot.transcriptgerman.model.ModelRepository
import com.creativeidiot.transcriptgerman.session.CaptionSessionState
import com.creativeidiot.transcriptgerman.session.CaptionSessionStatus
import com.creativeidiot.transcriptgerman.session.CaptionSessionStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CaptionUiState(
    val selectedBackend: AsrBackend,
    val model: ModelInstallState,
    val anyModelDownloading: Boolean,
    val session: CaptionSessionState,
    val microphonePermissionDenied: Boolean,
)

class CaptionViewModel(
    private val modelRepository: ModelRepository,
    private val sessionStore: CaptionSessionStore,
) : ViewModel() {
    private val selectedBackend = MutableStateFlow(
        sessionStore.state.value.activeBackend ?: AsrBackend.PRIMELINE,
    )
    private val microphonePermissionDenied = MutableStateFlow(false)

    private var pendingStartBackend: AsrBackend? = null
    private var downloadJob: Job? = null

    val state: StateFlow<CaptionUiState> = combine(
        modelRepository.states,
        sessionStore.state,
        selectedBackend,
        microphonePermissionDenied,
    ) { modelStates, session, selected, permissionDenied ->
        val effectiveBackend = session.activeBackend ?: selected
        CaptionUiState(
            selectedBackend = effectiveBackend,
            model = modelStates.getValue(effectiveBackend),
            anyModelDownloading = modelStates.values.any {
                it is ModelInstallState.Downloading
            },
            session = session,
            microphonePermissionDenied = permissionDenied,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = initialState(),
    )

    fun selectBackend(backend: AsrBackend) {
        if (sessionStore.state.value.status != CaptionSessionStatus.IDLE) return
        if (modelRepository.states.value.values.any { it is ModelInstallState.Downloading }) return

        selectedBackend.value = backend
        microphonePermissionDenied.value = false
    }

    fun downloadModel() {
        if (downloadJob?.isActive == true) return

        val backend = selectedBackend.value
        downloadJob = viewModelScope.launch {
            modelRepository.download(backend)
        }
    }

    fun clearTranscript() {
        sessionStore.clearTranscript()
    }

    fun prepareMicrophoneRequest(): AsrBackend {
        microphonePermissionDenied.value = false
        return selectedBackend.value.also { backend ->
            pendingStartBackend = backend
        }
    }

    fun consumePreparedStart(): AsrBackend? =
        pendingStartBackend.also {
            pendingStartBackend = null
        }

    fun onMicrophonePermissionResult(granted: Boolean): AsrBackend? {
        microphonePermissionDenied.value = !granted
        val backend = pendingStartBackend
        pendingStartBackend = null
        return backend.takeIf { granted }
    }

    private fun initialState(): CaptionUiState {
        val session = sessionStore.state.value
        val backend = session.activeBackend ?: selectedBackend.value
        val modelStates = modelRepository.states.value
        return CaptionUiState(
            selectedBackend = backend,
            model = modelStates.getValue(backend),
            anyModelDownloading = modelStates.values.any {
                it is ModelInstallState.Downloading
            },
            session = session,
            microphonePermissionDenied = false,
        )
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(CaptionViewModel::class.java)) {
                return CaptionViewModel(
                    modelRepository = container.modelRepository,
                    sessionStore = container.sessionStore,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
