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
            viewModel.onMicrophonePermissionResult(granted)
            if (granted) {
                startCaptionService()
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
                    onDownloadModel = viewModel::downloadModel,
                    onStartListening = ::requestStartListening,
                    onStopListening = ::stopCaptionService,
                    onClearTranscript = viewModel::clearTranscript,
                )
            }
        }
    }

    private fun requestStartListening() {
        viewModel.prepareMicrophoneRequest()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCaptionService()
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startCaptionService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, CaptionService::class.java).setAction(CaptionService.ACTION_START),
        )
    }

    private fun stopCaptionService() {
        startService(
            Intent(this, CaptionService::class.java).setAction(CaptionService.ACTION_STOP),
        )
    }
}
