# German + English Live Captions

An Android live-captioning MVP for spoken German with paired on-device English translation.

The app offers two German ASR backends:

- **Primeline Parakeet:** German-optimized, Silero-VAD segmented offline transducer.
- **Nemotron 3.5:** multilingual streaming transducer configured explicitly for German.

Both feed one shared **Bergamot de-en-base INT8** translation stage, so every finalized caption is
shown as German plus English. Nemotron also translates the newest live partial while the speaker is
talking. Primeline has no ASR partial stream, so its English caption follows each finalized German
utterance.

## Recognition stack

### Primeline

- Model: primeline/parakeet-primeline using the sherpa-onnx-compatible INT8 export from
  flozen1981/parakeet-primeline-onnx.
- Export revision: d548e25b9bfe559aa274f361892dc4ed5d64743a.
- Runtime: sherpa-onnx 1.13.8, CPU inference, four ASR threads.
- Segmentation: Silero VAD at 16 kHz.
- Behavior: near-live, utterance-based German captions.

### Nemotron 3.5

- Base model: nvidia/nemotron-3.5-asr-streaming-0.6b.
- sherpa-onnx export: 560-ms INT8 streaming transducer from csukuangfj2, pinned to revision
  ab43d895f5985b1bbab8b6eac8607fcdc05343f3.
- Runtime: sherpa-onnx 1.13.8 OnlineRecognizer, CPU inference, four ASR threads.
- Language: German forced per stream with `language=de`.
- Behavior: German partial text updates while speaking and finalizes at streaming endpoints.

## Translation stack

- Model: Bergamot **German-English base** (`de-en-base`), version 2 / API 1.
- Official archive:
  `deen.student.base.v2.caa7c0ce3c8eaf05.tar.gz`.
- Archive SHA-256:
  `caa7c0ce3c8eaf05d333dc9458683f4b0375e5eeb604f6fb2c8585f7b70d398b`.
- Runtime: translate-kit 0.1.0, a thin Android JNI/Kotlin wrapper around the Bergamot/Marian
  inference engine, built from pinned upstream commit
  `2dcdcb1559ed405d65ae1ff1e786d3a5ebb933c4`.
- The pinned translate-kit AAR is reproduced once from that upstream source and published as a
  repository release asset. Local/CI builds download it and verify SHA-256
  `e0da38118cd27504a1b6a0037818e7e490f3a4eff1930205e0bb5b774163d60e`.
- Translation calls are serialized because one loaded Bergamot model is not thread-safe.
- Final captions are translated in order. Live Nemotron partial translation is latest-wins: stale
  partials collapse instead of creating an unbounded translation backlog.

## Model downloads

Primeline, Nemotron, and Bergamot use separate app-private install directories.

Primeline downloads about 671 MB and Nemotron about 682 MB. Their current verifier uses pinned
revisions, exact expected asset sizes, and SHA-256 for ONNX graph assets.

Bergamot is downloaded as the official de-en-base archive. The complete tar.gz is SHA-256 verified
before extraction. Extraction happens in an app-private staging directory, rejects links/path
traversal, verifies the required model/vocabulary/shortlist/config files, writes a pinned archive
marker, and only then commits the install directory.

Only one model download runs at a time across all three bundles.

## Live behavior

1. Choose Primeline or Nemotron while idle.
2. Download the selected German ASR model.
3. Download Bergamot de-en-base INT8.
4. Tap **Start listening** and grant microphone permission.
5. The foreground service fixes the selected ASR backend for the session and loads one Bergamot
   German→English translator.
6. German appears from ASR. English is attached to the same caption source:
   - Primeline: paired translation after every finalized VAD utterance.
   - Nemotron: latest German/English partials while speaking, then one paired finalized line.
7. **Stop** drains accepted audio, ASR finalization, and accepted final translations before native
   resources are released.
8. **Clear transcript** removes finalized bilingual history.

A translation runtime failure stops the session visibly rather than silently switching to
German-only output.

## Privacy and lifecycle

Microphone audio is never written to disk. German captions, English translations, and current
partials stay in process memory and are never uploaded or logged. Final history is bounded to the
latest 200 lines.

Process death ends the current session and clears caption/backend-selection memory. Downloaded
models stay in app-private storage. App backup is disabled.

## Build

Requirements:

- JDK 17
- Android SDK 35
- Gradle 8.10.2
- arm64-v8a shipping ABI

The build downloads and checksum-verifies the pinned sherpa-onnx 1.13.8 AAR and pinned
translate-kit 0.1.0 AAR. Apache Commons Compress 1.28.0 is used for Bergamot tar.gz extraction.

    gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

CI also assembles the release variant and publishes the debug APK as an artifact.

## Licensing/provenance

Primeline retains its existing CC BY 4.0 model/export terms. Nemotron model weights use NVIDIA
OpenMDW-1.1.

translate-kit is Apache-2.0 and statically links the Bergamot translation layer (MPL-2.0) plus the
third-party components documented by that upstream project. The Bergamot German-English base model is CC-BY-SA-4.0 and is downloaded at runtime rather than embedded in the APK. Exact runtime/model provenance is pinned above so a
shipped binary can be traced to its source/runtime inputs.

## Architecture

See `architecture.md` for ownership, lifecycle, concurrency, and failure semantics, and
`project.md` for the product contract and verification matrix.
