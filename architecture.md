# Architecture

## Owner map

```text
Model install authority/truth   ModelInstallVerifier + app-private files
Model install mutation          ModelRepository
Screen projection               CaptionViewModel
Session/transcript truth        CaptionSessionStore (process-local)
Microphone/ASR lifecycle        CaptionService
AudioRecord resource            AudioCapture
Native VAD/ASR resources        ParakeetRecognizer
Permission launcher             MainActivity
Dependency construction         TranscriptApplication -> AppContainer
Navigation                      Single Activity; no navigation graph
```

There is intentionally no Room, DataStore, backend, use-case layer, DI framework, or durable
transcript store in this MVP.

## Dependency flow

```text
Compose UI
   ↓ actions/state
CaptionViewModel
   ↓                       MainActivity ── runtime mic permission
ModelRepository                 │
CaptionSessionStore             └── starts/stops CaptionService
                                     ↓
                                AudioCapture
                                     ↓ bounded FloatArray queue
                                ParakeetRecognizer
                                     ↓
                                CaptionSessionStore
                                     ↓
                                CaptionViewModel/UI
```

The UI never opens the microphone, downloads model files directly, or constructs native
recognizers.

## Model installation

`ModelRepository` serializes download attempts with a mutex. Each remote file is written to
`<name>.part`, verified, and renamed inside the same app-private directory. The final
`.installed-revision` marker is written only after all assets finish. Startup readiness uses the
marker, pinned revision, presence, and expected sizes.

Every downloaded model asset, including the token vocabulary and VAD, is pinned to the immutable
upstream revision/version and verified against an exact byte length and SHA-256 before it can be
committed to the install.

## Caption session lifecycle

`CaptionService` is a non-exported foreground service with the microphone service type. It owns a
structured coroutine scope for the service lifetime and a single session Job. Repeated Start while
that Job is active is ignored.

`AudioCapture` owns exactly one `AudioRecord` and capture thread. Stop is idempotent and attempts
to unblock a pending read before joining the thread. All recorder paths release the native recorder
in `finally`.

Audio chunks are 100 ms of 16 kHz mono PCM converted to `FloatArray`. A bounded channel decouples
capture from VAD/ASR decode. The channel is deliberately finite. If decoding falls behind by more
than the configured queue capacity, the session fails and stops; it never silently discards
business-critical audio.

`ParakeetRecognizer` owns one sherpa-onnx Silero VAD and one offline NeMo transducer recognizer.
VAD emits completed speech segments; each segment is decoded synchronously on the service's
background dispatcher. Text is emitted only as finalized utterances. Close releases both native
objects exactly once.

A user Stop first stops audio capture and closes the queue, allowing already accepted chunks to
drain. The recognizer then flushes VAD once so the trailing partial utterance can be finalized.
During that drain/flush, the session state remains `STOPPING` while finalized trailing text may still
append. Failure stops instead cancel the session rather than spending more time decoding stale
queued audio.

## State and durability

`CaptionSessionStore` is an application-scoped in-memory state holder. It has one mutable
`StateFlow`; external consumers receive read-only `StateFlow`. It retains at most 200 finalized
lines to keep memory bounded.

The transcript is intentionally not process-durable. Configuration changes reconnect to the same
application state. Process death clears transcript/session state and stops microphone work because
the service uses `START_NOT_STICKY`. Downloaded models are the only durable data.

## Error and logging boundary

User state exposes stable failure categories: model unavailable, audio unavailable, ASR
initialization, queue overload, or unexpected local failure. Raw paths, URLs, audio, transcripts,
and recognized text are never logged. Diagnostics log only lifecycle/failure categories and model
asset file names.

## Persistence impact

This initial app introduces no database schema, DataStore, serialized user data, or migration
contract. It does introduce app-private model files and a revision marker. App backup is disabled.
Removing app data removes the model; reinstalling therefore requires a new download.
