package com.creativeidiot.transcriptgerman.session

import com.creativeidiot.transcriptgerman.model.AsrBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionSessionStoreTest {
    @Test
    fun sessionTransitions_preserveTranscriptAndClearOldFailure() {
        val store = CaptionSessionStore()

        store.markFailure(CaptionFailure.AUDIO_UNAVAILABLE)
        store.markStopped(clearFailure = false)
        store.markStarting(AsrBackend.PRIMELINE)
        store.markListening()
        store.appendFinal("  Guten Morgen  ")

        val state = store.state.value
        assertEquals(CaptionSessionStatus.LISTENING, state.status)
        assertEquals(AsrBackend.PRIMELINE, state.activeBackend)
        assertNull(state.failure)
        assertEquals(listOf("Guten Morgen"), state.lines.map { it.text })
    }

    @Test
    fun streamingPartial_isReplacedThenClearedByFinal() {
        val store = CaptionSessionStore()
        store.markStarting(AsrBackend.NEMOTRON)
        store.markListening()

        store.updatePartial(" Guten ")
        store.updatePartial(" Guten Morgen ")

        assertEquals("Guten Morgen", store.state.value.partialText)
        assertEquals(emptyList<CaptionLine>(), store.state.value.lines)

        store.appendFinal(" Guten Morgen ")

        assertEquals("", store.state.value.partialText)
        assertEquals(listOf("Guten Morgen"), store.state.value.lines.map { it.text })
    }

    @Test
    fun stopAndFailure_clearStreamingPartialAndBackendAtCompletion() {
        val store = CaptionSessionStore()
        store.markStarting(AsrBackend.NEMOTRON)
        store.markListening()
        store.updatePartial("Zwischenstand")

        store.markFailure(CaptionFailure.AUDIO_BACKPRESSURE)

        assertEquals(CaptionSessionStatus.STOPPING, store.state.value.status)
        assertEquals("", store.state.value.partialText)
        assertEquals(AsrBackend.NEMOTRON, store.state.value.activeBackend)

        store.markStopped(clearFailure = false)

        assertEquals(CaptionSessionStatus.IDLE, store.state.value.status)
        assertNull(store.state.value.activeBackend)
        assertEquals(CaptionFailure.AUDIO_BACKPRESSURE, store.state.value.failure)
    }

    @Test
    fun markStopping_preservesTranscriptAndMovesToStopping() {
        val store = CaptionSessionStore()
        store.markStarting(AsrBackend.PRIMELINE)
        store.markListening()
        store.appendFinal("Hallo Welt")

        store.markStopping()

        assertEquals(CaptionSessionStatus.STOPPING, store.state.value.status)
        assertEquals(listOf("Hallo Welt"), store.state.value.lines.map { it.text })
    }

    @Test
    fun lateActivityCallbacks_doNotLeaveStoppingState() {
        val store = CaptionSessionStore()
        store.markStarting(AsrBackend.PRIMELINE)
        store.markListening()
        store.markStopping()

        store.markListening()
        store.markSpeechDetected(true)
        store.markSpeechDetected(false)
        store.markTranscribing(true)
        store.markTranscribing(false)
        store.appendFinal("Letzter Satz")

        assertEquals(CaptionSessionStatus.STOPPING, store.state.value.status)
        assertEquals(listOf("Letzter Satz"), store.state.value.lines.map { it.text })
    }

    @Test
    fun transcript_isBoundedAndCanBeCleared() {
        val store = CaptionSessionStore()

        repeat(205) { index ->
            store.appendFinal("line " + index)
        }
        store.updatePartial("noch nicht final")

        assertEquals(200, store.state.value.lines.size)
        assertEquals("line 5", store.state.value.lines.first().text)
        assertEquals("line 204", store.state.value.lines.last().text)

        store.clearTranscript()

        assertEquals(emptyList<CaptionLine>(), store.state.value.lines)
        assertEquals("", store.state.value.partialText)
    }

    @Test
    fun stoppingAfterFailure_keepsFailureUntilNextStart() {
        val store = CaptionSessionStore()

        store.markStarting(AsrBackend.PRIMELINE)
        store.markFailure(CaptionFailure.AUDIO_BACKPRESSURE)
        store.markStopped(clearFailure = false)

        assertEquals(CaptionSessionStatus.IDLE, store.state.value.status)
        assertEquals(CaptionFailure.AUDIO_BACKPRESSURE, store.state.value.failure)

        store.markStarting(AsrBackend.NEMOTRON)

        assertNull(store.state.value.failure)
        assertEquals(AsrBackend.NEMOTRON, store.state.value.activeBackend)
    }
}
