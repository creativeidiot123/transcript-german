package com.creativeidiot.transcriptgerman.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionScrollPolicyTest {
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
