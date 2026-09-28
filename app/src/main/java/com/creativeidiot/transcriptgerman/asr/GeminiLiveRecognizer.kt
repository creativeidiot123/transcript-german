package com.creativeidiot.transcriptgerman.asr

import com.creativeidiot.transcriptgerman.gemini.GeminiLiveProtocol
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * One Gemini caption session. Google caps a Live Transcribe session at 10 minutes, so the
 * recognizer opens a replacement socket before that cap and moves audio to it at a quiet point
 * (no pending interim), then lets the old socket finalize its tail. accept, finish, and close run
 * on the caption session worker; socket callbacks arrive on OkHttp threads.
 */
internal class GeminiLiveRecognizer private constructor(
    private val client: OkHttpClient,
    private val url: HttpUrl,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFailure: (GeminiLiveConnectionException) -> Unit,
    private val nowMillis: () -> Long,
) : CaptionRecognizer {
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)

    @Volatile
    private var started = false

    @Volatile
    private var current: GeminiLiveSocket? = null
    private var currentOpenedAt = 0L

    @Volatile
    private var replacement: GeminiLiveSocket? = null
    private var replacementOpenedAt = 0L
    private var nextReplacementAt = 0L

    @Volatile
    private var retiring: GeminiLiveSocket? = null
    private var retiringFinal: CompletableDeferred<Unit>? = null
    private var retiringDeadline = 0L

    /** Waits for the first socket's setup handshake; throws its connection failure. */
    suspend fun awaitSetup() {
        val socket = requireNotNull(current)
        try {
            val ready = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
                socket.awaitSetup()
                true
            } == true
            if (!ready) throw GeminiLiveConnectionException()
        } catch (failure: GeminiLiveConnectionException) {
            close()
            throw failure
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        }
        started = true
    }

    override fun accept(samples: FloatArray) {
        check(!closed.get()) { "Recognizer is closed" }
        if (failed.get()) return

        rotateIfDue(nowMillis())
        if (failed.get()) return

        if (!requireNotNull(current).send(GeminiLiveProtocol.audioMessage(samples))) {
            signalFailure(GeminiLiveConnectionException())
        }
    }

    override suspend fun finish() {
        if (closed.get() || failed.get()) return

        replacement?.close()
        replacement = null

        retiring?.let { old ->
            val remaining = (retiringDeadline - nowMillis()).coerceAtLeast(0L)
            val finalized = awaitFinal(requireNotNull(retiringFinal), remaining)
            if (!finalized && old.hasPendingPartial) {
                throw GeminiLiveConnectionException()
            }
            retire(old)
        }

        val socket = requireNotNull(current)
        val hasUnfinalizedAudio = socket.hasUnfinalizedAudio
        val shouldRequireFinal = socket.hasPendingPartial
        val waiter = socket.endAudio()
        if (waiter == null) {
            signalFailure(GeminiLiveConnectionException())
            throw GeminiLiveConnectionException()
        }

        if (hasUnfinalizedAudio) {
            val timeoutMillis =
                if (shouldRequireFinal) {
                    FINALIZATION_TIMEOUT_MILLIS
                } else {
                    QUIET_FINALIZATION_GRACE_MILLIS
                }
            if (!awaitFinal(waiter, timeoutMillis) && shouldRequireFinal) {
                throw GeminiLiveConnectionException()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        current?.close()
        replacement?.close()
        retiring?.close()
    }

    private fun rotateIfDue(now: Long) {
        retiring?.let { old ->
            val waiter = requireNotNull(retiringFinal)
            if (!waiter.isCompleted && now < retiringDeadline) return
            val finalized = waiter.isCompleted && !waiter.isCancelled
            if (!finalized && old.hasPendingPartial) {
                signalFailure(GeminiLiveConnectionException())
                return
            }
            retire(old)
        }

        val active = requireNotNull(current)
        val age = now - currentOpenedAt
        if (age < SESSION_ROTATE_AFTER_MILLIS && !active.goAwayReceived) return

        val next = replacement
        if (next == null) {
            if (now >= nextReplacementAt) {
                replacement = openSocket()
                replacementOpenedAt = now
            }
            return
        }

        if (next.isFailed || (!next.isReady && now - replacementOpenedAt >= CONNECT_TIMEOUT_MILLIS)) {
            next.close()
            replacement = null
            nextReplacementAt = now + REPLACEMENT_RETRY_DELAY_MILLIS
            return
        }

        val mustSwitch = age >= SESSION_FORCE_SWITCH_AFTER_MILLIS || active.goAwayReceived
        if (next.isReady && (!active.hasPendingPartial || mustSwitch)) {
            switchTo(next, now)
        }
    }

    private fun switchTo(next: GeminiLiveSocket, now: Long) {
        val old = requireNotNull(current)
        val hadPendingPartial = old.hasPendingPartial

        retiring = old
        current = next
        currentOpenedAt = replacementOpenedAt
        replacement = null

        val waiter = old.endAudio()
        if (waiter == null) {
            if (hadPendingPartial) {
                signalFailure(GeminiLiveConnectionException())
            } else {
                retire(old)
            }
            return
        }
        retiringFinal = waiter
        retiringDeadline = now + FINALIZATION_TIMEOUT_MILLIS
    }

    private fun retire(old: GeminiLiveSocket) {
        old.close()
        retiring = null
        retiringFinal = null
    }

    private fun openSocket(): GeminiLiveSocket =
        GeminiLiveSocket.open(
            client = client,
            url = url,
            onPartial = { text -> if (!failed.get()) onPartial(text) },
            onFinal = { text -> if (!failed.get()) onFinal(text) },
            onFailure = ::onSocketFailure,
        )

    private fun onSocketFailure(socket: GeminiLiveSocket, failure: GeminiLiveConnectionException) {
        when {
            // Rotation drops a failed replacement and retries it later.
            socket === replacement -> Unit
            // A quiet retiring socket has nothing left to deliver.
            socket === retiring -> if (socket.hasPendingPartial) signalFailure(failure)
            // Before awaitSetup returns, the first socket's failure is thrown from there.
            socket === current && started -> signalFailure(failure)
        }
    }

    private fun signalFailure(failure: GeminiLiveConnectionException) {
        if (closed.get()) return
        if (failed.compareAndSet(false, true)) {
            onFailure(failure)
        }
    }

    private suspend fun awaitFinal(
        waiter: CompletableDeferred<Unit>,
        timeoutMillis: Long,
    ): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            waiter.join()
            !waiter.isCancelled
        } == true

    companion object {
        /**
         * Starts the first socket without waiting for setup, so the caller can overlap the
         * network handshake with other initialization before calling [awaitSetup].
         */
        fun open(
            client: OkHttpClient,
            apiKey: String,
            onPartial: (String) -> Unit,
            onFinal: (String) -> Unit,
            onFailure: (GeminiLiveConnectionException) -> Unit,
            endpoint: HttpUrl = GeminiLiveProtocol.ENDPOINT.toHttpUrl(),
            nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
        ): GeminiLiveRecognizer {
            val recognizer = GeminiLiveRecognizer(
                client = client,
                url = endpoint
                    .newBuilder()
                    .addQueryParameter("key", apiKey)
                    .build(),
                onPartial = onPartial,
                onFinal = onFinal,
                onFailure = onFailure,
                nowMillis = nowMillis,
            )
            recognizer.currentOpenedAt = nowMillis()
            recognizer.current = recognizer.openSocket()
            return recognizer
        }

        private const val CONNECT_TIMEOUT_MILLIS = 20_000L
        private const val FINALIZATION_TIMEOUT_MILLIS = 5_000L
        private const val QUIET_FINALIZATION_GRACE_MILLIS = 2_000L

        // Google caps a Live Transcribe session at 10 minutes. Start the replacement early
        // enough to wait for a quiet point and to retry a failed replacement handshake.
        internal const val SESSION_ROTATE_AFTER_MILLIS = 8L * 60_000L
        internal const val SESSION_FORCE_SWITCH_AFTER_MILLIS = 9L * 60_000L + 30_000L
        internal const val REPLACEMENT_RETRY_DELAY_MILLIS = 10_000L
    }
}
