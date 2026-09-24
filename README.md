# German + English Live Captions

An Android live-captioning MVP for spoken German with paired local English translation.

The app offers three German ASR backends:

- **Primeline Parakeet:** on-device German-optimized VAD-segmented ASR.
- **Nemotron 3.5:** on-device streaming ASR.
- **Gemini 3.5 Transcribe Live:** cloud streaming ASR using a user-provided Gemini API key.

All three feed one shared **Bergamot de-en-base INT8** translation stage, so finalized German
captions are paired with English. Nemotron and Gemini also expose live German partials that feed the
latest-only partial translation path.

## Privacy model

Primeline and Nemotron keep microphone audio on-device. Gemini is deliberately different: when
Gemini is selected, microphone audio is streamed over TLS to Google's Gemini Live API for
transcription.

The Gemini API key is entered inside the app. It is encrypted at rest with an Android Keystore
AES-GCM key, saved across app restarts, excluded from backup, never committed to the repository or
BuildConfig, and removable from the UI. The app does not log the key, request URL, audio, or
transcript text.

German/English caption state itself remains process-memory only.

## Recognition stack

### Primeline

- primeline/parakeet-primeline
- sherpa-onnx 1.13.8, CPU, four ASR threads
- Silero VAD at 16 kHz
- utterance-based finalized German captions

### Nemotron 3.5

- nvidia/nemotron-3.5-asr-streaming-0.6b
- 560-ms INT8 sherpa-onnx export pinned to
  ab43d895f5985b1bbab8b6eac8607fcdc05343f3
- persistent OnlineRecognizer, language forced to German
- streaming German partials and endpoint finals

### Gemini 3.5 Transcribe Live

- model: gemini-3.5-transcribe-live
- Gemini Live v1beta BidiGenerateContent WebSocket
- raw mono signed 16-bit little-endian PCM at 16 kHz
- existing app chunks are 100 ms
- de-DE language hint
- interim and finalized input transcription callbacks
- documented continuous Live transcription session limit: up to 10 minutes
- no automatic reconnect or fallback; a failed/expired session stops visibly and can be restarted

Google recommends short-lived ephemeral tokens for production mobile clients. This app intentionally
uses a user-provided persistent API key because its product contract is BYO-key. The key is not
embedded in the APK.

## Translation stack

- Bergamot de-en-base, version 2 / API 1
- archive SHA-256:
  caa7c0ce3c8eaf05d333dc9458683f4b0375e5eeb604f6fb2c8585f7b70d398b
- translate-kit 0.1.0 from pinned upstream commit
  2dcdcb1559ed405d65ae1ff1e786d3a5ebb933c4
- translation is serialized through one loaded model
- finalized captions remain ordered
- live partial translation is latest-only

## Setup

For Primeline or Nemotron:

1. Select the backend.
2. Download its local speech model.
3. Download Bergamot.
4. Start listening.

For Gemini:

1. Select **Gemini 3.5 Transcribe Live**.
2. Enter your Gemini API key and tap **Save key**.
3. Download Bergamot if needed.
4. Start listening.

The saved Gemini key can be replaced or removed while no caption session is active.

## Model downloads

Primeline downloads about 671 MB. Nemotron downloads about 682 MB. Their model assets are revision
pinned and verified before install markers are committed.

Bergamot is downloaded into a separate app-private directory and its official archive is SHA-256
verified before safe extraction/commit.

Only one model download runs at a time.

## Session behavior

The selected recognition backend is fixed for the session. CaptionService owns the microphone,
selected recognizer, one Bergamot translation pipeline, and all teardown.

Audio flows through one bounded 64-element queue. Queue saturation stops the session rather than
silently dropping speech.

For Gemini, microphone capture starts only after the Live WebSocket setup handshake succeeds. On
Stop, accepted app audio drains, audioStreamEnd is sent, trailing final transcription is allowed a
bounded finalization window, final translations drain, then resources close.

## Build

Requirements:

- JDK 17
- Android SDK 35
- Gradle 8.10.2
- arm64-v8a shipping ABI

Runtime libraries include sherpa-onnx 1.13.8, translate-kit 0.1.0, Apache Commons Compress 1.28.0,
and OkHttp 4.12.0.

    gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

CI also assembles release and uploads the debug APK artifact.

## Architecture

See architecture.md for ownership/lifecycle semantics and project.md for the product contract,
privacy policy, and verification matrix.
