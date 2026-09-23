# German Live Captions

An Android live-captioning MVP focused on spoken German. The app offers two fully on-device ASR
backends after their model download:

- **Primeline Parakeet:** the existing German-optimized, VAD-segmented offline transducer.
- **Nemotron 3.5:** a multilingual streaming transducer configured explicitly for German.

The model picker is available while idle. Primeline remains the default after process start, and
the selected backend is fixed for the full lifetime of a caption session.

## Recognition stack

### Primeline

- Model: primeline/parakeet-primeline, using the sherpa-onnx-compatible INT8 export from
  flozen1981/parakeet-primeline-onnx.
- Export revision: d548e25b9bfe559aa274f361892dc4ed5d64743a.
- Runtime: sherpa-onnx 1.13.8, CPU inference, four ASR threads.
- Segmentation: Silero VAD at 16 kHz.
- Behavior: near-live, utterance-based captions. Text appears after a short speech pause.

### Nemotron 3.5

- Base model: nvidia/nemotron-3.5-asr-streaming-0.6b.
- sherpa-onnx export: 560-ms INT8 streaming transducer from the csukuangfj2 model repository,
  pinned to revision ab43d895f5985b1bbab8b6eac8607fcdc05343f3.
- Runtime: sherpa-onnx 1.13.8 OnlineRecognizer, CPU inference, four ASR threads.
- Language: German is forced per stream with language=de.
- Behavior: partial text updates while the speaker is talking and is finalized at streaming
  endpoints.

Both backends use microphone-only 16 kHz mono PCM. Audio is never written to disk. Transcript and
partial caption text stay in process memory and are never uploaded or logged.

## Model downloads

Each backend has an independent app-private install directory and revision marker.

Primeline downloads about 671 MB. Nemotron downloads about 682 MB. Files are first written as
partial files, validated, and only then committed. Every asset has an exact expected byte length;
the ONNX graph assets also have pinned SHA-256 digests. An interrupted or invalid download is never
reported ready.

The existing Primeline model/export uses CC BY 4.0. Nemotron model weights are under NVIDIA
OpenMDW-1.1. The Nemotron model family is © NVIDIA CORPORATION & AFFILIATES and is licensed under
the NVIDIA Open Model Data Warehouse License Agreement v1.1.

## Build

Requirements:

- JDK 17
- Android SDK 35
- Gradle 8.10.2

The sherpa-onnx AAR is downloaded from the official sherpa-onnx v1.13.8 release during preBuild and
verified against its published SHA-256.

    gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

CI also assembles the release variant.

## App behavior

1. Choose Primeline or Nemotron 3.5 while idle.
2. Download the selected model if it is not already installed.
3. Tap **Start listening** and grant microphone permission.
4. The foreground microphone service starts with that backend fixed for the session.
5. Primeline emits finalized VAD-segmented utterances; Nemotron updates a current partial phrase and
   finalizes it at streaming endpoints.
6. Tap **Stop listening** in the app or foreground-service notification.
7. **Clear transcript** removes finalized in-memory caption history.

Transcript history, partial text, and the picker selection intentionally live only in process
memory. Process death ends the current session and clears those values; downloaded model files
remain installed.

## Architecture

See architecture.md for ownership, lifecycle, concurrency, and failure semantics, and project.md for
the product contract and verification matrix.
