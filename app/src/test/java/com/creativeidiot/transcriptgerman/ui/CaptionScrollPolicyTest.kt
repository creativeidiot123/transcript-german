package com.creativeidiot.transcriptgerman.ui

import com.creativeidiot.transcriptgerman.session.CaptionLine
import com.creativeidiot.transcriptgerman.session.CaptionSessionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionScrollPolicyTest {
    @Test
    fun partialHypothesisUpdates_recheckLiveEdgeVisibility() {
        val before = CaptionSessionState(
            lines = listOf(CaptionLine(id = 4L, text = "Vorherige Zeile")),
            partialText = "Guten",
        )
        val after = before.copy(
            partialText = "Guten Morgen zusammen",
            partialEnglishText = "Good morning everyone",
        )

        assertNotEquals(
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
                targetLiveEdgeVisible = false,
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
                targetLiveEdgeVisible = false,
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
                targetLiveEdgeVisible = false,
                newCaptionItems = 0,
            ),
        )
        assertTrue(
            shouldAutoFollowLiveCaption(
                lastVisibleItemIndex = 8,
                totalItemsCount = 9,
                targetIndex = 8,
                targetLiveEdgeVisible = false,
                newCaptionItems = 0,
            ),
        )
    }

    @Test
    fun visibleLiveEdge_neverRequestsAnotherScroll() {
        assertFalse(
            shouldAutoFollowLiveCaption(
                lastVisibleItemIndex = 8,
                totalItemsCount = 9,
                targetIndex = 8,
                targetLiveEdgeVisible = true,
                newCaptionItems = 1,
            ),
        )
    }

    @Test
    fun growingCaption_scrollsOnlyWhileReaderRemainsAtLiveEdge() {
        assertTrue(
            shouldAutoFollowLiveCaption(
                lastVisibleItemIndex = 8,
                totalItemsCount = 9,
                targetIndex = 8,
                targetLiveEdgeVisible = false,
                newCaptionItems = 0,
            ),
        )
        assertFalse(
            shouldAutoFollowLiveCaption(
                lastVisibleItemIndex = 6,
                totalItemsCount = 9,
                targetIndex = 8,
                targetLiveEdgeVisible = false,
                newCaptionItems = 0,
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
                targetLiveEdgeVisible = false,
                newCaptionItems = 3,
            ),
        )
    }
}
