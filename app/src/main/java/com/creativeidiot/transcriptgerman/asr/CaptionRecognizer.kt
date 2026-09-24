package com.creativeidiot.transcriptgerman.asr

internal interface CaptionRecognizer : AutoCloseable {
    fun accept(samples: FloatArray)

    suspend fun finish()
}
