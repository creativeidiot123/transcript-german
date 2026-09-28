package com.creativeidiot.transcriptgerman.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChangedWordsTextTest {
    @Test
    fun appendedWords_keepEarlierWordsStable() {
        val current = "Good morning everyone"

        assertEquals("Good morning".length, stableWordPrefixLength("Good morning", current))
    }

    @Test
    fun revisedWord_marksItAndEverythingAfterAsChanged() {
        assertEquals(
            "I am".length,
            stableWordPrefixLength("I am going home", "I am coming home"),
        )
        assertEquals(
            "Good".length,
            stableWordPrefixLength("Good morning", "Good morning."),
        )
    }

    @Test
    fun unchangedOrShortenedText_hasNoChangedWords() {
        assertEquals(12, stableWordPrefixLength("Good morning", "Good morning"))
        assertEquals(4, stableWordPrefixLength("Good morning", "Good"))
    }

    @Test
    fun placeholderOrNothingShownBefore_marksWholeTextChanged() {
        assertEquals(0, stableWordPrefixLength(null, "Hello"))
        assertEquals(0, stableWordPrefixLength("Translating…", "Hello there"))
        assertEquals(0, stableWordPrefixLength("Hello", "Well hello"))
    }
}
