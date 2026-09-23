package com.creativeidiot.transcriptgerman.asr

internal interface CaptionRecognizer : AutoCloseable {
    fun accept(samples: FloatArray)
    fun finish()
}
