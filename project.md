# Project contract

## Product

**German + English Live Captions** is an Android MVP that turns nearby spoken German into paired
German and English captions. The user selects one of three German ASR backends:

- Primeline Parakeet: on-device, VAD-segmented offline ASR.
- Nemotron 3.5: on-device streaming ASR.
- Gemini 3.5 Transcribe Live: cloud streaming ASR over the Gemini Live API.

All three feed one shared on-device Bergamot de-en-base INT8 German-to-English translation stage.
Primeline and Nemotron keep microphone audio on-device. Gemini is an explicit cloud choice and
streams microphone audio to Google for transcription.

### Required journeys

1. **Choose backend:** while idle, the user can select Primeline, Nemotron 3.5, or Gemini 3.5
   Transcribe Live. Primeline is the default after process start. The picker is locked while any
   model download or caption session is active.
2. **Prepare selected backend:** Primeline/Nemotron require their pinned local ASR bundle. Gemini
   instead requires a user-provided Gemini API key saved in the app. Bergamot is required for all
   three backends. A failed/partial local model install is never reported ready.
3. **Manage Gemini credential:** while idle, the user can save, replace, or remove the Gemini API
   key. The key is encrypted using an Android Keystore AES-GCM key and persisted in app-private
   SharedPreferences across app restarts. The plaintext key is never exposed through UI state,
   logs, notifications, backups, or repository files.
4. **Start captions:** Start is available only when the selected recognition backend is ready and
   Bergamot is ready. Microphone permission is requested only at this point. The selected backend
   is passed explicitly to the foreground service and remains fixed for the session.
5. **Caption + translate speech:** 16 kHz mono audio is kept in memory. Primeline emits finalized
   VAD utterances. Nemotron exposes replaceable German partials and endpoint finals. Gemini streams
   raw 16-bit PCM to the Gemini Live API with a de-DE language hint and receives interim/final
   German transcripts. All finals and available live partials feed the shared Bergamot translation
   stage.
6. **Stop captions:** app and notification Stop actions are idempotent. Accepted audio drains on a
   user stop, the selected recognizer flushes/finalizes, queued finalized translations drain,
   native/network resources are released, and the foreground service ends.
7. **Clear transcript:** finalized bilingual caption history can be removed without affecting
   installed models or the saved Gemini API key.

### Observable behavior

- Selected Primeline/Nemotron model missing: Start is disabled and Download is available.
- Gemini key missing: Start is disabled and the key-entry UI is available.
- Bergamot model missing: Start is disabled and its separate Download action is available.
- Downloading: progress is visible when the server exposes total bytes.
- Download/integrity failure: no Ready state; Retry is available.
- Backend changes and Gemini credential changes are blocked while listening.
- Microphone denial: no service starts; an explanatory message is shown.
- Listening: a persistent foreground-service notification is posted.
- Primeline speech: finalized German appears after a VAD endpoint; English follows from the same
  finalized source.
- Nemotron speech: current German partial text updates in place; English partial text follows only
  the newest German hypothesis.
- Gemini speech: interim German text updates from Gemini Live; finalized Gemini input transcription
  becomes one final German caption and then receives local Bergamot English translation.
- Gemini connection/auth/session failure stops visibly rather than silently switching backends or
  dropping accepted speech.
- ASR queue overload or translation failure stops visibly rather than silently dropping speech or
  degrading to German-only output.
- Process death ends active capture and clears in-memory captions/backend selection. Local models
  and the encrypted Gemini key remain.

### Canonical facts and owners

- Installed local ASR truth: ModelInstallVerifier over app-private backend files plus revision
  marker. ModelRepository owns local ASR installation mutation.
- Installed translation truth: BergamotModelInstallVerifier over the app-private extracted bundle
  plus the pinned archive-SHA marker. BergamotModelRepository owns translation installation.
- Gemini API key truth: GeminiApiKeyStore over app-private encrypted SharedPreferences; the AES-GCM
  key material is owned by Android Keystore. Only GeminiApiKeyStore may persist/decrypt the key.
- Model download serialization: one application-owned mutex shared by both model repositories.
- Selected ASR backend: CaptionViewModel, process-local; defaults to Primeline after process death.
- Active-session backend: immutable CaptionService start input.
- German/English transcript and current partial truth: CaptionSessionStore, process-local.
- Translation scheduling/native model lifecycle: CaptionTranslationPipeline + BergamotTranslator,
  owned by the active CaptionService session.
