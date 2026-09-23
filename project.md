# Project contract

## Product

**German Live Captions** is an Android MVP that turns nearby spoken German into captions using
the German-specific Primeline Parakeet model. Recognition is fully on-device after the first
model download.

### Required journeys

1. **Install model:** when the model is absent, the user can download the pinned ONNX bundle.
   Progress is visible. A failed, partial, or integrity-invalid download is never reported ready.
2. **Start captions:** from the visible Activity, the user taps Start. Microphone permission is
   requested only at this point. If granted, a microphone foreground service starts and owns the
   live session.
3. **Caption speech:** 16 kHz mono audio is kept in memory, segmented by Silero VAD, then decoded
   by the Primeline Parakeet offline transducer. Finalized utterances appear in order.
4. **Stop captions:** app and notification Stop actions are idempotent. Audio capture and native
   ASR resources are released and the foreground service ends.
5. **Clear transcript:** caption history can be removed without affecting the installed model.

### Observable behavior

- Missing model: Start is disabled and Download is available.
- Downloading: the current file number and per-file progress, when known, are visible.
- Download failure/integrity failure: no Ready state; Retry is available.
- Microphone denial: no service starts; an explanatory message is shown.
- Listening: a persistent foreground-service notification is posted.
- Speech: the UI distinguishes listening, speech detected, and transcribing.
- Decoder overload: the session stops visibly rather than silently dropping audio.
- Process death: active capture ends and transcript history is lost by design; model files remain.

### Canonical facts and owners

- Model installation truth: `ModelInstallVerifier` over app-private model files plus the revision
  install marker. `ModelRepository` owns installation mutation.
- Current session/transcript truth: `CaptionSessionStore`, process-local and intentionally not
  durable.
- Microphone/ASR lifecycle: `CaptionService`.
- Runtime microphone permission launcher: `MainActivity`.
- UI projection: `CaptionViewModel`.

### Concurrency and retry

- Model download is single-flight under a repository mutex.
- Caption Start is first-wins while a session job is active.
- Stop is idempotent.
- Audio uses a bounded queue of 64 100-ms chunks. Queue saturation is a terminal failure because
  dropping chunks would make captions deceptively incomplete.
- Downloads are retried only by explicit user action. Successfully verified assets may be reused;
  partial files are deleted before retry.

### Data lifecycle and privacy

- Microphone audio: memory only, never persisted, uploaded, logged, or included in notifications.
- Transcript: process memory only, max 200 finalized lines, never uploaded or logged.
- ONNX/VAD model files: persisted in app-private `filesDir/models` until app data is removed.
- Accounts/authentication/analytics: none.
- App backup: disabled so downloaded model data and transcript state are not backed up.

### Permissions and platform behavior

- `RECORD_AUDIO` is requested at point of use.
- Foreground service permissions and `microphone` service type are declared.
- The microphone foreground service is started only from a visible Activity user action.
- Target/compile SDK: 35. Minimum SDK: 29. Shipping ABI: arm64-v8a.
- Notification runtime permission is not requested in this MVP. Android still requires and receives
  the foreground-service notification; OS foreground-service surfaces remain authoritative when
  ordinary app notifications are disabled.

### Performance/storage assumptions

- Model download is approximately 671 MB.
- CPU inference uses four sherpa-onnx ASR threads plus one VAD thread.
- Captions are VAD-segmented and therefore utterance-based, not token-streaming.
- No claim is made yet about exact real-time factor or battery consumption on arbitrary devices.

### External contracts

- sherpa-onnx runtime: 1.13.8.
- Primeline-compatible INT8 export revision:
  `d548e25b9bfe559aa274f361892dc4ed5d64743a`.
- Recognizer model type: `nemo_transducer`, decoding: `greedy_search`.
- Silero VAD asset: sherpa-onnx `asr-models/silero_vad.onnx`.
- Model/runtime downloads use HTTPS and pinned revisions/versions.

### Non-goals for this MVP

No cloud transcription, audio recording, transcript persistence/export, translation, speaker
diarization, multi-engine picker, floating overlay, accessibility service, account system, or
background auto-start.

### Forbidden outcomes

- Starting microphone capture without runtime microphone permission.
- Treating a partial/corrupt model as installed.
- Dropping queued audio silently.
- Logging or uploading audio/transcript content.
- Leaking AudioRecord or native recognizer/VAD resources after stop/failure.
- Auto-restarting microphone capture after process death or reboot.

## Verification matrix

| Category | MVP proof |
| --- | --- |
| Core logic | JVM tests for model install verification and bounded transcript behavior. |
| State transitions | JVM tests for failure, stop, restart, append, and clear transitions. |
| Happy-path E2E | **UNVERIFIED** until an arm64 Android device runs microphone + native ASR. |
| Persistence/process death | Model marker validation is automated; Android process-kill behavior remains **UNVERIFIED** on-device. |
| Failure/recovery | Missing/stale/size-invalid installs are automated; network/native/device failures require device/integration follow-up. |
| Cross-feature | N/A: this initial repository has one feature and no shared product truth. |
| Concurrency/duplicates | Repository mutex and service first-wins are structurally enforced; device overlap stress is **UNVERIFIED**. |
| UI behavior | Compile/lint coverage in CI; semantics/device interaction remains **UNVERIFIED** until instrumentation is added. |
| Lifecycle/reboot | **UNVERIFIED** until service/microphone lifecycle is exercised on Android hardware/emulator. |
| Regression | N/A: this is a new feature, not a fix for an existing defect. |
