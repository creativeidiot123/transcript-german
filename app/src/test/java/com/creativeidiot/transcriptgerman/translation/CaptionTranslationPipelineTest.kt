package com.creativeidiot.transcriptgerman.translation

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionTranslationPipelineTest {
    @Test
    fun partialsAreConflatedToLatestBeforeTranslationStarts() = runTest {
        val translator = FakeTranslator()
        val partials = mutableListOf<Pair<String, String>>()
        val finals = mutableListOf<Pair<Long, String>>()
        var failed = false

        val pipeline = CaptionTranslationPipeline(
            scope = this,
            translator = translator,
            onPartialTranslated = { german, english ->
                partials += german to english
            },
            onFinalTranslated = { id, english ->
                finals += id to english
            },
            onFailure = { failed = true },
        )

        pipeline.submitPartial("eins")
        pipeline.submitPartial("eins zwei")
        pipeline.submitPartial("eins zwei drei")

        advanceUntilIdle()

        assertEquals(listOf("eins zwei drei"), translator.inputs)
        assertEquals(
            listOf("eins zwei drei" to "EN:eins zwei drei"),
            partials,
        )
        assertTrue(finals.isEmpty())
        assertTrue(!failed)

        pipeline.finishAndDrain()
        assertTrue(translator.closed)
    }

    @Test
    fun finalizedCaptionsAreTranslatedInSubmissionOrder() = runTest {
        val translator = FakeTranslator()
        val finals = mutableListOf<Pair<Long, String>>()

        val pipeline = CaptionTranslationPipeline(
            scope = this,
            translator = translator,
            onPartialTranslated = { _, _ -> },
            onFinalTranslated = { id, english ->
                finals += id to english
            },
            onFailure = { error("translation should not fail") },
        )

        pipeline.submitFinal(7L, "Guten Morgen")
        pipeline.submitFinal(8L, "Wie geht es dir?")
        pipeline.finishAndDrain()

        assertEquals(
            listOf(
                7L to "EN:Guten Morgen",
                8L to "EN:Wie geht es dir?",
            ),
            finals,
        )
        assertTrue(translator.closed)
    }

    @Test
    fun translationFailureSignalsOnceAndClosesModel() = runTest {
        val translator = FailingTranslator()
        var failures = 0

        val pipeline = CaptionTranslationPipeline(
            scope = this,
            translator = translator,
            onPartialTranslated = { _, _ -> error("no partial expected") },
            onFinalTranslated = { _, _ -> error("no final expected") },
            onFailure = { failures += 1 },
        )

        pipeline.submitFinal(1L, "Fehler")
        advanceUntilIdle()
        pipeline.finishAndDrain()

        assertEquals(1, failures)
        assertTrue(translator.closed)
    }

    private class FakeTranslator : CaptionTranslator {
        val inputs = mutableListOf<String>()
        var closed = false

        override fun translateGermanToEnglish(text: String): String {
            inputs += text
            return "EN:$text"
        }

        override fun close() {
            closed = true
        }
    }

    private class FailingTranslator : CaptionTranslator {
        var closed = false

        override fun translateGermanToEnglish(text: String): String {
            error("synthetic translation failure")
        }

        override fun close() {
            closed = true
        }
    }
}
