# Architecture

## Owner map

    Model install authority/truth   ModelInstallVerifier + per-backend app-private files
    Model install mutation          ModelRepository
    Backend picker state            CaptionViewModel
    Active-session backend          CaptionService start input
    Screen projection               CaptionViewModel
    Session/transcript truth        CaptionSessionStore (process-local)
    Active-backend UI projection    CaptionSessionStore from CaptionService input
    Microphone/ASR lifecycle        CaptionService
    AudioRecord resource            AudioCapture
    Primeline native resources      ParakeetRecognizer
    Nemotron native resources       NemotronRecognizer
    Permission launcher             MainActivity
    Dependency construction         TranscriptApplication -> AppContainer
    Navigation                      Single Activity; no navigation graph

There is intentionally no Room, DataStore, backend, use-case layer, DI framework, or durable
transcript/backend-selection store in this MVP.

## Dependency flow

    Compose UI
       | actions/state
       v
    CaptionViewModel ---- selected backend ----> MainActivity
       |                                      mic permission |
       | model state/install                               |
       v                                                   v
    ModelRepository                              CaptionService start Intent
                                                        |
                                                        | fixed backend for session
                                                        v
                                                   AudioCapture
                                                        |
                                               bounded FloatArray queue
                                                        |
                                  +---------------------+---------------------+
                                  |                                           |
                           ParakeetRecognizer                         NemotronRecognizer
                           Silero VAD + offline                       persistent online
                           NeMo transducer                            NeMo transducer
                                  |                                           |
                                  +---------------------+---------------------+
                                                        |
                                                CaptionSessionStore
                                                        |
                                                        v
                                                CaptionViewModel/UI

The UI never opens the microphone, downloads model files directly, constructs native recognizers,
or chooses a backend after a session has started.

## Backend selection

CaptionViewModel is the only mutable owner of the picker selection. Primeline is the initial value.
The selection survives configuration recreation with the ViewModel but intentionally resets after
process death. The picker is disabled, and the ViewModel rejects changes, while a download or
caption session is active.

MainActivity captures the selected backend when Start is tapped and includes that stable value as
an explicit Intent extra after microphone permission succeeds. CaptionService resolves that input
once, records it in CaptionSessionStore for screen projection, and passes it through the session
job. A recreated Activity/ViewModel therefore renders the backend actually in use, and a later UI
selection cannot mutate an already-running recognizer.

## Model installation

ModelRepository owns one state entry per AsrBackend and serializes all model download attempts with
one mutex. Each backend maps to one immutable ModelBundleSpec and one separate app-private
directory. The repository never treats one backend's files as satisfying another backend.

Each remote file is written to <name>.part, verified, and renamed inside the same app-private
directory. The final .installed-revision marker is written only after all assets finish. Startup
readiness uses the marker, pinned revision, presence, and expected sizes.

Primeline retains its existing bundle, including Silero VAD. Nemotron uses the sherpa-onnx
Nemotron 3.5 multilingual streaming 560-ms INT8 export. Download URLs are revision-pinned; all
assets have exact byte lengths and the ONNX graphs also have pinned SHA-256 digests.

## Caption session lifecycle

CaptionService is a non-exported foreground service with the microphone service type. It owns a
structured coroutine scope for the service lifetime and a single session Job. Repeated Start while
that Job is active is ignored.

AudioCapture owns exactly one AudioRecord and capture thread. Stop is idempotent and attempts to
unblock a pending read before joining the thread. All recorder paths release the native recorder in
finally.

Audio chunks are 100 ms of 16 kHz mono PCM converted to FloatArray. A bounded channel decouples
capture from ASR decode. The channel is deliberately finite. If decoding falls behind by more than
the configured queue capacity, the session fails and stops; it never silently discards
business-critical audio.

CaptionRecognizer is the narrow runtime substitution seam shared by the two real recognizer
implementations. It owns only accept, finish, and close lifecycle behavior; backend-specific
decoding stays inside each implementation.

### Primeline

ParakeetRecognizer is unchanged in behavior. It owns one sherpa-onnx Silero VAD and one offline
NeMo transducer recognizer. VAD emits completed speech segments; each segment is decoded
synchronously on the service background dispatcher. Text is emitted only as finalized utterances.
Close releases both native objects exactly once.

### Nemotron 3.5

NemotronRecognizer owns one sherpa-onnx OnlineRecognizer and one persistent OnlineStream for the
session. It configures the 560-ms INT8 transducer package and pins the stream language option to
German (de).

Each 100-ms audio chunk is accepted into the persistent stream. The recognizer decodes while the
stream reports readiness, publishes the current hypothesis into CaptionSessionStore as replaceable
partial text, and finalizes that text when sherpa-onnx reports an endpoint. The stream is then reset
for the next utterance without reconstructing the recognizer.

On an explicit user stop, accepted queued audio drains first. Nemotron then marks input finished,
decodes remaining ready frames, and commits the trailing result once. Primeline keeps its existing
VAD flush behavior. Failure stops cancel rather than spending more time decoding stale queued
audio.

## State and durability

CaptionSessionStore is an application-scoped in-memory state holder. It has one mutable StateFlow;
external consumers receive read-only StateFlow. It retains at most 200 finalized lines and at most
one current partial hypothesis.

A partial hypothesis is a projection of the current Nemotron stream, never a finalized line.
Updating it replaces the prior partial. appendFinal clears the partial before adding the finalized
line, preventing duplicate transcript history.

The transcript, partial hypothesis, and picker selection are intentionally not process-durable.
Configuration changes reconnect to application/ViewModel state. Process death clears transcript and
selection and stops microphone work because the service uses START_NOT_STICKY. Downloaded model
files are the only durable data.

## Error and logging boundary

User state exposes stable failure categories: model unavailable, audio unavailable, ASR
initialization, queue overload, or unexpected local failure. Raw paths, URLs, audio, transcripts,
partial hypotheses, and recognized text are never logged. Diagnostics log only lifecycle/failure
categories, backend names, and model asset file names.

## Persistence impact

There is no database schema, DataStore, serialized user data, or migration contract. This change
adds a second app-private model directory and revision marker. Existing Primeline files and marker
remain valid and untouched. App backup remains disabled. Removing app data removes both models;
reinstalling therefore requires new downloads.
