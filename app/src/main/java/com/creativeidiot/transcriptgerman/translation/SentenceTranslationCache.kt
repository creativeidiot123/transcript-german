package com.creativeidiot.transcriptgerman.translation

/**
 * Translates German text one sentence at a time and remembers recent sentence translations.
 *
 * Bergamot already splits its input into sentences (ssplit) and translates each sentence
 * independently, so joining per-sentence output preserves the translation while a growing live
 * hypothesis only pays for the sentences that changed instead of the whole utterance again.
 *
 * Owned by one CaptionTranslationPipeline worker: single-threaded, session-scoped, and discarded
 * with the pipeline. Keys are exact German sentence text; entries are evicted least-recently-used.
 */
internal class SentenceTranslationCache(
    private val translator: CaptionTranslator,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val translations =
        object : LinkedHashMap<String, String>(capacity, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) =
                size > capacity
        }

    fun translate(german: String): String =
        splitGermanSentences(german)
            .map { sentence ->
                translations.getOrPut(sentence) {
                    translator.translateGermanToEnglish(sentence).trim()
                }
            }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private companion object {
        const val DEFAULT_CAPACITY = 64
        const val LOAD_FACTOR = 0.75f
    }
}

/**
 * Splits ASR German at sentence punctuation followed by whitespace. A period does not end a
 * sentence after digits (ordinals such as "3. Oktober"), single letters, dotted abbreviations
 * ("z.B."), or common short abbreviations, so those stay inside one translation unit.
 */
internal fun splitGermanSentences(text: String): List<String> {
    val sentences = mutableListOf<String>()
    var start = 0
    for (index in text.indices) {
        val next = index + 1
        if (next < text.length && text[next].isWhitespace() && endsSentence(text, index)) {
            text.substring(start, next).trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
            start = next
        }
    }
    text.substring(start).trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
    return sentences
}

private fun endsSentence(text: String, index: Int): Boolean =
    when (text[index]) {
        '!', '?', '…' -> true
        '.' -> {
            var wordStart = index
            while (wordStart > 0 && !text[wordStart - 1].isWhitespace()) wordStart -= 1
            val word = text.substring(wordStart, index)
            word.length > 1 &&
                word.none { it == '.' || it.isDigit() } &&
                word.lowercase() !in GERMAN_ABBREVIATIONS
        }

        else -> false
    }

private val GERMAN_ABBREVIATIONS = setOf(
    "bzw", "ca", "dr", "evtl", "ggf", "hr", "fr", "inkl", "nr", "prof", "st", "str", "usw", "vgl",
)
