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
    val isAnyModelDownloading: Boolean,
    val session: CaptionSessionState,
    val microphonePermissionDenied: Boolean,
)

class CaptionViewModel(
    private val modelRepository: ModelRepository,
    private val sessionStore: CaptionSessionStore,
) : ViewModel() {
    private val selectedBackend = MutableStateFlow(AsrBackend.PRIMELINE)
    private val microphonePermissionDenied = MutableStateFlow(false)
    private var downloadJob: Job? = null

    val state: StateFlow<CaptionUiState> = combine(
        modelRepository.states,
        sessionStore.state,
        selectedBackend,
        microphonePermissionDenied,
    ) { modelStates, session, backend, permissionDenied ->
        CaptionUiState(
            selectedBackend = backend,
            model = modelStates.getValue(backend),
            isAnyModelDownloading = modelStates.values.any {
                it is ModelInstallState.Downloading
            },
            session = session,
            microphonePermissionDenied = permissionDenied,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CaptionUiState(
            selectedBackend = selectedBackend.value,
            model = modelRepository.stateFor(selectedBackend.value),
            isAnyModelDownloading = modelRepository.states.value.values.any {
                it is ModelInstallState.Downloading
            },
            session = sessionStore.state.value,
            microphonePermissionDenied = false,
        ),
    )

    fun selectBackend(backend: AsrBackend) {
        if (sessionStore.state.value.status != CaptionSessionStatus.IDLE) return
        if (modelRepository.states.value.values.any { it is ModelInstallState.Downloading }) return
        selectedBackend.value = backend
    }

    fun selectedBackendForStart(): AsrBackend = selectedBackend.value

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

    fun prepareMicrophoneRequest() {
        microphonePermissionDenied.value = false
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        microphonePermissionDenied.value = !granted
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
