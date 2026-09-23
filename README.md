# German Live Captions

An Android live-captioning MVP focused on spoken German. After the first model download,
speech recognition runs fully on-device with the German-optimized
[Primeline Parakeet](https://huggingface.co/primeline/parakeet-primeline) model.

## Recognition stack

- **ASR model:** `primeline/parakeet-primeline`, using the sherpa-onnx-compatible INT8 export
  from `flozen1981/parakeet-primeline-onnx`, pinned to revision
  `d548e25b9bfe559aa274f361892dc4ed5d64743a`.
- **Runtime:** sherpa-onnx 1.13.8, CPU inference, four ASR threads.
- **Segmentation:** Silero VAD at 16 kHz.
- **Audio:** microphone only, 16 kHz mono PCM.
- **Privacy:** audio is never written to disk and transcript text is never sent to a server or
  written to logs.

The Primeline export is a non-streaming transducer. This app is therefore **near-live,
utterance-based captions**: text appears after a short speech pause rather than token-by-token.

## First-run model download

The app downloads about 671 MB into its private app storage. The large ONNX assets and Silero VAD
are SHA-256 verified. Model installation becomes ready only after every required file has finished
and a revision install marker is committed. Interrupted `.part` files are never accepted as a
valid model.

The Primeline model/export uses CC BY 4.0. Attribution belongs to PrimeLine Solutions for the
German fine-tune and NVIDIA for the Parakeet base model/architecture; see the linked model cards
for the complete upstream notices.

## Build

Requirements:

- JDK 17
- Android SDK 35
- Gradle 8.10.2

The sherpa-onnx AAR is downloaded from the official sherpa-onnx v1.13.8 release during
`preBuild` and verified against its published SHA-256.

```bash
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

CI also assembles the release variant.

## MVP behavior

1. Download the pinned German model.
2. Tap **Start listening** and grant microphone permission.
3. The foreground microphone service segments speech with VAD and transcribes finalized
   utterances locally.
4. Tap **Stop listening** in the app or foreground-service notification.
5. **Clear transcript** removes the in-memory caption history.

Transcript history intentionally lives only in process memory. Process death ends the current
session and clears transcript history; downloaded model files remain installed.

## Architecture

See [architecture.md](architecture.md) for ownership, lifecycle, concurrency, and failure
semantics, and [project.md](project.md) for the product contract and verification matrix.
