package com.creativeidiot.transcriptgerman.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.creativeidiot.transcriptgerman.R
import com.creativeidiot.transcriptgerman.model.AsrBackend
import com.creativeidiot.transcriptgerman.model.ModelInstallState
import com.creativeidiot.transcriptgerman.session.CaptionFailure
import com.creativeidiot.transcriptgerman.session.CaptionLine
import com.creativeidiot.transcriptgerman.session.CaptionSessionState
import com.creativeidiot.transcriptgerman.session.CaptionSessionStatus

@Composable
fun CaptionScreen(
    state: CaptionUiState,
    onBackendSelected: (AsrBackend) -> Unit,
    onDownloadModel: () -> Unit,
    onDownloadTranslationModel: () -> Unit,
    onSaveGeminiApiKey: (String) -> Unit,
    onClearGeminiApiKey: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onClearTranscript: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines = state.session.lines
    val partialText = state.session.partialText
    val autoFollowTrigger = captionAutoFollowTrigger(state.session)
    val hasPartial = autoFollowTrigger.partialVisible
    val captionItemCount = lines.size + if (hasPartial) 1 else 0
    val listState = rememberLazyListState()
    var previousCaptionItemCount by remember { mutableStateOf(captionItemCount) }

    LaunchedEffect(autoFollowTrigger) {
        val newCaptionItems = (captionItemCount - previousCaptionItemCount).coerceAtLeast(0)
        previousCaptionItemCount = captionItemCount

        val targetIndex = captionItemCount
        if (targetIndex <= 0) return@LaunchedEffect

        val layoutInfo = listState.layoutInfo
        val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        val targetFullyVisible =
            targetItem != null &&
                targetItem.offset >= layoutInfo.viewportStartOffset &&
                targetItem.offset + targetItem.size <= layoutInfo.viewportEndOffset

        if (
            shouldAutoFollowLiveCaption(
                lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index,
                totalItemsCount = layoutInfo.totalItemsCount,
                targetIndex = targetIndex,
                targetFullyVisible = targetFullyVisible,
                newCaptionItems = newCaptionItems,
            )
        ) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.screen_title),
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            Text(
                                text = if (state.selectedBackend == AsrBackend.GEMINI) {
                                    stringResource(R.string.screen_subtitle_gemini)
                                } else {
                                    stringResource(R.string.screen_subtitle_local)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )

                            BackendConfiguration(
                                state = state,
                                onBackendSelected = onBackendSelected,
                                onDownloadModel = onDownloadModel,
                                onDownloadTranslationModel = onDownloadTranslationModel,
                                onSaveGeminiApiKey = onSaveGeminiApiKey,
                                onClearGeminiApiKey = onClearGeminiApiKey,
                            )

                            HorizontalDivider()

                            SessionStatus(state)

                            Text(
                                text = when (state.selectedBackend) {
                                    AsrBackend.PRIMELINE ->
                                        stringResource(R.string.latency_note_primeline)

                                    AsrBackend.NEMOTRON ->
                                        stringResource(R.string.latency_note_nemotron)

                                    AsrBackend.CANARY ->
                                        stringResource(R.string.latency_note_canary)

                                    AsrBackend.GEMINI ->
                                        stringResource(R.string.latency_note_gemini)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    if (lines.isEmpty() && !hasPartial) {
                        item {
                            Text(
                                text = stringResource(R.string.empty_transcript),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        items(
                            items = lines,
                            key = { it.id },
                            contentType = { "caption-pair" },
                        ) { line ->
                            CaptionPair(
                                line = line,
                                translationPending =
                                    state.session.status != CaptionSessionStatus.IDLE &&
                                        state.session.failure == null,
                            )
                        }

                        if (hasPartial) {
                            item(
                                key = "partial-caption",
                                contentType = "caption-pair",
                            ) {
                                CaptionPair(
                                    german = partialText,
                                    english = state.session.partialEnglishText,
                                    pending = true,
                                    isLive = true,
                                )
                            }
                        }
                    }
                }

                Controls(
                    state = state,
                    onStartListening = onStartListening,
                    onStopListening = onStopListening,
                    onClearTranscript = onClearTranscript,
                )
            }
        }
    }
}

internal data class CaptionAutoFollowTrigger(
    val latestFinalId: Long?,
    val partialVisible: Boolean,
)

internal fun captionAutoFollowTrigger(session: CaptionSessionState): CaptionAutoFollowTrigger =
    CaptionAutoFollowTrigger(
        latestFinalId = session.lines.lastOrNull()?.id,
        partialVisible = session.partialText.isNotBlank(),
    )

internal fun shouldAutoFollowLiveCaption(
    lastVisibleItemIndex: Int?,
    totalItemsCount: Int,
    targetIndex: Int,
    targetFullyVisible: Boolean,
    newCaptionItems: Int,
): Boolean {
    if (targetIndex <= 0 || targetFullyVisible) return false
    if (totalItemsCount <= 0 || lastVisibleItemIndex == null) return true

    val allowedUnseenItems = newCaptionItems.coerceAtLeast(0)
    val liveEdgeIndex = (totalItemsCount - 1 - allowedUnseenItems).coerceAtLeast(0)
    return lastVisibleItemIndex >= liveEdgeIndex
}

@Composable
private fun CaptionPair(
    line: CaptionLine,
    translationPending: Boolean,
) {
    CaptionPair(
        german = line.text,
        english = line.englishText,
        pending = line.englishText == null && translationPending,
        isLive = false,
    )
}

@Composable
private fun CaptionPair(
    german: String,
    english: String?,
    pending: Boolean,
    isLive: Boolean,
) {
    val contentColor =
        if (isLive) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.large,
        color = if (isLive) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.caption_language_german),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = german,
                    style = MaterialTheme.typography.titleMedium,
                    minLines = 2,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.caption_language_english),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = english ?: if (pending) {
                        stringResource(R.string.translation_pending)
                    } else {
                        stringResource(R.string.translation_unavailable)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    minLines = 2,
                )
            }
        }
    }
}

