package com.creativeidiot.transcriptgerman.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionSessionStoreTest {
    @Test
    fun sessionTransitions_preserveTranscriptAndClearOldFailure() {
        val store = CaptionSessionStore()

        store.markFailure(CaptionFailure.AUDIO_UNAVAILABLE)
        store.markStopped(clearFailure = false)
        store.markStarting()
        store.markListening()
        store.appendFinal("  Guten Morgen  ")

        val state = store.state.value
        assertEquals(CaptionSessionStatus.LISTENING, state.status)
        assertNull(state.failure)
        assertEquals(listOf("Guten Morgen"), state.lines.map { it.text })
    }

    @Test
    fun transcript_isBoundedAndCanBeCleared() {
        val store = CaptionSessionStore()

        repeat(205) { index ->
            store.appendFinal("line " + index)
        }

        assertEquals(200, store.state.value.lines.size)
        assertEquals("line 5", store.state.value.lines.first().text)
        assertEquals("line 204", store.state.value.lines.last().text)

        store.clearTranscript()

        assertEquals(emptyList<CaptionLine>(), store.state.value.lines)
    }

    @Test
    fun stoppingAfterFailure_keepsFailureUntilNextStart() {
        val store = CaptionSessionStore()

        store.markStarting()
        store.markFailure(CaptionFailure.AUDIO_BACKPRESSURE)
        store.markStopped(clearFailure = false)

        assertEquals(CaptionSessionStatus.IDLE, store.state.value.status)
        assertEquals(CaptionFailure.AUDIO_BACKPRESSURE, store.state.value.failure)

        store.markStarting()

        assertNull(store.state.value.failure)
    }
}
