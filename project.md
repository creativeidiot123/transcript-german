# Project contract

## Product

**German + English Live Captions** is an Android MVP that turns nearby spoken German into paired
German and English captions. The user selects Primeline Parakeet or Nemotron 3.5 for on-device
German ASR. Both recognition backends feed one shared on-device Bergamot de-en-base INT8 translation
stage. After the required models are downloaded, microphone audio, recognized German, and English
translation remain on-device.

### Required journeys

1. **Choose backend:** while idle, the user can select Primeline or Nemotron 3.5. Primeline is the
   default after process start. The picker is locked while any model download or caption session is
   active.
2. **Install models:** the selected ASR bundle and the shared Bergamot de-en-base INT8 bundle each
   have explicit install state and download controls. A failed, partial, or integrity-invalid
   download is never reported ready.
3. **Start captions:** Start is available only when both the selected ASR model and Bergamot model
   are ready. Microphone permission is requested only at this point. If granted, the selected ASR
   backend is passed explicitly to the foreground service and remains fixed for that session.
4. **Caption + translate speech:** 16 kHz mono audio is kept in memory. Primeline emits finalized
   German VAD utterances and each final is translated immediately. Nemotron keeps a persistent
   German streaming transducer, exposes replaceable German partials, and the latest partial is
   translated without building a stale translation backlog. Final German lines are translated in
   order and paired with their English result.
5. **Stop captions:** app and notification Stop actions are idempotent. Accepted audio drains on a
   user stop, the recognizer flushes, queued finalized translations drain, native ASR/translation
   resources are released, and the foreground service ends.
6. **Clear transcript:** finalized bilingual caption history can be removed without affecting
   installed models.

### Observable behavior

- Selected ASR model missing: Start is disabled and Download is available for that backend.
- Bergamot model missing: Start is disabled and its separate Download action is available.
- Downloading: progress is visible when the server exposes total bytes.
- Download/integrity failure: no Ready state; Retry is available.
- Backend changes are blocked while any model is downloading or a caption session is active.
- Microphone denial: no service starts; an explanatory message is shown.
- Listening: a persistent foreground-service notification is posted.
- Primeline speech: finalized German appears after a VAD endpoint; the paired English translation
  follows from the same finalized German source.
- Nemotron speech: current German partial text updates in place while speech continues. English
  partial text follows the newest German hypothesis only; stale translations are never attached to
  a newer German partial.
- Finalized transcript lines retain both German source text and the English translation belonging
  to that line.
- ASR queue overload or translation failure stops visibly rather than silently dropping speech or
  degrading to German-only output.
- Process death: active capture ends and in-memory captions/backend selection are lost by design;
  downloaded models remain.

### Canonical facts and owners

- Installed ASR truth: ModelInstallVerifier over app-private backend files plus revision marker.
  ModelRepository owns ASR installation mutation.
- Installed translation truth: BergamotModelInstallVerifier over the app-private extracted bundle
  plus the pinned archive-SHA marker. BergamotModelRepository owns translation installation.
- Model download serialization: one application-owned mutex shared by both repositories.
- Selected ASR backend: CaptionViewModel, process-local; defaults to Primeline after process death.
- Active-session backend: immutable CaptionService start input.
- German/English transcript and current partial truth: CaptionSessionStore, process-local. German is
  the source caption; English is an associated translation and may temporarily be pending.
- Translation scheduling/native model lifecycle: CaptionTranslationPipeline + BergamotTranslator,
  owned by the active CaptionService session.
- Microphone/ASR lifecycle: CaptionService.
- Runtime microphone permission launcher: MainActivity.
- UI projection: CaptionViewModel.

### Concurrency and retry

- All model downloads are serialized by one application-owned mutex.
- Backend selection is accepted only while the session is idle and no model download is active.
- Caption Start is first-wins while a session job is active.
- Stop is idempotent.
- Audio uses a bounded queue of 64 100-ms chunks. Saturation is terminal because dropping chunks
  would make captions deceptively incomplete.
- Translation uses one serialized worker because the Bergamot model is not thread-safe. Final
  captions use a bounded ordered queue. Partial translation is conflated to the newest German
  hypothesis, so a slow translator cannot accumulate obsolete live partials.
- A translated partial is committed only if its German source still equals the current partial.
- On explicit user stop, final translations already accepted by the translation worker drain before
  the session is marked stopped. Failure cancellation does not commit stale results afterward.
- Downloads are retried only by explicit user action.

### Data lifecycle and privacy

- Microphone audio: memory only, never persisted, uploaded, logged, or included in notifications.
- German and English captions/partials: process memory only, finalized history max 200 paired lines,
  never uploaded or logged.
