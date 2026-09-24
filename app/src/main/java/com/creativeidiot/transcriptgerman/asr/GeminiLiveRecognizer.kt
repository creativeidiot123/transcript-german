package com.creativeidiot.transcriptgerman.asr

import com.creativeidiot.transcriptgerman.gemini.GeminiLiveEvent
import com.creativeidiot.transcriptgerman.gemini.GeminiLiveProtocol
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal class GeminiLiveConnectionException : IOException()

internal class GeminiLiveRecognizer private constructor(
    private val webSocket: WebSocket,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFailure: () -> Unit,
) : CaptionRecognizer {
    private val acceptingEvents = AtomicBoolean(true)
    private val clientClosing = AtomicBoolean(false)
    private val failureSignaled = AtomicBoolean(false)
    private val latestPartial = AtomicReference("")
    private val audioSinceLastFinal = AtomicBoolean(false)
    private val finalizationWaiter =
        AtomicReference<CompletableDeferred<Unit>?>(null)

    override fun accept(samples: FloatArray) {
        check(!clientClosing.get()) { "Recognizer is closed" }
        if (!acceptingEvents.get()) return

        audioSinceLastFinal.set(true)
        if (!webSocket.send(GeminiLiveProtocol.audioMessage(samples))) {
            signalFailure()
        }
    }

    override suspend fun finish() {
        if (clientClosing.get() || !acceptingEvents.get()) return

        val hasUnfinalizedAudio = audioSinceLastFinal.get()
        val shouldRequireFinal = latestPartial.get().isNotBlank()
        val waiter = CompletableDeferred<Unit>()
        if (hasUnfinalizedAudio) {
            finalizationWaiter.set(waiter)
        }

        if (!webSocket.send(GeminiLiveProtocol.audioStreamEndMessage())) {
            finalizationWaiter.compareAndSet(waiter, null)
            signalFailure()
            throw GeminiLiveConnectionException()
        }

        if (hasUnfinalizedAudio) {
            val timeoutMillis =
                if (shouldRequireFinal) {
                    FINALIZATION_TIMEOUT_MILLIS
                } else {
                    QUIET_FINALIZATION_GRACE_MILLIS
                }
            val finalized =
                withTimeoutOrNull(timeoutMillis) {
                    waiter.await()
                    true
                } == true
            finalizationWaiter.compareAndSet(waiter, null)

            if (shouldRequireFinal && !finalized) {
                throw GeminiLiveConnectionException()
            }
        }
    }

    override fun close() {
        if (!clientClosing.compareAndSet(false, true)) return

        acceptingEvents.set(false)
        finalizationWaiter.getAndSet(null)?.cancel()
        if (!webSocket.close(NORMAL_CLOSE_CODE, "caption session finished")) {
            webSocket.cancel()
        }
    }

    private fun handleEvent(event: GeminiLiveEvent) {
        if (!acceptingEvents.get()) return

        event.interimText?.let { text ->
            val normalized = text.trim()
            latestPartial.set(normalized)
            onPartial(normalized)
        }

        event.finalText?.let { text ->
            val normalized = text.trim()
            if (normalized.isNotEmpty()) {
                latestPartial.set("")
                audioSinceLastFinal.set(false)
                onFinal(normalized)
                finalizationWaiter.getAndSet(null)?.complete(Unit)
            }
        }
    }

    private fun signalFailure() {
        if (clientClosing.get()) return

        acceptingEvents.set(false)
        finalizationWaiter.getAndSet(null)?.cancel()
        if (failureSignaled.compareAndSet(false, true)) {
            onFailure()
        }
    }

    companion object {
        suspend fun connect(
            client: OkHttpClient,
            apiKey: String,
            onPartial: (String) -> Unit,
            onFinal: (String) -> Unit,
            onFailure: () -> Unit,
            endpoint: HttpUrl = GeminiLiveProtocol.ENDPOINT.toHttpUrl(),
        ): GeminiLiveRecognizer {
            val setup = CompletableDeferred<Unit>()
            val recognizerRef = AtomicReference<GeminiLiveRecognizer?>()

            val url = endpoint
                .newBuilder()
                .addQueryParameter("key", apiKey)
                .build()

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!webSocket.send(GeminiLiveProtocol.setupMessage())) {
                        setup.completeExceptionally(GeminiLiveConnectionException())
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = try {
                        GeminiLiveProtocol.parseServerMessage(text)
                    } catch (_: RuntimeException) {
                        if (!setup.isCompleted) {
                            setup.completeExceptionally(GeminiLiveConnectionException())
                        } else {
                            recognizerRef.get()?.signalFailure()
                        }
                        return
                    }

                    if (event.setupComplete) {
                        setup.complete(Unit)
                    }
                    recognizerRef.get()?.handleEvent(event)
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    throwable: Throwable,
                    response: Response?,
                ) {
                    if (!setup.isCompleted) {
                        setup.completeExceptionally(GeminiLiveConnectionException())
                    } else {
                        recognizerRef.get()?.signalFailure()
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    val recognizer = recognizerRef.get() ?: return
                    if (!recognizer.clientClosing.get()) {
                        recognizer.signalFailure()
                    }
                }
            }

            val webSocket = client.newWebSocket(
                Request.Builder()
                    .url(url)
                    .build(),
                listener,
            )
            val recognizer = GeminiLiveRecognizer(
                webSocket = webSocket,
                onPartial = onPartial,
                onFinal = onFinal,
                onFailure = onFailure,
            )
            recognizerRef.set(recognizer)

            try {
                val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
                    setup.await()
                    true
                } == true
                if (!connected) {
                    recognizer.close()
                    throw GeminiLiveConnectionException()
                }
            } catch (cancelled: CancellationException) {
                recognizer.close()
                throw cancelled
            } catch (_: IOException) {
                recognizer.close()
                throw GeminiLiveConnectionException()
            }

            return recognizer
        }

        private const val CONNECT_TIMEOUT_MILLIS = 20_000L
        private const val FINALIZATION_TIMEOUT_MILLIS = 5_000L
        private const val QUIET_FINALIZATION_GRACE_MILLIS = 2_000L
        private const val NORMAL_CLOSE_CODE = 1000
    }
}