@Composable
private fun SessionStatus(state: CaptionUiState) {
    Text(
        text = when (state.session.status) {
            CaptionSessionStatus.IDLE -> stringResource(R.string.status_idle)
            CaptionSessionStatus.STARTING -> stringResource(R.string.status_starting)
            CaptionSessionStatus.LISTENING -> stringResource(R.string.status_listening)
            CaptionSessionStatus.SPEECH_DETECTED -> stringResource(R.string.status_speech)
            CaptionSessionStatus.TRANSCRIBING -> stringResource(R.string.status_transcribing)
            CaptionSessionStatus.STOPPING -> stringResource(R.string.status_stopping)
        },
        style = MaterialTheme.typography.titleMedium,
    )

    if (state.microphonePermissionDenied) {
        ErrorText(stringResource(R.string.permission_denied))
    }

    state.session.failure?.let { failure ->
        ErrorText(
            when (failure) {
                CaptionFailure.MODEL_NOT_READY -> stringResource(R.string.error_model_not_ready)
                CaptionFailure.TRANSLATION_MODEL_NOT_READY ->
                    stringResource(R.string.error_translation_model_not_ready)
                CaptionFailure.AUDIO_UNAVAILABLE -> stringResource(R.string.error_audio_unavailable)
                CaptionFailure.ASR_INITIALIZATION -> stringResource(R.string.error_asr_init)
                CaptionFailure.TRANSLATION_INITIALIZATION ->
                    stringResource(R.string.error_translation_init)
                CaptionFailure.GEMINI_API_KEY_NOT_CONFIGURED ->
                    stringResource(R.string.error_gemini_key_missing)
                CaptionFailure.GEMINI_AUTHENTICATION ->
                    stringResource(R.string.error_gemini_authentication)
                CaptionFailure.GEMINI_CONNECTION ->
                    stringResource(R.string.error_gemini_connection)
                CaptionFailure.AUDIO_BACKPRESSURE -> stringResource(R.string.error_backpressure)
                CaptionFailure.TRANSLATION -> stringResource(R.string.error_translation)
                CaptionFailure.UNEXPECTED -> stringResource(R.string.error_unexpected)
            },
        )
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun Controls(
    state: CaptionUiState,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onClearTranscript: () -> Unit,
) {
    val running = state.session.status != CaptionSessionStatus.IDLE
    val recognitionReady =
        if (state.selectedBackend == AsrBackend.GEMINI) {
            isGeminiStartAllowed(
                configured = state.geminiApiKeyConfigured,
                mutationInProgress = state.geminiApiKeyMutationInProgress,
            )
        } else {
            state.model == ModelInstallState.Ready
        }
    val modelsReady =
        recognitionReady &&
            state.translationModel == ModelInstallState.Ready

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = if (running) onStopListening else onStartListening,
            enabled = running || modelsReady,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (running) {
                    stringResource(R.string.stop_listening)
                } else {
                    stringResource(R.string.start_listening)
                },
            )
        }

        OutlinedButton(
            onClick = onClearTranscript,
            enabled = state.session.lines.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.clear_transcript))
        }
    }
}
