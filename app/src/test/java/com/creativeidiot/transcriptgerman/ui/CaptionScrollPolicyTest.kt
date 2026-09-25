package com.creativeidiot.transcriptgerman.ui

import com.creativeidiot.transcriptgerman.session.CaptionLine
import com.creativeidiot.transcriptgerman.session.CaptionSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionScrollPolicyTest {
    @Test
    fun partialHypothesisUpdates_keepTheSameAutoFollowTrigger() {
        val before = CaptionSessionState(
            lines = listOf(CaptionLine(id = 4L, text = "Vorherige Zeile")),
            partialText = "Guten",
        )
        val after = before.copy(
            partialText = "Guten Morgen zusammen",
            partialEnglishText = "Good morning everyone",
        )

        assertEquals(
            captionAutoFollowTrigger(before),
            captionAutoFollowTrigger(after),
        )
    }

    @Test
    fun finalizingOrStartingANewPartial_changesTheAutoFollowTrigger() {
        val partial = CaptionSessionState(partialText = "Guten Morgen")
        val finalized = CaptionSessionState(
            lines = listOf(CaptionLine(id = 9L, text = "Guten Morgen")),
        )
        val nextPartial = finalized.copy(partialText = "Wie geht")

        val partialTrigger = captionAutoFollowTrigger(partial)
        val finalizedTrigger = captionAutoFollowTrigger(finalized)
        val nextPartialTrigger = captionAutoFollowTrigger(nextPartial)

        assertFalse(partialTrigger == finalizedTrigger)
        assertFalse(finalizedTrigger == nextPartialTrigger)
    }

    @Test
    fun newCaption_followsWhenReaderWasAtPreviousLiveEdge() {
        assertTrue(
            shouldAutoFollowLiveCaption(
                lastVisibleItemIndex = 7,
                totalItemsCount = 9,
                targetIndex = 8,
                targetFullyVisible = false,
                newCaptionItems = 1,
            ),
        )
    }

    @Test
    fun newCaption_doesNotInterruptReaderWhoScrolledIntoHistory() {
        assertFalse(
            shouldAutoFollowLiveCaption(
                lastVisibleItemIndex = 6,
                totalItemsCount = 9,
                targetIndex = 8,
                targetFullyVisible = false,
                newCaptionItems = 1,
            ),
        )
    }

    @Test
    fun replacementAtLiveEdge_requiresReaderToStillBeAtLiveEdge() {
        assertFalse(
            shouldAutoFollowLiveCaption(
                lastVisibleItemIndex = 7,
                totalItemsCount = 9,
                targetIndex = 8,
                targetFullyVisible = false,
                newCaptionItems = 0,
            ),
        )
        assertTrue(
            shouldAutoFollowLiveCaption(
                lastVisibleItemIndex = 8,
                totalItemsCount = 9,
                targetIndex = 8,
                targetFullyVisible = false,
                newCaptionItems = 0,
            ),
        )
    }

    @Test
    fun fullyVisibleTarget_neverRequestsAnotherScroll() {
        assertFalse(
            shouldAutoFollowLiveCaption(
                lastVisibleItemIndex = 8,
                totalItemsCount = 9,
                targetIndex = 8,
                targetFullyVisible = true,
                newCaptionItems = 1,
            ),
        )
    }

    @Test
    fun multipleConflatedNewCaptions_stillFollowFromOldLiveEdge() {
        assertTrue(
            shouldAutoFollowLiveCaption(
                lastVisibleItemIndex = 5,
                totalItemsCount = 9,
                targetIndex = 8,
                targetFullyVisible = false,
                newCaptionItems = 3,
            ),
        )
    }
}
