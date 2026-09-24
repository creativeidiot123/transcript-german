package com.creativeidiot.transcriptgerman.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.creativeidiot.transcriptgerman.MainActivity
import com.creativeidiot.transcriptgerman.R
import com.creativeidiot.transcriptgerman.TranscriptApplication
import com.creativeidiot.transcriptgerman.asr.CaptionRecognizer
import com.creativeidiot.transcriptgerman.asr.GeminiLiveConnectionException
import com.creativeidiot.transcriptgerman.asr.GeminiLiveRecognizer
import com.creativeidiot.transcriptgerman.asr.NemotronRecognizer
import com.creativeidiot.transcriptgerman.asr.ParakeetRecognizer
import com.creativeidiot.transcriptgerman.audio.AudioCapture
import com.creativeidiot.transcriptgerman.model.AsrBackend
import com.creativeidiot.transcriptgerman.session.CaptionFailure
import com.creativeidiot.transcriptgerman.session.CaptionSessionStore
import com.creativeidiot.transcriptgerman.translation.BergamotTranslator
import com.creativeidiot.transcriptgerman.translation.CaptionTranslationPipeline
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptionService : Service() {
    private enum class StopReason {
        NONE,
        USER,
        FAILURE,
        DESTROYED,
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopReason = AtomicReference(StopReason.NONE)
    private val terminalFailureRequested = AtomicBoolean(false)

    private var sessionGeneration = 0L

    @Volatile
    private var sessionJob: Job? = null

    @Volatile
    private var audioCapture: AudioCapture? = null

    @Volatile
    private var audioQueue: Channel<FloatArray>? = null

    private val container
        get() = (application as TranscriptApplication).container

    private val store: CaptionSessionStore
        get() = container.sessionStore

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val backend =
                    AsrBackend.fromWireValue(intent.getStringExtra(EXTRA_BACKEND))
                        ?: AsrBackend.PRIMELINE
                startForegroundSession(backend)
            }

            ACTION_STOP -> requestUserStop()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopReason.compareAndSet(StopReason.NONE, StopReason.DESTROYED)
        audioCapture?.stop()
        audioQueue?.close()
        sessionJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSession(backend: AsrBackend) {
        if (sessionJob?.isActive == true) {
            Log.i(TAG, "Ignoring duplicate start; caption session is already active")
            return
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
        } catch (failure: RuntimeException) {
            Log.e(TAG, "Foreground service start failed: " + failure.javaClass.simpleName)
            store.markFailure(CaptionFailure.UNEXPECTED)
            store.markStopped(clearFailure = false)
            stopSelf()
            return
        }

        stopReason.set(StopReason.NONE)
        terminalFailureRequested.set(false)
        store.markStarting(backend)

        sessionGeneration += 1
        val generation = sessionGeneration
        sessionJob = serviceScope.launch {
            runSession(generation, backend)
        }
    }

    private suspend fun runSession(
        generation: Long,
        backend: AsrBackend,
    ) {
        val queue = Channel<FloatArray>(capacity = AUDIO_QUEUE_CAPACITY)
        audioQueue = queue

        var recognizer: CaptionRecognizer? = null
        var capture: AudioCapture? = null
        var translationPipeline: CaptionTranslationPipeline? = null

        try {
            val modelDirectory =
                if (backend.requiresLocalModel) {
                    container.modelRepository.installedDirectoryOrNull(backend)
                } else {
                    null
                }
            if (backend.requiresLocalModel && modelDirectory == null) {
                failSession(CaptionFailure.MODEL_NOT_READY)
                return
            }

            val geminiApiKey =
                if (backend == AsrBackend.GEMINI) {
                    container.geminiApiKeyStore.apiKeyOrNull()
                } else {
                    null
                }
            if (backend == AsrBackend.GEMINI && geminiApiKey == null) {
                failSession(CaptionFailure.GEMINI_API_KEY_NOT_CONFIGURED)
                return
            }

            val translationFiles =
                container.bergamotModelRepository.installedFilesOrNull()
            if (translationFiles == null) {
                failSession(CaptionFailure.TRANSLATION_MODEL_NOT_READY)
                return
            }

            val translator = try {
                BergamotTranslator(
                    context = applicationContext,
                    files = translationFiles,
                )
            } catch (failure: RuntimeException) {
                Log.e(
                    TAG,
                    "Bergamot initialization failed: " +
                        failure.javaClass.simpleName,
                )
                failSession(CaptionFailure.TRANSLATION_INITIALIZATION)
                return
            } catch (failure: LinkageError) {
                Log.e(
                    TAG,
                    "Bergamot runtime linkage failed: " +
                        failure.javaClass.simpleName,
                )
                failSession(CaptionFailure.TRANSLATION_INITIALIZATION)
                return
            }

            translationPipeline = CaptionTranslationPipeline(
                scope = serviceScope,
                translator = translator,
                onPartialTranslated = store::updatePartialTranslation,
                onFinalTranslated = store::updateFinalTranslation,
                onFailure = {
                    Log.e(TAG, "Bergamot translation failed")
                    signalTerminalFailure(CaptionFailure.TRANSLATION)
                },
            )

            recognizer = try {
                createRecognizer(
                    backend = backend,
                    modelDirectory = modelDirectory,
                    geminiApiKey = geminiApiKey,
                    onPartial = { german ->
                        store.updatePartial(german)
                        if (translationPipeline?.submitPartial(german) == false) {
                            signalTerminalFailure(CaptionFailure.TRANSLATION)
                        }
                    },
                    onFinal = { german ->
                        val lineId = store.appendFinal(german)
                        if (
                            lineId != null &&
                            translationPipeline?.submitFinal(lineId, german) == false
                        ) {
                            signalTerminalFailure(CaptionFailure.TRANSLATION)
                        }
                    },
                )
            } catch (_: GeminiLiveConnectionException) {
                Log.e(TAG, "Gemini live transcription connection failed")
                failSession(CaptionFailure.GEMINI_CONNECTION)
                return
            } catch (failure: RuntimeException) {
                Log.e(
                    TAG,
                    "ASR initialization failed for " + backend.name +
                        ": " + failure.javaClass.simpleName,
                )
                failSession(CaptionFailure.ASR_INITIALIZATION)
                return
            }

            capture = AudioCapture(
                onChunk = { samples ->
                    if (!queue.trySend(samples).isSuccess) {
                        signalTerminalFailure(CaptionFailure.AUDIO_BACKPRESSURE)
                    }
                },
                onFailure = { failure ->
                    Log.e(TAG, "Audio capture failed: " + failure.name)
                    signalTerminalFailure(CaptionFailure.AUDIO_UNAVAILABLE)
                },
            )
            audioCapture = capture

            store.markListening()
            if (!capture.start()) {
                failSession(CaptionFailure.AUDIO_UNAVAILABLE)
                return
            }

            for (samples in queue) {
                recognizer.accept(samples)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            Log.e(
                TAG,
                "Caption processing failed for " + backend.name +
                    ": " + failure.javaClass.simpleName,
            )
            failSession(CaptionFailure.UNEXPECTED)
        } finally {
            capture?.stop()
            queue.close()

            withContext(NonCancellable) {
                if (stopReason.get() == StopReason.USER) {
                    try {
                        recognizer?.finish()
                    } catch (_: GeminiLiveConnectionException) {
                        Log.e(TAG, "Gemini final transcription did not complete")
                        store.markFailure(CaptionFailure.GEMINI_CONNECTION)
                        stopReason.set(StopReason.FAILURE)
                    } catch (failure: RuntimeException) {
                        Log.e(
                            TAG,
                            "Final ASR flush failed: " +
                                failure.javaClass.simpleName,
                        )
                    }
                    translationPipeline?.finishAndDrain()
                    runCatching { recognizer?.close() }
                } else {
                    // Stop asynchronous cloud callbacks before translation cancellation can
                    // make a late finalized caption impossible to translate.
                    runCatching { recognizer?.close() }
                    translationPipeline?.cancel()
                }
                audioCapture = null
                audioQueue = null
                completeSession(generation)
            }
        }
    }

    private suspend fun createRecognizer(
        backend: AsrBackend,
        modelDirectory: File?,
        geminiApiKey: String?,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
    ): CaptionRecognizer =
        when (backend) {
            AsrBackend.PRIMELINE -> {
                ParakeetRecognizer(
                    modelDirectory = requireNotNull(modelDirectory),
                    onSpeechDetected = store::markSpeechDetected,
                    onTranscribing = store::markTranscribing,
                    onFinal = onFinal,
                )
            }

            AsrBackend.NEMOTRON -> {
                NemotronRecognizer(
                    modelDirectory = requireNotNull(modelDirectory),
                    onTranscribing = store::markTranscribing,
                    onPartial = onPartial,
                    onFinal = onFinal,
                )
            }

            AsrBackend.GEMINI -> {
                GeminiLiveRecognizer.connect(
                    client = container.geminiHttpClient,
                    apiKey = requireNotNull(geminiApiKey),
                    onPartial = onPartial,
                    onFinal = onFinal,
                    onFailure = {
                        Log.e(TAG, "Gemini live transcription stream failed")
                        signalTerminalFailure(CaptionFailure.GEMINI_CONNECTION)
                    },
                )
            }
        }

    private fun signalTerminalFailure(failure: CaptionFailure) {
        if (!terminalFailureRequested.compareAndSet(false, true)) return

        store.markFailure(failure)
        stopReason.set(StopReason.FAILURE)

        serviceScope.launch {
            audioCapture?.stop()
            audioQueue?.close()
            sessionJob?.cancel()
        }
    }

    private fun failSession(failure: CaptionFailure) {
        store.markFailure(failure)
        stopReason.set(StopReason.FAILURE)
    }

    private fun requestUserStop() {
        if (sessionJob == null) {
            store.markStopped(clearFailure = true)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        stopReason.compareAndSet(StopReason.NONE, StopReason.USER)
        store.markStopping()

        serviceScope.launch {
            audioCapture?.stop()
            audioQueue?.close()
        }
    }

    private fun completeSession(generation: Long) {
        mainHandler.post {
            if (generation != sessionGeneration) return@post

            val reason = stopReason.get()
            store.markStopped(clearFailure = reason != StopReason.FAILURE)
            sessionJob = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopService = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, CaptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_caption)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, getString(R.string.notification_stop), stopService)
            .build()
    }

    companion object {
        const val ACTION_START =
            "com.creativeidiot.transcriptgerman.action.START_CAPTIONS"
        const val ACTION_STOP =
            "com.creativeidiot.transcriptgerman.action.STOP_CAPTIONS"
        const val EXTRA_BACKEND =
            "com.creativeidiot.transcriptgerman.extra.ASR_BACKEND"

        private const val TAG = "CaptionService"
        private const val NOTIFICATION_CHANNEL_ID = "live_captions"
        private const val NOTIFICATION_ID = 1001
        private const val OPEN_APP_REQUEST_CODE = 1002
        private const val STOP_REQUEST_CODE = 1003
        private const val AUDIO_QUEUE_CAPACITY = 64
    }
}