- ASR, VAD, and Bergamot model files: app-private filesDir/models until app data is removed.
- Selected backend: process memory only; not persisted.
- Accounts/authentication/analytics: none.
- App backup: disabled.

### Permissions and platform behavior

- RECORD_AUDIO is requested at point of use.
- Foreground service permissions and microphone service type are declared.
- The microphone foreground service is started only from a visible Activity user action.
- Target/compile SDK: 35. Minimum SDK: 29. Shipping ABI: arm64-v8a.
- Notification runtime permission is not requested in this MVP. Android still receives the
  foreground-service notification/system surface required by the OS.

### Performance/storage assumptions

- Primeline download is approximately 671 MB.
- Nemotron 3.5 560-ms INT8 download is approximately 682 MB.
- Bergamot de-en-base is a separately downloaded quantized student-model archive.
- CPU inference uses four ASR threads. Primeline additionally uses one Silero VAD thread.
- Bergamot translation is serialized through one model worker.
- Primeline captions are VAD-segmented and utterance-based, so English updates after each final
  utterance rather than word-by-word.
- Nemotron captions use a persistent 560-ms streaming transducer; English live partial latency also
  includes Bergamot translation time.
- No claim is made yet about real-time factor, battery, thermals, memory pressure, or translation/
  recognition quality on arbitrary Android devices.

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
- Bergamot catalog model: de-en-base version 2, API 1, CC-BY-SA-4.0.
- Bergamot archive SHA-256:
  caa7c0ce3c8eaf05d333dc9458683f4b0375e5eeb604f6fb2c8585f7b70d398b.
- Bergamot Android JNI wrapper: translate-kit 0.1.0, built from pinned upstream commit
  2dcdcb1559ed405d65ae1ff1e786d3a5ebb933c4 and consumed as a checksum-pinned AAR.
- Apache Commons Compress is used only for safe extraction of the pinned tar.gz bundle.
- Model/runtime downloads use HTTPS and pinned revisions, checksums, or exact asset metadata before
  commit.
- Release signing is opt-in through the manual `Signed AAB` GitHub Actions workflow. The job is
  bound to a protected `release` environment, restricted to `main`; signing material is supplied
  only through environment-scoped Actions secrets and a runner-temporary keystore. Normal PR/main
  verification stays unsigned.

### Non-goals for this MVP

No cloud transcription/translation, audio recording, transcript persistence/export, speaker
diarization, floating overlay, accessibility service, account system, background auto-start,
automatic backend benchmarking, or Qualcomm-QNN backend.

### Forbidden outcomes

- Starting microphone capture without runtime microphone permission.
- Treating a partial/corrupt ASR or Bergamot model as installed.
- Switching recognizer implementation inside an active session.
- Attaching an English partial translated from an older German hypothesis to newer German text.
- Reordering finalized English translations relative to their German source lines.
- Silently dropping queued audio/finalized translation work.
- Silently degrading a requested bilingual session to German-only after translation failure.
- Logging or uploading audio/transcript/translation content.
- Leaking AudioRecord or native ASR/Bergamot resources after stop/failure.
- Auto-restarting microphone capture after process death or reboot.

## Verification matrix

| Category | MVP proof |
| --- | --- |
| Core logic | JVM tests for ASR manifests, Bergamot install verification, transcript pairing, and translation partial conflation/final ordering. |
| State transitions | JVM tests cover failure/stop/restart, German partial replacement, stale-English rejection, final pairing, append, and clear transitions. |
| Happy-path E2E | **UNVERIFIED** until an arm64 Android device runs microphone + both ASR choices + real Bergamot JNI/model translation. |
| Persistence/process death | ASR and Bergamot marker validation are automated; process-owned captions/selection intentionally reset; Android process-kill behavior remains **UNVERIFIED** on-device. |
| Failure/recovery | Missing/stale/invalid installs are covered at verifier boundaries; network/native/device failures require device/integration follow-up. |
| Cross-feature | Selected ASR + shared translator readiness gate Start; both ASR callbacks feed the same translation/session owner. |
| Concurrency/duplicates | Shared model-download mutex, service first-wins, bounded audio/final translation queues, and conflated live partial translation are structurally enforced/tested where platform-free. |
| UI behavior | Compile/lint cover wiring; real TalkBack/large-font bilingual rendering remains **UNVERIFIED** until instrumentation/device checks. |
| Lifecycle/reboot | **UNVERIFIED** until service/microphone/native ASR + Bergamot lifecycles are exercised on Android hardware/emulator. |
| Regression | Primeline keeps its existing ASR implementation; both Primeline and Nemotron now share only the post-ASR translation stage. |
