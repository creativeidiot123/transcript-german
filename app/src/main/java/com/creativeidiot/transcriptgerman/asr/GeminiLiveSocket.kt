package com.creativeidiot.transcriptgerman.asr

import com.creativeidiot.transcriptgerman.gemini.GeminiLiveEvent
import com.creativeidiot.transcriptgerman.gemini.GeminiLiveProtocol
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

internal enum class GeminiLiveConnectionFailure {
    AUTHENTICATION,
    CONNECTION,
}

internal class GeminiLiveConnectionException(
    val failure: GeminiLiveConnectionFailure = GeminiLiveConnectionFailure.CONNECTION,
    val httpStatusCode: Int? = null,
    val closeCode: Int? = null,
) : IOException()

private fun Response?.toGeminiConnectionFailure(): GeminiLiveConnectionFailure =
    when (this?.code) {
        400, 401, 403 -> GeminiLiveConnectionFailure.AUTHENTICATION
        else -> GeminiLiveConnectionFailure.CONNECTION
    }

// Google accepts the socket upgrade, then rejects an invalid key by closing
// with 1007 and a reason such as "API key not valid" once it reads setup.
private fun geminiCloseFailure(code: Int, reason: String): GeminiLiveConnectionFailure =
    if (
        (code == INVALID_PAYLOAD_CLOSE_CODE || code == POLICY_VIOLATION_CLOSE_CODE) &&
        reason.contains("API key", ignoreCase = true)
    ) {
        GeminiLiveConnectionFailure.AUTHENTICATION
    } else {
        GeminiLiveConnectionFailure.CONNECTION
    }

private const val INVALID_PAYLOAD_CLOSE_CODE = 1007
private const val POLICY_VIOLATION_CLOSE_CODE = 1008

/**
 * One Gemini Live WebSocket: setup handshake, ordered audio sends, and the transcript state of
 * that single server session. GeminiLiveRecognizer decides which socket is current.
 */
internal class GeminiLiveSocket private constructor(
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFailure: (GeminiLiveSocket, GeminiLiveConnectionException) -> Unit,
) {
    private lateinit var webSocket: WebSocket
    private val setup = CompletableDeferred<Unit>()
    private val clientClosing = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val latestPartial = AtomicReference("")
    private val audioSinceLastFinal = AtomicBoolean(false)
    private val finalizationWaiter =
        AtomicReference<CompletableDeferred<Unit>?>(null)

    @Volatile
    private var setupComplete = false

    @Volatile
    var goAwayReceived = false
        private set

    val isReady: Boolean
        get() = setupComplete && !failed.get()

    val isFailed: Boolean
        get() = failed.get()

    val hasPendingPartial: Boolean
        get() = latestPartial.get().isNotBlank()

    val hasUnfinalizedAudio: Boolean
        get() = audioSinceLastFinal.get()

    /** Completes on setupComplete; throws the setup failure otherwise. */
    suspend fun awaitSetup() {
        setup.await()
    }

    fun send(audioMessage: String): Boolean {
        if (failed.get() || webSocket.queueSize() >= MAX_WEB_SOCKET_QUEUE_BYTES) {
            return false
        }
        audioSinceLastFinal.set(true)
        return webSocket.send(audioMessage)
    }

    /**
     * Sends audioStreamEnd. The returned waiter completes on the next final transcript, is
     * already complete when no audio followed the last final, and is cancelled on failure.
     * Returns null when the message could not be queued.
     */
    fun endAudio(): CompletableDeferred<Unit>? {
        val waiter = CompletableDeferred<Unit>()
        if (audioSinceLastFinal.get()) {
            finalizationWaiter.set(waiter)
        } else {
            waiter.complete(Unit)
        }

        if (failed.get() || !webSocket.send(GeminiLiveProtocol.audioStreamEndMessage())) {
            finalizationWaiter.compareAndSet(waiter, null)
            waiter.cancel()
            return null
        }
        return waiter
    }

    fun close() {
        if (!clientClosing.compareAndSet(false, true)) return

        finalizationWaiter.getAndSet(null)?.cancel()
        if (!webSocket.close(NORMAL_CLOSE_CODE, "caption session finished")) {
            webSocket.cancel()
        }
    }

    private fun handleEvent(event: GeminiLiveEvent) {
        if (clientClosing.get() || failed.get()) return

        if (event.setupComplete) {
            setupComplete = true
            setup.complete(Unit)
        }
        if (event.goAway) {
            goAwayReceived = true
        }

        event.interimText?.let { text ->
            val normalized = text.trim()
            val previous = latestPartial.getAndSet(normalized)
            if (normalized != previous) {
                onPartial(normalized)
            }
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

    private fun fail(failure: GeminiLiveConnectionException) {
        if (clientClosing.get() || !failed.compareAndSet(false, true)) return

        setup.completeExceptionally(failure)
        finalizationWaiter.getAndSet(null)?.cancel()
        onFailure(this, failure)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!webSocket.send(GeminiLiveProtocol.setupMessage())) {
                fail(GeminiLiveConnectionException())
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleServerMessage(text)
        }

        // Gemini Live sends its JSON server messages, setupComplete included,
        // as binary WebSocket frames.
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleServerMessage(bytes.utf8())
        }

        private fun handleServerMessage(text: String) {
            val event = try {
                GeminiLiveProtocol.parseServerMessage(text)
            } catch (_: RuntimeException) {
                fail(GeminiLiveConnectionException())
                return
            }
            handleEvent(event)
        }

        override fun onFailure(
            webSocket: WebSocket,
            throwable: Throwable,
            response: Response?,
        ) {
            fail(
                if (setupComplete) {
                    GeminiLiveConnectionException()
                } else {
                    GeminiLiveConnectionException(
                        failure = response.toGeminiConnectionFailure(),
                        httpStatusCode = response?.code,
                    )
                },
            )
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            fail(
                GeminiLiveConnectionException(
                    failure = if (setupComplete) {
                        GeminiLiveConnectionFailure.CONNECTION
                    } else {
                        geminiCloseFailure(code, reason)
                    },
                    closeCode = code,
                ),
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail(GeminiLiveConnectionException(closeCode = code))
        }
    }

    companion object {
        /** Starts the connection; the setup handshake continues on OkHttp threads. */
        fun open(
            client: OkHttpClient,
            url: HttpUrl,
            onPartial: (String) -> Unit,
            onFinal: (String) -> Unit,
            onFailure: (GeminiLiveSocket, GeminiLiveConnectionException) -> Unit,
        ): GeminiLiveSocket {
            val socket = GeminiLiveSocket(onPartial, onFinal, onFailure)
            socket.webSocket = client.newWebSocket(
                Request.Builder()
                    .url(url)
                    .build(),
                socket.Listener(),
            )
            return socket
        }

        private const val MAX_WEB_SOCKET_QUEUE_BYTES = 256L * 1024L
        private const val NORMAL_CLOSE_CODE = 1000
    }
}
