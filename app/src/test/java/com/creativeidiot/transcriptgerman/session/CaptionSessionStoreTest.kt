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
        assertNull(state.failure)
        assertEquals(listOf("Guten Morgen"), state.lines.map { it.text })
    }

    @Test
    fun activeBackend_survivesScreenRecreationUntilSessionStops() {
        val store = CaptionSessionStore()

        store.markStarting(AsrBackend.NEMOTRON)
        store.markListening()

        assertEquals(AsrBackend.NEMOTRON, store.state.value.activeBackend)

        store.markStopping()
        assertEquals(AsrBackend.NEMOTRON, store.state.value.activeBackend)

        store.markStopped(clearFailure = true)
        assertNull(store.state.value.activeBackend)
    }

    @Test
    fun streamingPartial_replacesUntilFinalized() {
        val store = CaptionSessionStore()
        store.markStarting(AsrBackend.PRIMELINE)
        store.markListening()

        store.updatePartial("Guten")
        store.updatePartial("Guten Morgen")

        assertEquals(CaptionSessionStatus.SPEECH_DETECTED, store.state.value.status)
        assertEquals("Guten Morgen", store.state.value.partialText)
        assertEquals(emptyList<CaptionLine>(), store.state.value.lines)

        store.appendFinal("Guten Morgen.")

        assertEquals("", store.state.value.partialText)
        assertEquals(listOf("Guten Morgen."), store.state.value.lines.map { it.text })
    }

    @Test
    fun translationsPairWithTheirGermanSourceAndRejectStalePartials() {
        val store = CaptionSessionStore()
        store.markStarting(AsrBackend.NEMOTRON)
        store.markListening()

        store.updatePartial("Guten")
        store.updatePartial("Guten Morgen")
        store.updatePartialTranslation("Guten", "Good")
        assertNull(store.state.value.partialEnglishText)

        store.updatePartialTranslation("Guten Morgen", "Good morning")
        assertEquals("Good morning", store.state.value.partialEnglishText)

        val lineId = store.appendFinal("Guten Morgen")
        assertEquals(null, store.state.value.lines.single().englishText)

        store.updateFinalTranslation(requireNotNull(lineId), "Good morning")
        assertEquals("Good morning", store.state.value.lines.single().englishText)
        assertEquals("", store.state.value.partialText)
        assertNull(store.state.value.partialEnglishText)

        store.clearTranscript()
        store.updateFinalTranslation(lineId, "Late translation")
        assertEquals(emptyList<CaptionLine>(), store.state.value.lines)
    }

    @Test
    fun markStopping_preservesTranscriptAndMovesToStopping() {
        val store = CaptionSessionStore()
        store.markStarting(AsrBackend.PRIMELINE)
        store.markListening()
        store.appendFinal("Hallo Welt")

        store.markStopping()
        store.updatePartial("späte Hypothese")

        assertEquals(CaptionSessionStatus.STOPPING, store.state.value.status)
        assertEquals("späte Hypothese", store.state.value.partialText)
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

        assertEquals(200, store.state.value.lines.size)
        assertEquals("line 5", store.state.value.lines.first().text)
        assertEquals("line 204", store.state.value.lines.last().text)

        store.clearTranscript()

        assertEquals(emptyList<CaptionLine>(), store.state.value.lines)
    }

    @Test
    fun stoppingAfterFailure_keepsFailureUntilNextStart() {
        val store = CaptionSessionStore()

        store.markStarting(AsrBackend.PRIMELINE)
        store.updatePartial("discard me")
        store.markFailure(CaptionFailure.AUDIO_BACKPRESSURE)
        store.markStopped(clearFailure = false)

        assertEquals(CaptionSessionStatus.IDLE, store.state.value.status)
        assertEquals("", store.state.value.partialText)
        assertEquals(CaptionFailure.AUDIO_BACKPRESSURE, store.state.value.failure)

        store.markStarting(AsrBackend.PRIMELINE)

        assertNull(store.state.value.failure)
    }
}
