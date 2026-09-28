package com.creativeidiot.transcriptgerman.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceTranslationCacheTest {
    @Test
    fun split_breaksAtSentencePunctuationFollowedBySpace() {
        assertEquals(
            listOf("Ja.", "Das stimmt!", "Wirklich?", "Und dann…", "weiter"),
            splitGermanSentences("Ja. Das stimmt! Wirklich? Und dann… weiter"),
        )
    }

    @Test
    fun split_keepsOrdinalsInitialsAndAbbreviationsInsideTheSentence() {
        assertEquals(
            listOf("Am 3. Oktober kommt Dr. Müller, z.B. mit A. Schmidt."),
            splitGermanSentences("Am 3. Oktober kommt Dr. Müller, z.B. mit A. Schmidt."),
        )
    }

    @Test
    fun split_ignoresPunctuationWithoutFollowingSpaceAndBlankInput() {
        assertEquals(listOf("Version 1.5 ist gut."), splitGermanSentences("Version 1.5 ist gut."))
        assertEquals(emptyList<String>(), splitGermanSentences("   "))
    }

    @Test
    fun translate_reusesUnchangedSentencesAndEvictsLeastRecentlyUsed() {
        val inputs = mutableListOf<String>()
        val cache = SentenceTranslationCache(
            translator = object : CaptionTranslator {
                override fun translateGermanToEnglish(text: String): String {
                    inputs += text
                    return " EN:$text "
                }

                override fun close() = Unit
            },
            capacity = 2,
        )

        assertEquals("EN:Eins. EN:Zwei.", cache.translate("Eins. Zwei."))
        assertEquals("EN:Eins. EN:Zwei. EN:Drei", cache.translate("Eins. Zwei. Drei"))
        // "Eins." was least recently used when "Drei" was added, so it is translated again.
        assertEquals("EN:Eins.", cache.translate("Eins."))

        assertEquals(listOf("Eins.", "Zwei.", "Drei", "Eins."), inputs)
    }
}
