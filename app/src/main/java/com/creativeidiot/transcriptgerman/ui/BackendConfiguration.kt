package com.creativeidiot.transcriptgerman.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.creativeidiot.transcriptgerman.R
import com.creativeidiot.transcriptgerman.model.AsrBackend
import com.creativeidiot.transcriptgerman.model.ModelInstallFailure
import com.creativeidiot.transcriptgerman.model.ModelInstallState
import com.creativeidiot.transcriptgerman.session.CaptionSessionStatus

@Composable
internal fun BackendConfiguration(
    state: CaptionUiState,
    onBackendSelected: (AsrBackend) -> Unit,
    onDownloadModel: () -> Unit,
    onDownloadTranslationModel: () -> Unit,
    onSaveGeminiApiKey: (String) -> Unit,
    onClearGeminiApiKey: () -> Unit,
) {
    BackendPicker(
        selected = state.selectedBackend,
        enabled = state.session.status == CaptionSessionStatus.IDLE &&
            !state.isAnyModelDownloading,
        onSelected = onBackendSelected,
    )

    if (state.selectedBackend == AsrBackend.GEMINI) {
        GeminiCredentialStatus(
            configured = state.geminiApiKeyConfigured,
            storageError = state.geminiApiKeyStorageError,
            enabled = state.session.status == CaptionSessionStatus.IDLE &&
                !state.geminiApiKeyMutationInProgress,
            onSave = onSaveGeminiApiKey,
            onClear = onClearGeminiApiKey,
        )
    } else {
        Text(
            text = stringResource(R.string.recognition_model_label),
            style = MaterialTheme.typography.titleSmall,
        )
        ModelStatus(
            backend = state.selectedBackend,
            model = requireNotNull(state.model),
            downloadEnabled = !state.isAnyModelDownloading,
            onDownloadModel = onDownloadModel,
        )
    }

    Text(
        text = stringResource(R.string.translation_model_label),
        style = MaterialTheme.typography.titleSmall,
    )
    TranslationModelStatus(
        model = state.translationModel,
        downloadEnabled = !state.isAnyModelDownloading,
        onDownloadModel = onDownloadTranslationModel,
    )
}

@Composable
private fun GeminiCredentialStatus(
    configured: Boolean,
    storageError: Boolean,
    enabled: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.gemini_api_key_label),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = if (configured) {
                stringResource(R.string.gemini_api_key_saved)
            } else {
                stringResource(R.string.gemini_api_key_missing)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.gemini_api_key_field)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    onSave(apiKey)
                    apiKey = ""
                },
                enabled = enabled && apiKey.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.gemini_api_key_save))
            }
            OutlinedButton(
                onClick = onClear,
                enabled = enabled && configured,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.gemini_api_key_clear))
            }
        }
        Text(
            text = stringResource(R.string.gemini_api_key_storage_note),
            style = MaterialTheme.typography.bodySmall,
        )
        if (storageError) {
            Text(
                text = stringResource(R.string.gemini_api_key_storage_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun BackendPicker(
    selected: AsrBackend,
    enabled: Boolean,
    onSelected: (AsrBackend) -> Unit,
) {
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.backend_picker_label),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackendButton(
                backend = AsrBackend.PRIMELINE,
                selected = selected == AsrBackend.PRIMELINE,
                enabled = enabled,
                onSelected = onSelected,
                modifier = Modifier.weight(1f),
            )
            BackendButton(
                backend = AsrBackend.NEMOTRON,
                selected = selected == AsrBackend.NEMOTRON,
                enabled = enabled,
                onSelected = onSelected,
                modifier = Modifier.weight(1f),
            )
        }
        BackendButton(
            backend = AsrBackend.GEMINI,
            selected = selected == AsrBackend.GEMINI,
            enabled = enabled,
            onSelected = onSelected,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BackendButton(
    backend: AsrBackend,
    selected: Boolean,
    enabled: Boolean,
    onSelected: (AsrBackend) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (backend) {
        AsrBackend.PRIMELINE -> stringResource(R.string.backend_primeline)
        AsrBackend.NEMOTRON -> stringResource(R.string.backend_nemotron)
        AsrBackend.GEMINI -> stringResource(R.string.backend_gemini)
    }

    val selectionSemantics = modifier.semantics {
        this.selected = selected
        role = Role.RadioButton
    }

    if (selected) {
        Button(
            onClick = { onSelected(backend) },
            enabled = enabled,
            modifier = selectionSemantics,
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = { onSelected(backend) },
            enabled = enabled,
            modifier = selectionSemantics,
        ) {
            Text(label)
        }
    }
}

@Composable
private fun ModelStatus(
    backend: AsrBackend,
    model: ModelInstallState,
    downloadEnabled: Boolean,
    onDownloadModel: () -> Unit,
) {
    InstallStatus(
        model = model,
        downloadEnabled = downloadEnabled,
        downloadLabel = when (backend) {
            AsrBackend.PRIMELINE -> stringResource(R.string.download_primeline_model)
            AsrBackend.NEMOTRON -> stringResource(R.string.download_nemotron_model)
            AsrBackend.GEMINI -> error("Gemini does not use a local model download")
        },
        onDownloadModel = onDownloadModel,
    )
}

@Composable
private fun TranslationModelStatus(
    model: ModelInstallState,
    downloadEnabled: Boolean,
    onDownloadModel: () -> Unit,
) {
    InstallStatus(
        model = model,
        downloadEnabled = downloadEnabled,
        downloadLabel = stringResource(R.string.download_bergamot_model),
        onDownloadModel = onDownloadModel,
    )
}

@Composable
private fun InstallStatus(
    model: ModelInstallState,
    downloadEnabled: Boolean,
    downloadLabel: String,
    onDownloadModel: () -> Unit,
) {
    when (model) {
        ModelInstallState.Missing -> {
            Text(stringResource(R.string.model_missing))
            Button(
                onClick = onDownloadModel,
                enabled = downloadEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(downloadLabel)
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
                enabled = downloadEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.retry_download))
            }
        }
    }
}
