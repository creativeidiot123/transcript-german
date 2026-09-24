package com.creativeidiot.transcriptgerman.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.creativeidiot.transcriptgerman.AppContainer
import com.creativeidiot.transcriptgerman.gemini.GeminiApiKeyStore
import com.creativeidiot.transcriptgerman.model.AsrBackend
import com.creativeidiot.transcriptgerman.model.ModelInstallState
import com.creativeidiot.transcriptgerman.model.ModelRepository
import com.creativeidiot.transcriptgerman.session.CaptionSessionState
import com.creativeidiot.transcriptgerman.session.CaptionSessionStatus
import com.creativeidiot.transcriptgerman.session.CaptionSessionStore
import com.creativeidiot.transcriptgerman.translation.BergamotModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CaptionUiState(
    val selectedBackend: AsrBackend,
    val model: ModelInstallState?,
    val translationModel: ModelInstallState,
    val isAnyModelDownloading: Boolean,
    val geminiApiKeyConfigured: Boolean,
    val geminiApiKeyMutationInProgress: Boolean,
    val geminiApiKeyStorageError: Boolean,
    val session: CaptionSessionState,
    val microphonePermissionDenied: Boolean,
)

private data class ModelReadiness(
    val modelStates: Map<AsrBackend, ModelInstallState>,
    val translationModel: ModelInstallState,
    val geminiApiKeyConfigured: Boolean,
    val geminiApiKeyMutationInProgress: Boolean,
)

class CaptionViewModel(
    private val modelRepository: ModelRepository,
    private val bergamotModelRepository: BergamotModelRepository,
    private val geminiApiKeyStore: GeminiApiKeyStore,
    private val sessionStore: CaptionSessionStore,
) : ViewModel() {
    private val selectedBackend = MutableStateFlow(
        sessionStore.state.value.activeBackend ?: AsrBackend.PRIMELINE,
    )
    private val microphonePermissionDenied = MutableStateFlow(false)
    private val geminiApiKeyStorageError = MutableStateFlow(false)
    private val geminiApiKeyMutationInProgress = MutableStateFlow(false)
    private var pendingStartBackend: AsrBackend? = null
    private var downloadJob: Job? = null
    private var geminiKeyJob: Job? = null

    private val modelReadiness = combine(
        modelRepository.states,
        bergamotModelRepository.state,
        geminiApiKeyStore.isConfigured,
        geminiApiKeyMutationInProgress,
    ) { modelStates, translationModel, geminiApiKeyConfigured, keyMutationInProgress ->
        ModelReadiness(
            modelStates = modelStates,
            translationModel = translationModel,
            geminiApiKeyConfigured = geminiApiKeyConfigured,
            geminiApiKeyMutationInProgress = keyMutationInProgress,
        )
    }

    val state: StateFlow<CaptionUiState> = combine(
        modelReadiness,
        sessionStore.state,
        selectedBackend,
        microphonePermissionDenied,
        geminiApiKeyStorageError,
    ) { readiness, session, selected, permissionDenied, keyStorageError ->
        val effectiveBackend = session.activeBackend ?: selected
        CaptionUiState(
            selectedBackend = effectiveBackend,
            model = readiness.modelStates[effectiveBackend],
            translationModel = readiness.translationModel,
            isAnyModelDownloading =
                readiness.modelStates.values.any { it is ModelInstallState.Downloading } ||
                    readiness.translationModel is ModelInstallState.Downloading,
            geminiApiKeyConfigured = readiness.geminiApiKeyConfigured,
            geminiApiKeyMutationInProgress = readiness.geminiApiKeyMutationInProgress,
            geminiApiKeyStorageError = keyStorageError,
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
        geminiApiKeyStorageError.value = false
    }

    fun downloadModel() {
        if (downloadJob?.isActive == true) return

        val backend = selectedBackend.value
        if (!backend.requiresLocalModel) return

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

    fun saveGeminiApiKey(value: String) {
        if (sessionStore.state.value.status != CaptionSessionStatus.IDLE) return
        if (geminiKeyJob?.isActive == true) return
        val apiKey = value.trim()
        if (apiKey.isEmpty()) return

        geminiApiKeyStorageError.value = false
        geminiApiKeyMutationInProgress.value = true
        geminiKeyJob = viewModelScope.launch {
            try {
                geminiApiKeyStore.saveApiKey(apiKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                geminiApiKeyStorageError.value = true
            } finally {
                geminiApiKeyMutationInProgress.value = false
            }
        }
    }

    fun clearGeminiApiKey() {
        if (sessionStore.state.value.status != CaptionSessionStatus.IDLE) return
        if (geminiKeyJob?.isActive == true) return

        geminiApiKeyStorageError.value = false
        geminiApiKeyMutationInProgress.value = true
        geminiKeyJob = viewModelScope.launch {
            try {
                geminiApiKeyStore.clearApiKey()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                geminiApiKeyStorageError.value = true
            } finally {
                geminiApiKeyMutationInProgress.value = false
            }
        }
    }

    fun clearTranscript() {
        sessionStore.clearTranscript()
    }

    fun prepareMicrophoneRequest(): AsrBackend? {
        microphonePermissionDenied.value = false
        val backend = selectedBackend.value

        if (
            backend == AsrBackend.GEMINI &&
            !isGeminiStartAllowed(
                configured = geminiApiKeyStore.isConfigured.value,
                mutationInProgress = geminiApiKeyMutationInProgress.value,
            )
        ) {
            pendingStartBackend = null
            return null
        }

        pendingStartBackend = backend
        return backend
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
            model = modelStates[backend],
            translationModel = translationModel,
            isAnyModelDownloading =
                modelStates.values.any { it is ModelInstallState.Downloading } ||
                    translationModel is ModelInstallState.Downloading,
            geminiApiKeyConfigured = geminiApiKeyStore.isConfigured.value,
            geminiApiKeyMutationInProgress = geminiApiKeyMutationInProgress.value,
            geminiApiKeyStorageError = false,
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
                    geminiApiKeyStore = container.geminiApiKeyStore,
                    sessionStore = container.sessionStore,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}


internal fun isGeminiStartAllowed(
    configured: Boolean,
    mutationInProgress: Boolean,
): Boolean = configured && !mutationInProgress
