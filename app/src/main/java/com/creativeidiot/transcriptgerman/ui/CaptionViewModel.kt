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
import com.creativeidiot.transcriptgerman.translation.BergamotModelRepository
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
    val translationModel: ModelInstallState,
    val isAnyModelDownloading: Boolean,
    val session: CaptionSessionState,
    val microphonePermissionDenied: Boolean,
)

class CaptionViewModel(
    private val modelRepository: ModelRepository,
    private val bergamotModelRepository: BergamotModelRepository,
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
        bergamotModelRepository.state,
        sessionStore.state,
        selectedBackend,
        microphonePermissionDenied,
    ) { modelStates, translationModel, session, selected, permissionDenied ->
        val effectiveBackend = session.activeBackend ?: selected
        CaptionUiState(
            selectedBackend = effectiveBackend,
            model = modelStates.getValue(effectiveBackend),
            translationModel = translationModel,
            isAnyModelDownloading =
                modelStates.values.any { it is ModelInstallState.Downloading } ||
                    translationModel is ModelInstallState.Downloading,
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
        if (isAnyModelDownloading()) return
        selectedBackend.value = backend
    }

    fun downloadModel() {
        if (downloadJob?.isActive == true) return

        val backend = selectedBackend.value
        downloadJob = viewModelScope.launch {
            modelRepository.download(backend)
        }
    }

    fun downloadTranslationModel() {
        if (downloadJob?.isActive == true) return

        downloadJob = viewModelScope.launch {
            bergamotModelRepository.download()
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

    fun consumePreparedStart() {
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
        val translationModel = bergamotModelRepository.state.value
        return CaptionUiState(
            selectedBackend = backend,
            model = modelStates.getValue(backend),
            translationModel = translationModel,
            isAnyModelDownloading =
                modelStates.values.any { it is ModelInstallState.Downloading } ||
                    translationModel is ModelInstallState.Downloading,
            session = session,
            microphonePermissionDenied = false,
        )
    }

    private fun isAnyModelDownloading(): Boolean =
        modelRepository.states.value.values.any { it is ModelInstallState.Downloading } ||
            bergamotModelRepository.state.value is ModelInstallState.Downloading

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(CaptionViewModel::class.java)) {
                return CaptionViewModel(
                    modelRepository = container.modelRepository,
                    bergamotModelRepository = container.bergamotModelRepository,
                    sessionStore = container.sessionStore,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
