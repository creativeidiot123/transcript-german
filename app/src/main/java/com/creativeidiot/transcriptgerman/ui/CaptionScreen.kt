package com.creativeidiot.transcriptgerman.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.creativeidiot.transcriptgerman.R
import com.creativeidiot.transcriptgerman.model.ModelInstallFailure
import com.creativeidiot.transcriptgerman.model.ModelInstallState
import com.creativeidiot.transcriptgerman.session.CaptionFailure
import com.creativeidiot.transcriptgerman.session.CaptionSessionStatus

@Composable
fun CaptionScreen(
    state: CaptionUiState,
    onDownloadModel: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onClearTranscript: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines = state.session.lines
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.size)
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
                    contentPadding = PaddingValues(bottom = 4.dp),
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
                                text = stringResource(R.string.screen_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                            )

                            ModelStatus(
                                model = state.model,
                                onDownloadModel = onDownloadModel,
                            )

                            HorizontalDivider()

                            SessionStatus(state)

                            Text(
                                text = stringResource(R.string.latency_note),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    if (lines.isEmpty()) {
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
                        ) { line ->
                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.bodyLarge,
                            )
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

@Composable
private fun ModelStatus(
    model: ModelInstallState,
    onDownloadModel: () -> Unit,
) {
    when (model) {
        ModelInstallState.Missing -> {
            Text(stringResource(R.string.model_missing))
            Button(
                onClick = onDownloadModel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.download_model))
            }
        }

        is ModelInstallState.Downloading -> {
            Text(
                stringResource(
                    R.string.model_downloading,
                    model.fileIndex,
                    model.totalFiles,
                ),
            )
            val progress = model.fileProgress
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ModelInstallState.Ready -> {
            Text(
                text = stringResource(R.string.model_ready),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        is ModelInstallState.Failed -> {
            Text(
                text = when (model.reason) {
                    ModelInstallFailure.DOWNLOAD_OR_STORAGE ->
                        stringResource(R.string.model_download_failed)

                    ModelInstallFailure.INTEGRITY ->
                        stringResource(R.string.model_integrity_failed)
                },
                color = MaterialTheme.colorScheme.error,
            )
            Button(
                onClick = onDownloadModel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.retry_download))
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
                CaptionFailure.AUDIO_UNAVAILABLE -> stringResource(R.string.error_audio_unavailable)
                CaptionFailure.ASR_INITIALIZATION -> stringResource(R.string.error_asr_init)
                CaptionFailure.AUDIO_BACKPRESSURE -> stringResource(R.string.error_backpressure)
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
    val modelReady = state.model == ModelInstallState.Ready

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = if (running) onStopListening else onStartListening,
            enabled = running || modelReady,
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
