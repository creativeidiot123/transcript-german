# Architecture

## Owner map

~~~text
Model install authority/truth   ModelInstallVerifier + per-backend app-private files
Model install mutation          ModelRepository
Idle backend selection          CaptionViewModel (process-local)
Active backend/session truth    CaptionSessionStore
Screen projection               CaptionViewModel
Microphone/ASR lifecycle        CaptionService
AudioRecord resource            AudioCapture
Primeline native resources      ParakeetRecognizer
Nemotron native resources       NemotronRecognizer
Permission launcher             MainActivity
Dependency construction         TranscriptApplication -> AppContainer
Navigation                      Single Activity; no navigation graph
~~~

There is intentionally no Room, DataStore, server backend, use-case layer, DI framework, or durable
transcript/selection store in this MVP.

## Dependency flow

~~~text
Compose UI -- select backend / actions
   |
   v
CaptionViewModel -- process-local idle selection
   |                       MainActivity -- runtime mic permission
   v                            |
ModelRepository                 +-- Start intent + explicit AsrBackend
CaptionSessionStore                              |
                                                 v
                                           CaptionService
                                                 |
                                                 v
                                            AudioCapture
                                                 |
                                      bounded FloatArray queue
                                      /                    \
                                     v                      v
                         ParakeetRecognizer          NemotronRecognizer
                         VAD + offline ASR           persistent online ASR
                                      \                    /
                                       +---------+----------+
                                                 |
                                                 v
                                        CaptionSessionStore
                                                 |
                                                 v
                                        CaptionViewModel/UI
~~~

The UI never opens the microphone, downloads model files directly, or constructs native
recognizers. CaptionService is the only owner that chooses a recognizer implementation, using the
backend snapshotted into the Start intent.

## Backend selection

CaptionViewModel owns the user's idle selection. It starts at Primeline unless an already-running
process-local session identifies another active backend. Selection changes are rejected while the
session is non-idle or any model download is active.

Before requesting microphone permission, the ViewModel snapshots the selected backend. The
permission callback returns that snapshot to MainActivity, which places its stable wire value in
the service Start intent. CaptionService falls back to Primeline only for a missing/unknown extra,
which keeps old/internal start intents backward compatible. Once a session starts,
CaptionSessionStore.activeBackend is the observable active fact and duplicate Start requests are
ignored.

Selection is intentionally not persisted. Configuration recreation retains it through the
ViewModel/application process. Process death resets an idle selection to Primeline.

## Model installation

ModelRepository owns a read-only installation-state map keyed by AsrBackend and serializes all
download attempts with one mutex. Each backend has its own pinned ModelBundleSpec, directory, and
revision marker, so installing one model cannot make the other ready.

Each remote file is written to <name>.part, checked, and renamed inside the same app-private
directory. The final .installed-revision marker is written only after all assets finish. Startup
readiness uses the marker, pinned revision, presence, and expected file sizes.

Primeline retains its existing exact-size and SHA-256 checks for every downloaded asset. Nemotron's
three ONNX weight files are exact-size and SHA-256 checked; its pinned tokens.txt is exact-size
checked. Successfully verified existing files can be reused. A cancelled/failed partial file is
deleted and never committed as installed.

## Caption session lifecycle

CaptionService is a non-exported foreground service with the microphone service type. It owns a
structured coroutine scope for the service lifetime and a single session Job. Repeated Start while
that Job is active is ignored, so a second backend choice cannot replace an active recognizer.

AudioCapture owns exactly one AudioRecord and capture thread. Stop is idempotent and attempts to
unblock a pending read before joining the thread. All recorder paths release the native recorder
in finally.

Audio chunks are 100 ms of 16 kHz mono PCM converted to FloatArray. A bounded channel decouples
capture from ASR decode. The channel is deliberately finite. If decoding falls behind by more than
the configured queue capacity, the session fails and stops; it never silently discards
business-critical audio.

### Primeline

ParakeetRecognizer keeps the pre-picker behavior: one sherpa-onnx Silero VAD plus one offline
NeMo transducer recognizer. VAD emits completed speech segments and each segment is decoded
synchronously on the service background dispatcher. Only finalized utterances are emitted. Close
releases both native objects exactly once.

### Nemotron

NemotronRecognizer owns one sherpa-onnx OnlineRecognizer and one persistent OnlineStream for the
entire caption session. The stream is created with language=de-DE. Each 100-ms audio chunk is fed
into the stream, ready frames are decoded until isReady() is false, and changed hypotheses replace
CaptionSessionStore.partialText.

When sherpa-onnx reports an endpoint, the current non-blank hypothesis is appended as one finalized
caption line, partial text is cleared, and the same stream is reset for the next utterance. User
Stop calls inputFinished(), drains ready frames, commits the trailing hypothesis once, then closes
the stream and recognizer.

### Stop and failure

A user Stop first stops audio capture and closes the queue, allowing already accepted chunks to
drain. The selected recognizer then receives one finish() call. During drain/flush the state remains
STOPPING; finalized trailing text may still append. Failure stops cancel the session and clear any
partial hypothesis rather than spending more time decoding stale queued audio.

## State and durability

CaptionSessionStore is an application-scoped in-memory state holder. It has one mutable StateFlow;
external consumers receive read-only StateFlow. It retains at most 200 finalized lines plus at most
one current Nemotron partial hypothesis.

The transcript, partial hypothesis, and backend selection are intentionally not process-durable.
Configuration changes reconnect to application/ViewModel state. Process death clears
transcript/session state and stops microphone work because the service uses START_NOT_STICKY.
Downloaded model bundles are the only durable data.

## Error and logging boundary

User state exposes stable failure categories: model unavailable, audio unavailable, ASR
initialization, queue overload, or unexpected local failure. Raw paths, URLs, audio, transcripts,
partial hypotheses, and recognized text are never logged. Diagnostics log only lifecycle/failure
categories, backend wire names, and model asset file names.

## Persistence impact

This change adds no database schema, DataStore, serialized user setting, or migration contract. It
adds a second app-private model directory/revision marker. App backup remains disabled. Removing app
data removes both model bundles; reinstalling therefore requires new downloads.
