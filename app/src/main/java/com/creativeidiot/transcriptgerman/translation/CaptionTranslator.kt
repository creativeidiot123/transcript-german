package com.creativeidiot.transcriptgerman.translation

import android.content.Context
import io.github.marcosholgado.translatekit.ModelSpec
import io.github.marcosholgado.translatekit.TranslateKit
import io.github.marcosholgado.translatekit.TranslationModel

internal interface CaptionTranslator : AutoCloseable {
    fun translateGermanToEnglish(text: String): String
}

internal class BergamotTranslator(
    context: Context,
    files: BergamotModelFiles,
) : CaptionTranslator {
    private val model: TranslationModel

    init {
        TranslateKit.init(context.applicationContext)
        check(TranslateKit.isInitialized()) {
            "Bergamot native translation runtime is unavailable"
        }

        model = TranslateKit.loadModel(
            ModelSpec(
                sourceLang = "de",
                targetLang = "en",
                modelPath = files.model.absolutePath,
                vocabPaths = files.vocabs.map { it.absolutePath },
                shortlistPath = files.shortlist.absolutePath,
                configYaml = files.config.absolutePath,
                numWorkers = 1,
            ),
        )
    }

    override fun translateGermanToEnglish(text: String): String =
        model.translate(text, isHtml = false).text.trim()

    override fun close() {
        model.close()
    }
}