- Microphone/ASR/network session lifecycle: CaptionService.
- Gemini Live WebSocket: GeminiLiveRecognizer, one socket per Gemini caption session.
- Runtime microphone permission launcher: MainActivity.
- UI projection: CaptionViewModel.

### Concurrency and retry

- All model downloads are serialized by one application-owned mutex.
- Backend selection and Gemini credential mutation are accepted only while the session is idle.
- Caption Start is first-wins while a session job is active.
- Stop is idempotent.
- Audio uses a bounded queue of 64 100-ms chunks. Saturation is terminal because dropping chunks
  would make captions deceptively incomplete.
- Gemini uses one WebSocket per session. Audio sends preserve queue order. Async socket failures
  terminate the active session; the app does not auto-reconnect or silently fall back to another
  recognizer.
- Translation uses one serialized worker because the Bergamot model is not thread-safe. Final
  captions use a bounded ordered queue. Partial translation is conflated to the newest German
  hypothesis.
- A translated partial is committed only if its German source still equals the current partial.
- On explicit user stop, final translations already accepted by the translation worker drain before
  the session is marked stopped.
- Downloads are retried only by explicit user action. Gemini connection retry is a new Start action.

### Data lifecycle and privacy

- Primeline/Nemotron microphone audio: memory only, never persisted, uploaded, or logged.
- Gemini microphone audio: memory only locally, but streamed over TLS to Google while Gemini is the
  selected active backend.
- Gemini API key: user-provided; encrypted at rest with an Android Keystore AES-GCM key, persisted
  across app restarts, never backed up or logged, removable from the UI, and sent to Google only as
  authentication for the Gemini Live WebSocket.
- German and English captions/partials: process memory only, finalized history max 200 paired lines,
  never written to app logs or persistent transcript storage.
- ASR, VAD, and Bergamot model files: app-private filesDir/models until app data is removed.
- Selected backend: process memory only; not persisted.
- Accounts/analytics: none.
- App backup: disabled.

### Permissions and platform behavior

- RECORD_AUDIO is requested at point of use.
- INTERNET is required for model downloads and Gemini Live sessions.
- Foreground service permissions and microphone service type are declared.
- The microphone foreground service is started only from a visible Activity user action.
- Target/compile SDK: 35. Minimum SDK: 29. Shipping ABI: arm64-v8a.
- Notification runtime permission is not requested in this MVP.

### Performance/storage assumptions

- Primeline download is approximately 671 MB.
- Nemotron 3.5 560-ms INT8 download is approximately 682 MB.
- Bergamot de-en-base is a separately downloaded quantized student-model archive.
- CPU inference uses four ASR threads. Primeline additionally uses one Silero VAD thread.
- Bergamot translation is serialized through one model worker.
- Gemini latency and availability depend on network/API conditions. Gemini 3.5 Transcribe Live
  supports continuous Live transcription sessions for up to 10 minutes; a closed/expired session
  stops visibly and requires Start again.
- No claim is made yet about real-time factor, battery, thermals, memory pressure, network usage, or
  recognition/translation quality on arbitrary Android devices.

### External contracts

- sherpa-onnx runtime: 1.13.8.
- Primeline-compatible INT8 export revision:
  d548e25b9bfe559aa274f361892dc4ed5d64743a.
- Primeline recognizer model type: nemo_transducer, decoding: greedy_search.
- Silero VAD asset: sherpa-onnx asr-models/silero_vad.onnx.
- Nemotron base model: nvidia/nemotron-3.5-asr-streaming-0.6b.
- Nemotron sherpa-onnx 560-ms INT8 export revision:
  ab43d895f5985b1bbab8b6eac8607fcdc05343f3.
- Nemotron recognizer: sherpa-onnx OnlineRecognizer, greedy_search, per-stream language=de.
- Gemini model: gemini-3.5-transcribe-live through the Gemini Live v1beta BidiGenerateContent
  WebSocket endpoint, response modality TEXT, input transcription language code de-DE.
- Gemini audio contract: raw mono signed 16-bit little-endian PCM at 16 kHz, sent in the existing
  100-ms app audio chunks.
