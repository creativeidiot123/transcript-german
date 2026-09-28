package com.creativeidiot.transcriptgerman.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class CanaryTokensTest {
    @Test
    fun restoreCanaryWordMarkers_turnsPinnedLeadingSpacesIntoSherpaWordMarkers() {
        // Line shapes from the pinned Hugging Face tokens.txt, including the bare word-marker token.
        val pinned = "<|endoftext|> 3\n  1151\n der 1340\ner 1206\n Straße 4711\n„ 5190\n"

        assertEquals(
            "<|endoftext|> 3\n\u2581 1151\n\u2581der 1340\ner 1206\n\u2581Straße 4711\n„ 5190\n",
            restoreCanaryWordMarkers(pinned),
        )
    }

    @Test
    fun restoreCanaryWordMarkers_leavesAlreadyMarkedTokensUnchanged() {
        val official = "\u2581 1151\n\u2581der 1340\ner 1206\n"

        assertEquals(official, restoreCanaryWordMarkers(official))
    }
}
