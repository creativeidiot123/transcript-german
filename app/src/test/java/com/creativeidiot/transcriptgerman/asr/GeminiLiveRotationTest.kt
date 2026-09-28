package com.creativeidiot.transcriptgerman.asr

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveRotationTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val clock = AtomicLong(0L)
    private val partials = Collections.synchronizedList(mutableListOf<String>())
    private val finals = Collections.synchronizedList(mutableListOf<String>())
    private val failures = AtomicInteger(0)

    @After
    fun tearDown() {
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun rotatesToFreshSocketAtQuietPointBeforeSessionCap() = runBlocking {
        val first = FakeGeminiSocket()
        val second = FakeGeminiSocket(finalOnStreamEnd = "Neue Sitzung")
        val recognizer = start(first, second)

        recognizer.accept(CHUNK)
        clock.set(GeminiLiveRecognizer.SESSION_ROTATE_AFTER_MILLIS)
        pumpUntil(recognizer) { second.audioCount.get() > 0 }

        await(first.streamEnded)
        val firstAudio = first.audioCount.get()
        recognizer.accept(CHUNK)
        delay(QUIET_PUMPS * PUMP_DELAY_MILLIS)
        assertEquals("audio after the switch must go only to the new socket", firstAudio, first.audioCount.get())

        clock.addAndGet(RETIRE_WINDOW_MILLIS)
        recognizer.accept(CHUNK)
        await(first.closed)

        withContext(Dispatchers.IO) { recognizer.finish() }
        await(second.streamEnded)
        pumpFinals("Neue Sitzung")
        recognizer.close()
        assertEquals(0, failures.get())
    }

    @Test
    fun waitsForPendingInterimToFinalizeBeforeSwitching() = runBlocking {
        val first = FakeGeminiSocket(interimOnFirstAudio = "Wir gehen")
        val second = FakeGeminiSocket()
        val recognizer = start(first, second)

        recognizer.accept(CHUNK)
        waitFor { partials.contains("Wir gehen") }

        clock.set(GeminiLiveRecognizer.SESSION_ROTATE_AFTER_MILLIS)
        repeat(QUIET_PUMPS) {
            recognizer.accept(CHUNK)
            delay(PUMP_DELAY_MILLIS)
        }
        assertEquals("must not switch while an interim is pending", 0, second.audioCount.get())

        first.send("""{"serverContent":{"inputTranscription":{"text":"Wir gehen heim"}}}""")
        pumpUntil(recognizer) { second.audioCount.get() > 0 }
        await(first.streamEnded)

        assertEquals(listOf("Wir gehen heim"), finals.toList())
        recognizer.close()
        assertEquals(0, failures.get())
    }

    @Test
    fun forcesSwitchNearCapAndKeepsOldSocketFinal() = runBlocking {
        val first = FakeGeminiSocket(
            interimOnFirstAudio = "Ein langer",
            finalOnStreamEnd = "Ein langer Satz",
        )
        val second = FakeGeminiSocket()
        val recognizer = start(first, second)

        recognizer.accept(CHUNK)
        waitFor { partials.contains("Ein langer") }
        clock.set(GeminiLiveRecognizer.SESSION_ROTATE_AFTER_MILLIS)
        repeat(QUIET_PUMPS) {
            recognizer.accept(CHUNK)
            delay(PUMP_DELAY_MILLIS)
        }

        clock.set(GeminiLiveRecognizer.SESSION_FORCE_SWITCH_AFTER_MILLIS)
        pumpUntil(recognizer) { second.audioCount.get() > 0 }
        await(first.streamEnded)
        waitFor { finals.contains("Ein langer Satz") }

        clock.addAndGet(RETIRE_WINDOW_MILLIS)
        recognizer.accept(CHUNK)
        await(first.closed)
        recognizer.close()
        assertEquals(0, failures.get())
    }

    @Test
    fun goAwayStartsRotationBeforeScheduledTime() = runBlocking {
        val first = FakeGeminiSocket()
        val second = FakeGeminiSocket()
        val recognizer = start(first, second)

        recognizer.accept(CHUNK)
        first.send("""{"goAway":{"timeLeft":"10s"}}""")
        pumpUntil(recognizer) { second.audioCount.get() > 0 }
        await(first.streamEnded)

        recognizer.close()
        assertEquals(0, failures.get())
    }

    @Test
    fun failedReplacementHandshakeIsRetriedWithoutEndingSession() = runBlocking {
        val first = FakeGeminiSocket()
        val third = FakeGeminiSocket()
        server.enqueue(MockResponse().withWebSocketUpgrade(first))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().withWebSocketUpgrade(third))
        val recognizer = openRecognizer()

        clock.set(GeminiLiveRecognizer.SESSION_ROTATE_AFTER_MILLIS)
        pumpUntil(recognizer) { server.requestCount >= 2 }
        repeat(QUIET_PUMPS) {
            recognizer.accept(CHUNK)
            delay(PUMP_DELAY_MILLIS)
        }
        assertEquals(0, failures.get())

        clock.addAndGet(GeminiLiveRecognizer.REPLACEMENT_RETRY_DELAY_MILLIS)
        pumpUntil(recognizer) { third.audioCount.get() > 0 }
        await(first.streamEnded)

        recognizer.close()
        assertEquals(0, failures.get())
    }

    @Test
    fun retiringSocketThatNeverFinalizesPendingSpeechFailsSession() = runBlocking {
        val first = FakeGeminiSocket(interimOnFirstAudio = "Verloren")
        val second = FakeGeminiSocket()
        val recognizer = start(first, second)

        recognizer.accept(CHUNK)
        waitFor { partials.contains("Verloren") }
        clock.set(GeminiLiveRecognizer.SESSION_FORCE_SWITCH_AFTER_MILLIS)
        pumpUntil(recognizer) { second.audioCount.get() > 0 }
        await(first.streamEnded)
        assertEquals(0, failures.get())

        clock.addAndGet(RETIRE_WINDOW_MILLIS)
        recognizer.accept(CHUNK)
        assertEquals(1, failures.get())
        recognizer.close()
    }

    @Test
    fun openReturnsBeforeSetupSoCallerCanOverlapInitialization() = runBlocking {
        val releaseSetup = CompletableDeferred<Unit>()
        val setupReceived = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (JSONObject(text).has("setup")) {
                            setupReceived.complete(Unit)
                            runBlocking { releaseSetup.await() }
                            webSocket.send("""{"setupComplete":{}}""")
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
        server.start()

        val recognizer = GeminiLiveRecognizer.open(
            client = client,
            apiKey = "test-api-key",
            onPartial = {},
            onFinal = {},
            onFailure = { failures.incrementAndGet() },
            endpoint = server.url("/live"),
            nowMillis = clock::get,
        )
        await(setupReceived)
        releaseSetup.complete(Unit)
        withContext(Dispatchers.IO) { withTimeout(WAIT_MILLIS) { recognizer.awaitSetup() } }

        recognizer.close()
        assertEquals(0, failures.get())
    }

    private suspend fun start(vararg sockets: FakeGeminiSocket): GeminiLiveRecognizer {
        sockets.forEach { server.enqueue(MockResponse().withWebSocketUpgrade(it)) }
        return openRecognizer()
    }

    private suspend fun openRecognizer(): GeminiLiveRecognizer {
        server.start()
        return withContext(Dispatchers.IO) {
            GeminiLiveRecognizer.open(
                client = client,
                apiKey = "test-api-key",
                onPartial = { partials.add(it) },
                onFinal = { finals.add(it) },
                onFailure = { failures.incrementAndGet() },
                endpoint = server.url("/live"),
                nowMillis = clock::get,
            ).also { it.awaitSetup() }
        }
    }

    private suspend fun pumpUntil(recognizer: GeminiLiveRecognizer, condition: () -> Boolean) {
        withTimeout(WAIT_MILLIS) {
            while (!condition()) {
                recognizer.accept(CHUNK)
                delay(PUMP_DELAY_MILLIS)
            }
        }
    }

    private suspend fun pumpFinals(expected: String) = waitFor { finals.contains(expected) }

    private suspend fun waitFor(condition: () -> Boolean) {
        withTimeout(WAIT_MILLIS) {
            while (!condition()) delay(PUMP_DELAY_MILLIS)
        }
    }

    private suspend fun await(signal: CompletableDeferred<Unit>) {
        withContext(Dispatchers.IO) { withTimeout(WAIT_MILLIS) { signal.await() } }
        assertTrue(signal.isCompleted)
    }

    /** Server side of one Gemini Live socket. */
    private class FakeGeminiSocket(
        private val interimOnFirstAudio: String? = null,
        private val finalOnStreamEnd: String? = null,
    ) : WebSocketListener() {
        val audioCount = AtomicInteger(0)
        val streamEnded = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()

        @Volatile
        private var socket: WebSocket? = null

        fun send(json: String) {
            check(requireNotNull(socket).send(json))
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val root = JSONObject(text)
            val input = root.optJSONObject("realtimeInput")
            when {
                root.has("setup") -> webSocket.send("""{"setupComplete":{}}""")

                input?.has("audio") == true -> {
                    if (audioCount.incrementAndGet() == 1 && interimOnFirstAudio != null) {
                        webSocket.send(
                            """{"serverContent":{"interimInputTranscription":{"text":"$interimOnFirstAudio"}}}""",
                        )
                    }
                }

                input?.optBoolean("audioStreamEnd") == true -> {
                    streamEnded.complete(Unit)
                    if (finalOnStreamEnd != null) {
                        webSocket.send(
                            """{"serverContent":{"inputTranscription":{"text":"$finalOnStreamEnd"}}}""",
                        )
                    }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(Unit)
            webSocket.close(code, reason)
        }
    }

    private companion object {
        val CHUNK = floatArrayOf(0.1f, -0.1f)
        const val PUMP_DELAY_MILLIS = 20L
        const val QUIET_PUMPS = 15
        const val WAIT_MILLIS = 5_000L

        // Retiring sockets get the recognizer's five-second finalization window.
        const val RETIRE_WINDOW_MILLIS = 5_000L
    }
}
