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

internal object ModelCatalog {
    fun bundleFor(backend: AsrBackend): ModelBundleSpec? =
        when (backend) {
            AsrBackend.PRIMELINE -> PrimelineModelSpec.bundle
            AsrBackend.NEMOTRON -> NemotronModelSpec.bundle
            AsrBackend.CANARY -> CanaryModelSpec.bundle
            AsrBackend.GEMINI -> null
        }
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

internal object CanaryModelSpec {
    const val REVISION = "b3fd7d9883a92f767be20b3792b9d54883a2f18f"

    private const val MODEL_BASE =
        "https://huggingface.co/csukuangfj/" +
            "sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8/resolve/" +
            REVISION + "/"
    private const val SHERPA_MODELS =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

    val bundle = ModelBundleSpec(
        revision = REVISION,
        directoryName = "canary-180m-flash-" + REVISION,
        files = listOf(
            ModelFileSpec(
                name = "encoder.int8.onnx",
                url = MODEL_BASE + "encoder.int8.onnx?download=true",
                exactBytes = 132_678_643,
                sha256 = "7a75b4e2a5857a6dcc0819503bbe3fad66943db4a3ccf21d3f27c633667d303f",
            ),
            ModelFileSpec(
                name = "decoder.int8.onnx",
                url = MODEL_BASE + "decoder.int8.onnx?download=true",
                exactBytes = 74_437_848,
                sha256 = "e41a2ab9c0c2fe81a1e8ade5a45fb02a74bc4db7d1f91b89a54a25e2cf79cba2",
            ),
            ModelFileSpec(
                name = "tokens.txt",
                url = MODEL_BASE + "tokens.txt?download=true",
                exactBytes = 50_407,
                sha256 = "334642526f436058a5564ea0e3e9bd45bf400bd8362f047f2869639a5589d0ae",
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

internal object NemotronModelSpec {
    const val REVISION = "ab43d895f5985b1bbab8b6eac8607fcdc05343f3"

    private const val MODEL_BASE =
        "https://huggingface.co/csukuangfj2/" +
            "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11/resolve/" +
            REVISION + "/"

    val bundle = ModelBundleSpec(
        revision = REVISION,
        directoryName = "nemotron-3.5-streaming-560ms-" + REVISION,
        files = listOf(
            ModelFileSpec(
                name = "encoder.int8.onnx",
                url = MODEL_BASE + "encoder.int8.onnx?download=true",
                exactBytes = 657_601_403,
                sha256 = "012e9321373af99021415e0b0eb3ec827b4be3153be6f30d9b448fe65e896e68",
            ),
            ModelFileSpec(
                name = "decoder.int8.onnx",
                url = MODEL_BASE + "decoder.int8.onnx?download=true",
                exactBytes = 14_978_075,
                sha256 = "19f9c98fc6d0a2c33a65a43b36fdb2e914c26c0aa9764be3aebc502a1e982fb0",
            ),
            ModelFileSpec(
                name = "joiner.int8.onnx",
                url = MODEL_BASE + "joiner.int8.onnx?download=true",
                exactBytes = 9_504_438,
                sha256 = "4101c7c679a0bc30483794b27a059e34e79232aa2068d78d51231a22c8b0d7ce",
            ),
            ModelFileSpec(
                name = "tokens.txt",
                url = MODEL_BASE + "tokens.txt?download=true",
                exactBytes = 131_440,
            ),
        ),
    )
}