- Gemini auth policy for this app: a user-provided standard API key is sent only on the TLS
  WebSocket request. Google recommends ephemeral tokens for production client apps; this product
  intentionally uses BYO persisted API keys per user request and exposes that cloud/privacy boundary
  in the UI.
- OkHttp runtime for Gemini WebSocket: 4.12.0.
- Bergamot catalog model: de-en-base version 2, API 1, CC-BY-SA-4.0.
- Bergamot archive SHA-256:
  caa7c0ce3c8eaf05d333dc9458683f4b0375e5eeb604f6fb2c8585f7b70d398b.
- Bergamot Android JNI wrapper: translate-kit 0.1.0, built from pinned upstream commit
  2dcdcb1559ed405d65ae1ff1e786d3a5ebb933c4 and consumed as a checksum-pinned AAR.
- Apache Commons Compress is used only for safe extraction of the pinned tar.gz bundle.
- Model/runtime downloads use HTTPS and pinned revisions, checksums, or exact asset metadata before
  commit.

- Release signing is opt-in through the manual `Signed AAB` GitHub Actions workflow. The job is
  bound to a protected `release` environment with a required reviewer and `main`-only deployment
  rule; signing material is supplied
  only through environment-scoped Actions secrets and a runner-temporary keystore. Normal PR/main
  verification stays unsigned.

### Non-goals for this MVP

No cloud translation, no Gemini conversational responses, audio recording, transcript
persistence/export, speaker diarization, floating overlay, accessibility service, account system,
background auto-start, automatic backend benchmarking, or automatic cloud/local fallback.

### Forbidden outcomes

- Starting microphone capture without runtime microphone permission.
- Treating a partial/corrupt local ASR or Bergamot model as installed.
- Switching recognizer implementation inside an active session.
- Uploading microphone audio when Primeline or Nemotron is selected.
- Persisting or logging a plaintext Gemini API key, exposing it through UI state, committing it to
  source/build config, or backing it up.
- Attaching an English partial translated from an older German hypothesis to newer German text.
- Reordering finalized English translations relative to their German source lines.
- Silently dropping queued audio/finalized translation work.
- Silently degrading a requested bilingual session to German-only after translation failure.
- Silently falling back from Gemini to a local backend after cloud failure.
- Logging audio/transcript/translation content.
- Leaking AudioRecord, native ASR/Bergamot resources, or Gemini WebSocket resources after
  stop/failure.
- Auto-restarting microphone capture after process death or reboot.

## Verification matrix

| Category | MVP proof |
| --- | --- |
| Core logic | JVM tests cover ASR manifests, cloud/local catalog separation, Gemini setup/PCM/transcript protocol, Bergamot verification, transcript pairing, and translation ordering. |
| State transitions | JVM tests cover failure/stop/restart, partial replacement, stale-English rejection, final pairing, append, and clear transitions. |
| Happy-path E2E | MockWebServer test covers Gemini WebSocket setup, API-key query wiring, audio send, interim callback, final callback, and explicit finish. Real Google + microphone remains **UNVERIFIED** until an Android device uses a valid user key. |
| Persistence/process death | Local model markers are automated. Gemini key encryption/persistence uses real Android Keystore + SharedPreferences and remains **UNVERIFIED** until device/instrumentation execution. |
| Failure/recovery | Missing/stale/invalid model installs are covered. Gemini setup/transport failure is structurally terminal; real auth/quota/network recovery remains **UNVERIFIED** against Google. |
| Cross-feature | Selected ASR + shared translator readiness gate Start; all three ASR paths feed one translation/session owner. |
| Concurrency/duplicates | Shared model-download mutex, service first-wins, bounded audio/final translation queues, conflated partial translation, and one Gemini socket per session are structurally enforced/tested where platform-free. |
| UI behavior | Compile/lint cover picker/key wiring; real secure-key entry, TalkBack, IME, and large-font behavior remain **UNVERIFIED** until instrumentation/device checks. |
| Lifecycle/reboot | **UNVERIFIED** until service/microphone/native ASR/Bergamot/Gemini lifecycles are exercised on Android hardware/emulator. |
| Regression | Primeline and Nemotron retain their existing recognizer/model paths; Gemini adds a third substitution branch without changing local model assets. |
