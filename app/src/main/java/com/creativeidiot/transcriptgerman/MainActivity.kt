package com.creativeidiot.transcriptgerman

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.creativeidiot.transcriptgerman.model.AsrBackend
import com.creativeidiot.transcriptgerman.service.CaptionService
import com.creativeidiot.transcriptgerman.ui.CaptionScreen
import com.creativeidiot.transcriptgerman.ui.CaptionViewModel
import com.creativeidiot.transcriptgerman.ui.TranscriptGermanTheme

class MainActivity : ComponentActivity() {
    private val viewModel: CaptionViewModel by viewModels {
        CaptionViewModel.Factory((application as TranscriptApplication).container)
    }

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onMicrophonePermissionResult(granted)?.let { backend ->
                startCaptionService(backend)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TranscriptGermanTheme {
                val state = viewModel.state.collectAsStateWithLifecycle().value
                CaptionScreen(
                    state = state,
                    onBackendSelected = viewModel::selectBackend,
                    onDownloadModel = viewModel::downloadModel,
                    onStartListening = ::requestStartListening,
                    onStopListening = ::stopCaptionService,
                    onClearTranscript = viewModel::clearTranscript,
                )
            }
        }
    }

    private fun requestStartListening() {
        val backend = viewModel.prepareMicrophoneRequest()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.consumePreparedStart()
            startCaptionService(backend)
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startCaptionService(backend: AsrBackend) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, CaptionService::class.java)
                .setAction(CaptionService.ACTION_START)
                .putExtra(CaptionService.EXTRA_BACKEND, backend.wireValue),
        )
    }

    private fun stopCaptionService() {
        startService(
            Intent(this, CaptionService::class.java).setAction(CaptionService.ACTION_STOP),
        )
    }
}
