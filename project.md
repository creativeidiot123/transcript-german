# Project contract

## Product

**German Live Captions** is an Android MVP that turns nearby spoken German into captions using
a user-selected on-device ASR backend. The existing German-specific Primeline Parakeet backend
remains available unchanged, and Nemotron 3.5 adds true streaming captions. Recognition is fully
on-device after the selected model is downloaded.

### Required journeys

1. **Choose backend:** while idle, the user can select Primeline or Nemotron 3.5. Primeline is the
   default after process start. The picker is locked while a model download or caption session is
   active.
2. **Install selected model:** when the selected model is absent, the user can download its pinned
   ONNX bundle. Progress is visible. A failed, partial, or integrity-invalid download is never
   reported ready. Each backend has an independent install directory and marker.
3. **Start captions:** from the visible Activity, the user taps Start. Microphone permission is
   requested only at this point. If granted, the selected backend is passed explicitly to the
   microphone foreground service and remains fixed for that session.
4. **Caption speech:** 16 kHz mono audio is kept in memory. Primeline is segmented by Silero VAD
   and emits finalized utterances. Nemotron keeps a persistent online transducer stream, forces the
   German language prompt, exposes partial text while speech is in progress, and finalizes text at
   streaming endpoints.
5. **Stop captions:** app and notification Stop actions are idempotent. Accepted audio is drained
   for a user stop, the selected recognizer is flushed, native ASR resources are released, and the
   foreground service ends.
6. **Clear transcript:** finalized caption history can be removed without affecting installed
   models.

### Observable behavior

- Selected model missing: Start is disabled and Download is available for that backend.
- Downloading: the current file number and per-file progress, when known, are visible.
- Download failure/integrity failure: no Ready state; Retry is available.
- Backend changes are blocked while downloading or listening.
- Microphone denial: no service starts; an explanatory message is shown.
- Listening: a persistent foreground-service notification is posted.
- Primeline speech: UI distinguishes listening, speech detected, and transcribing; finalized text
  appears after a VAD endpoint.
- Nemotron speech: the current partial phrase updates in place while speech continues, then moves
  into finalized transcript history at a streaming endpoint.
- Decoder overload: the session stops visibly rather than silently dropping audio.
- Process death: active capture ends and transcript history/backend selection are lost by design;
  installed model files remain.

### Canonical facts and owners

- Installed model truth per backend: ModelInstallVerifier over the backend's app-private model
  files plus revision install marker. ModelRepository owns installation mutation.
- Selected backend for the screen: CaptionViewModel, process-local; defaults to Primeline after
  process death.
- Backend for an active session: immutable CaptionService start input. The service does not read
  a mutable picker value after start.
- Current session/transcript/partial text truth: CaptionSessionStore, process-local and
  intentionally not durable.
- Microphone/ASR lifecycle: CaptionService.
- Runtime microphone permission launcher: MainActivity.
- UI projection: CaptionViewModel.

### Concurrency and retry

- Model download is single-flight under one repository mutex. A second backend download cannot
  overlap the first.
- Backend selection is accepted only while the session is idle and no model download is active.
- Caption Start is first-wins while a session job is active.
- Stop is idempotent.
- Audio uses a bounded queue of 64 100-ms chunks. Queue saturation is a terminal failure because
  dropping chunks would make captions deceptively incomplete.
- Downloads are retried only by explicit user action. Successfully verified assets may be reused;
  partial files are deleted before retry.

### Data lifecycle and privacy

- Microphone audio: memory only, never persisted, uploaded, logged, or included in notifications.
- Transcript and Nemotron partial hypotheses: process memory only, finalized history max 200 lines,
  never uploaded or logged.
- ONNX/VAD model files: persisted in app-private filesDir/models until app data is removed.
- Selected backend: process memory only; not persisted.
- Accounts/authentication/analytics: none.
- App backup: disabled so downloaded model data and transcript state are not backed up.

### Permissions and platform behavior

- RECORD_AUDIO is requested at point of use.
- Foreground service permissions and microphone service type are declared.
- The microphone foreground service is started only from a visible Activity user action.
- Target/compile SDK: 35. Minimum SDK: 29. Shipping ABI: arm64-v8a.
- Notification runtime permission is not requested in this MVP. Android still requires and receives
  the foreground-service notification; OS foreground-service surfaces remain authoritative when
  ordinary app notifications are disabled.

### Performance/storage assumptions

- Primeline download is approximately 671 MB.
- Nemotron 3.5 560-ms INT8 download is approximately 682 MB.
- CPU inference uses four ASR threads. Primeline additionally uses one Silero VAD thread.
- Primeline captions are VAD-segmented and utterance-based.
- Nemotron captions use a persistent streaming transducer stream with a 560-ms model chunk.
- No claim is made yet about comparative real-time factor, battery consumption, thermals, or
  German WER on arbitrary Android devices.

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
- Model/runtime downloads use HTTPS and pinned revisions/versions. Every downloaded asset has an
  exact expected byte length; all ONNX graph assets are additionally SHA-256 verified before
  commit.

### Non-goals for this MVP

No cloud transcription, audio recording, transcript persistence/export, translation, speaker
diarization, floating overlay, accessibility service, account system, background auto-start,
automatic backend benchmarking, or Qualcomm-QNN backend.

### Forbidden outcomes

- Starting microphone capture without runtime microphone permission.
- Treating a partial/corrupt model as installed.
- Switching the recognizer implementation inside an active session.
- Showing Nemotron partial hypotheses as separate finalized transcript lines.
- Dropping queued audio silently.
- Logging or uploading audio/transcript content.
- Leaking AudioRecord or native recognizer/VAD/stream resources after stop/failure.
- Auto-restarting microphone capture after process death or reboot.

## Verification matrix

| Category | MVP proof |
| --- | --- |
| Core logic | JVM tests for both model manifests/install verification and bounded/partial transcript behavior. |
| State transitions | JVM tests for failure, stop, restart, partial replacement/finalization, append, and clear transitions. |
| Happy-path E2E | **UNVERIFIED** until an arm64 Android device runs microphone + both native ASR backends. |
| Persistence/process death | Model marker validation is automated; backend selection intentionally resets to Primeline; Android process-kill behavior remains **UNVERIFIED** on-device. |
| Failure/recovery | Missing/stale/size-invalid installs are automated; network/native/device failures require device/integration follow-up. |
| Cross-feature | Backend picker/model install/session start are the shared collision points; the active session receives one explicit backend value. |
| Concurrency/duplicates | Repository mutex and service first-wins are structurally enforced; device overlap stress is **UNVERIFIED**. |
| UI behavior | Compile/lint coverage in CI; picker/partial-caption semantics remain **UNVERIFIED** until instrumentation is added. |
| Lifecycle/reboot | **UNVERIFIED** until service/microphone/native recognizer lifecycle is exercised on Android hardware/emulator. |
| Regression | Primeline retains its existing recognizer implementation and model bundle; unit/build checks protect shared state/model changes. |
