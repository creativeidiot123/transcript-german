package com.creativeidiot.transcriptgerman.asr

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class GeminiLiveRecognizerTest {
    @Test
    fun streamsAudioAndPublishesInterimThenFinalTranscript() = runTest {
        val server = MockWebServer()
        val client = OkHttpClient()
        val interim = CompletableDeferred<String>()
        val final = CompletableDeferred<String>()
        var failed = false

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val root = JSONObject(text)
                        when {
                            root.has("setup") -> {
                                webSocket.send("""{"setupComplete":{}}""")
                            }

                            root.optJSONObject("realtimeInput")
                                ?.has("audio") == true -> {
                                webSocket.send(
                                    """{"serverContent":{"interimInputTranscription":{"text":"Guten"}}}""",
                                )
                            }

                            root.optJSONObject("realtimeInput")
                                ?.optBoolean("audioStreamEnd") == true -> {
                                webSocket.send(
                                    """{"serverContent":{"inputTranscription":{"text":"Guten Morgen"}}}""",
                                )
                            }
                        }
                    }
                },
            ),
        )
        server.start()

        val recognizer = try {
            withContext(Dispatchers.IO) {
                GeminiLiveRecognizer.connect(
                    client = client,
                    apiKey = "test-api-key",
                    onPartial = { interim.complete(it) },
                    onFinal = { final.complete(it) },
                    onFailure = { failed = true },
                    endpoint = server.url("/live"),
                )
            }
        } catch (failure: Throwable) {
            server.shutdown()
            client.dispatcher.executorService.shutdown()
            throw failure
        }

        try {
            val request = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals(
                "test-api-key",
                request?.requestUrl?.queryParameter("key"),
            )

            recognizer.accept(floatArrayOf(0.1f, -0.1f))
            assertEquals("Guten", withContext(Dispatchers.IO) { interim.await() })

            withContext(Dispatchers.IO) {
                recognizer.finish()
            }
            assertEquals("Guten Morgen", withContext(Dispatchers.IO) { final.await() })
            assertFalse(failed)
        } finally {
            recognizer.close()
            server.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
