# Project contract

## Product

**German Live Captions** is an Android MVP that turns nearby spoken German into captions with a
user-selectable on-device ASR backend. Primeline Parakeet remains the default German-specialized,
utterance-based recognizer. Nemotron 3.5 ASR Streaming 0.6B is available as a separate true
streaming backend. Each backend runs fully on-device after its own model download.

### Required journeys

1. **Choose backend:** while no caption session or model download is active, the user can select
   Primeline or Nemotron. Selection is process-local; Primeline is the default after process death.
2. **Install selected model:** when the selected model is absent, the user can download its pinned
   ONNX bundle. Progress is visible. A failed or partial download is never reported ready.
3. **Start captions:** from the visible Activity, the user taps Start. Microphone permission is
   requested only at this point. The selected backend is snapshotted into the start request so an
   active session cannot silently switch recognizers.
4. **Caption speech:** 16 kHz mono audio is kept in memory. Primeline uses Silero VAD and the
   offline transducer, emitting finalized utterances. Nemotron uses one persistent online
   transducer stream pinned to German (de-DE), showing partial text while speech continues and
   finalizing text at streaming endpoints.
5. **Stop captions:** app and notification Stop actions are idempotent. Audio capture and native
   ASR resources are released and the foreground service ends.
6. **Clear transcript:** finalized and in-progress caption text can be removed without affecting
   installed models.

### Observable behavior

- The picker shows Primeline and Nemotron and is disabled while a caption session or model
  download is active.
- Missing selected model: Start is disabled and Download is available for that backend.
- Downloading: the current file number and per-file progress, when known, are visible.
- Download failure/integrity failure: no Ready state; Retry is available.
- Microphone denial: no service starts; an explanatory message is shown.
- Listening: a persistent foreground-service notification is posted.
- Primeline: finalized text appears after VAD completes a speech segment.
- Nemotron: the current partial hypothesis is visible while speech is being decoded; an endpoint
  commits it to the bounded transcript.
- Decoder overload: the session stops visibly rather than silently dropping audio.
- Process death: active capture ends, transcript and backend selection are lost by design, and
  downloaded model files remain.

### Canonical facts and owners

- Per-backend model installation truth: ModelInstallVerifier over app-private model files plus
  each bundle's revision install marker. ModelRepository owns installation mutation and exposes
  installation state keyed by AsrBackend.
- Idle backend selection: CaptionViewModel, process-local and mutable only while the session is
  idle and no download is running.
- Active session backend and transcript truth: CaptionSessionStore. CaptionService receives
  the backend explicitly in its Start intent and owns the microphone/ASR lifecycle for that
  session.
- Runtime microphone permission launcher: MainActivity.
- UI projection: CaptionViewModel.

### Concurrency and retry

- Model download is globally single-flight under a repository mutex. A second backend download is
  serialized rather than allowed to compete for storage/network bandwidth.
- Caption Start is first-wins while a session job is active. A duplicate Start cannot replace the
  active backend.
- Stop is idempotent.
- Audio uses a bounded queue of 64 100-ms chunks. Queue saturation is a terminal failure because
  dropping chunks would make captions deceptively incomplete.
- Downloads are retried only by explicit user action. Successfully verified assets may be reused;
  partial files are deleted before retry.
- User Stop drains already accepted audio and flushes the selected recognizer once. Failure stops
  cancel rather than decoding stale queued audio.

### Data lifecycle and privacy

- Microphone audio: memory only, never persisted, uploaded, logged, or included in notifications.
- Transcript and Nemotron partial hypothesis: process memory only, max 200 finalized lines, never
  uploaded or logged.
- Backend selection: process memory only; no DataStore or database is introduced.
- ONNX/VAD model files: separate pinned backend directories in app-private filesDir/models until
  app data is removed.
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

- Primeline download is approximately 671 MB and keeps the existing four sherpa-onnx ASR threads
  plus one VAD thread.
- Nemotron 560-ms INT8 download is approximately 682 MB (about 651 MiB) and uses four sherpa-onnx
  ASR threads. It does not use the separate Silero VAD.
- Primeline is VAD-segmented and utterance-based. Nemotron is cache-aware streaming and emits
  partial hypotheses from a persistent online stream.
- No claim is made yet about comparative German WER, exact real-time factor, memory peak, thermal
  behavior, or battery consumption on arbitrary Android devices.

### External contracts

- sherpa-onnx runtime: 1.13.8.
- Primeline-compatible INT8 export revision:
  d548e25b9bfe559aa274f361892dc4ed5d64743a.
- Primeline recognizer: offline nemo_transducer, greedy_search, Silero VAD.
- Nemotron export: sherpa-onnx 560-ms INT8 package
  sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11, pinned to
  ab43d895f5985b1bbab8b6eac8607fcdc05343f3.
- Nemotron recognizer: online transducer, greedy_search, stream option language=de-DE.
- Model/runtime downloads use HTTPS and pinned revisions/versions.
- Primeline model/export: CC BY 4.0. Nemotron model: NVIDIA Open Model Data Warehouse License
  Agreement v1.1 (OpenMDW-1.1); attribution and upstream license link are recorded in
  MODEL_NOTICES.md.

### Non-goals for this MVP

No cloud transcription, audio recording, transcript persistence/export, translation, speaker
diarization, automatic backend benchmarking/switching, backend-specific tuning UI, floating
overlay, accessibility service, account system, or background auto-start.

### Forbidden outcomes

- Starting microphone capture without runtime microphone permission.
- Starting with a backend whose model is not installed.
- Changing the active backend after a caption session has started.
- Treating a partial/incomplete model download as installed.
- Dropping queued audio silently.
- Logging or uploading audio/transcript content.
- Leaking AudioRecord or native recognizer/VAD/online-stream resources after stop/failure.
- Auto-restarting microphone capture after process death or reboot.

## Verification matrix

| Category | MVP proof |
| --- | --- |
| Core logic | JVM tests cover backend wire routing, bundle routing, model install verification, bounded transcript behavior, and streaming partial replacement/finalization. |
| State transitions | JVM tests cover backend activation, partial/final transitions, failure cleanup, stop, restart, append, and clear. |
| Happy-path E2E | **UNVERIFIED** until an arm64 Android device runs microphone + both native ASR backends with their real model files. |
| Persistence/process death | Model marker validation is automated; backend selection is intentionally non-durable. Android process-kill behavior remains **UNVERIFIED** on-device. |
| Failure/recovery | Missing/stale/size-invalid installs are automated; network/native/device failures require device/integration follow-up. |
| Cross-feature | The picker/session collision is structurally gated: picker mutation is rejected while a session or download is active. |
| Concurrency/duplicates | Repository mutex, ViewModel download first-wins, service Start first-wins, and explicit session backend snapshot are structurally enforced; device overlap stress is **UNVERIFIED**. |
| UI behavior | Compile/lint coverage verifies wiring; semantics, large-text rendering, and device interaction remain **UNVERIFIED** until instrumentation is added. |
| Lifecycle/reboot | **UNVERIFIED** until service/microphone/native-resource lifecycle is exercised on Android hardware/emulator for both backends. |
| Regression | Primeline remains on its existing VAD + offline recognizer path; JVM tests preserve existing session/model invariants. |
