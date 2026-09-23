package com.creativeidiot.transcriptgerman.model

import java.io.File

internal data class ModelFileSpec(
    val name: String,
    val url: String,
    val exactBytes: Long? = null,
    val minimumBytes: Long = 1,
    val sha256: String? = null,
)

internal data class ModelBundleSpec(
    val revision: String,
    val directoryName: String,
    val files: List<ModelFileSpec>,
) {
    fun directory(filesDir: File): File = File(File(filesDir, "models"), directoryName)
}

internal object PrimelineModelSpec {
    const val REVISION = "d548e25b9bfe559aa274f361892dc4ed5d64743a"

    private const val MODEL_BASE =
        "https://huggingface.co/flozen1981/parakeet-primeline-onnx/resolve/" +
            REVISION + "/"
    private const val SHERPA_MODELS =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

    val bundle = ModelBundleSpec(
        revision = REVISION,
        directoryName = "parakeet-primeline-" + REVISION,
        files = listOf(
            ModelFileSpec(
                name = "encoder.int8.onnx",
                url = MODEL_BASE + "encoder.int8.onnx?download=true",
                exactBytes = 1_548_009,
                sha256 = "d4232f86718da0330167fb10789d1a35cffe6a60cd58239957943a8d9bc24c63",
            ),
            ModelFileSpec(
                name = "encoder.int8.onnx.data",
                url = MODEL_BASE + "encoder.int8.onnx.data?download=true",
                exactBytes = 650_776_320,
                sha256 = "d3b0d27912043d38a3c2ce4f2c03124be30bc1ff57d976302908954b2e8fe7bb",
            ),
            ModelFileSpec(
                name = "decoder.int8.onnx",
                url = MODEL_BASE + "decoder.int8.onnx?download=true",
                exactBytes = 11_845_275,
                sha256 = "fb4ddefe200706cabb27ee3fc1c81efa50555a4c8a8e00b663cc795216fb9369",
            ),
            ModelFileSpec(
                name = "joiner.int8.onnx",
                url = MODEL_BASE + "joiner.int8.onnx?download=true",
                exactBytes = 6_355_277,
                sha256 = "8220c0d117d81bdd0d8c770881932ac340f1ce4b36932941d561d11ad1aaffce",
            ),
            ModelFileSpec(
                name = "tokens.txt",
                url = MODEL_BASE + "tokens.txt?download=true",
                exactBytes = 102_132,
                sha256 = "ba8e4007c65f4bb4358ffe2ecc13d9ccc7a10351151065242b5c3a943e685742",
            ),
            ModelFileSpec(
                name = "silero_vad.onnx",
                url = SHERPA_MODELS + "silero_vad.onnx",
                exactBytes = 643_854,
                sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
            ),
        ),
    )
}
