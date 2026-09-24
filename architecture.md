# Architecture

## Owner map

    Local ASR install truth          ModelInstallVerifier + per-backend app-private files
    Local ASR install mutation       ModelRepository
    Translation install truth       BergamotModelInstallVerifier + app-private extracted bundle
    Translation install mutation    BergamotModelRepository
    Model download serialization    AppContainer shared Mutex
    Gemini API key truth            GeminiApiKeyStore + encrypted app-private SharedPreferences
    Gemini encryption key           Android Keystore AES-GCM alias
    Backend picker state            CaptionViewModel
    Active-session backend          CaptionService start input
    Screen projection               CaptionViewModel
    Bilingual session truth         CaptionSessionStore (process-local)
    Active-backend UI projection    CaptionSessionStore from CaptionService input
    Microphone/ASR lifecycle        CaptionService
    Gemini socket lifecycle         GeminiLiveRecognizer owned by CaptionService session
    Translation work lifecycle      CaptionTranslationPipeline owned by CaptionService session
    Bergamot native model           BergamotTranslator
    AudioRecord resource            AudioCapture
    Primeline native resources      ParakeetRecognizer
    Nemotron native resources       NemotronRecognizer
    Permission launcher             MainActivity
    Dependency construction         TranscriptApplication -> AppContainer
    Navigation                      Single Activity; no navigation graph

There is intentionally no Room, DataStore, account backend, use-case layer, DI framework, durable
transcript store, or durable backend-selection store.

## Dependency flow

    Compose UI
       | actions/state
       v
    CaptionViewModel ---- selected backend ----> MainActivity
       |         |                            mic permission |
       |         +-- Bergamot model state/install          |
       |         +-- Gemini key configured/save/remove     |
       | local ASR model state/install                      |
       v                                                   v
 ModelRepository + BergamotModelRepository       CaptionService start Intent
        \              /                               |
         shared download mutex                           | fixed ASR backend
                                                        v
                                                   AudioCapture
                                                        |
                                               bounded FloatArray queue
                                                        |
                   +-------------------+----------------+-------------------+
                   |                   |                                    |
           ParakeetRecognizer   NemotronRecognizer                  GeminiLiveRecognizer
           VAD + offline ASR    streaming local ASR                 WSS to Gemini Live
                   |                   |                         de-DE interim/final German
                   +-------------------+----------------+-------------------+
                                                        |
                                             CaptionTranslationPipeline
                                             latest partial / ordered finals
                                                        |
                                                BergamotTranslator
                                                   German -> English
                                                        |
                                                CaptionSessionStore
                                           paired German + English state
                                                        |
                                                        v
                                                CaptionViewModel/UI

The UI never opens the microphone, downloads model files directly, constructs native
ASR/translation/network engines, or reads plaintext API keys. CaptionService never mutates saved
credentials.

## Backend selection

CaptionViewModel is the only mutable owner of picker selection. Primeline is the initial value.
Selection survives configuration recreation with the ViewModel but resets after process death.
The picker is disabled, and the ViewModel rejects changes, while a download or caption session is
active.

MainActivity captures the selected backend when Start is tapped and includes that stable value as
an explicit Intent extra after microphone permission succeeds. CaptionService resolves that input
once and records it in CaptionSessionStore. A recreated Activity therefore renders the backend
actually in use.

Primeline and Nemotron resolve through ModelRepository. Gemini bypasses local ASR model installation
and resolves its credential from GeminiApiKeyStore only when CaptionService starts a Gemini session.

## Gemini credential persistence

GeminiApiKeyStore is the sole credential persistence/decryption boundary. It stores only AES-GCM
ciphertext plus the random IV in a private SharedPreferences file. The AES key is generated by and
kept in Android Keystore. The plaintext API key exists only in the caller-provided save buffer,
during encryption/decryption, and in memory for creation of the active Gemini WebSocket request.

CaptionUiState exposes only configured/mutation-in-progress booleans and a safe storage-error flag.
It never contains the key or encrypted blob. The Compose field uses password visual transformation
and deliberately uses remember rather than rememberSaveable so an unsaved plaintext draft is not
written into saved instance state.

Save/replace/remove actions are accepted only while the caption session is idle and are single-flight.
CaptionViewModel flips the mutation-in-progress flag synchronously before launching encryption or
clear work. Gemini Start readiness is false while that flag is set, and prepareMicrophoneRequest
independently re-checks both the mutation flag and GeminiApiKeyStore configured truth before creating
a pending start. This prevents a rapid Clear/Replace -> Start from opening a session with the old or
soon-to-be-removed key. App backup is already disabled. If stored ciphertext cannot be decrypted,
the credential record is cleared and Gemini becomes unconfigured instead of returning corrupted
plaintext.

## Model installation

AppContainer owns one download Mutex shared by ModelRepository and BergamotModelRepository.
ModelRepository has immutable bundle specs/directories only for Primeline and Nemotron; Gemini has
no local ASR ModelBundleSpec and therefore cannot accidentally be reported installed by local files.

BergamotModelRepository downloads/verifies/extracts the de-en-base bundle independently because all
three recognition backends consume the same German-to-English translator.

## Caption session lifecycle

CaptionService is a non-exported foreground service with microphone service type. It owns a
structured service scope and one session Job. Repeated Start while that job is active is ignored.

A session resolves the selected recognition dependency and shared Bergamot files before capture.
For local backends this means a verified model directory. For Gemini this means a successfully
decrypted API key and a completed Gemini Live setup handshake. Audio capture starts only after the
selected recognizer and Bergamot translator initialize.

AudioCapture owns exactly one AudioRecord/capture thread. Audio chunks are 100 ms of 16 kHz mono
PCM and enter a bounded 64-element channel. Saturation is terminal; audio is never silently dropped.

CaptionRecognizer remains the narrow runtime ASR substitution seam. Its finish operation is suspend
so the cloud recognizer can perform bounded remote finalization without blocking the main thread;
local recognizers retain their previous synchronous behavior inside that suspend contract.

### Primeline

ParakeetRecognizer remains VAD + offline NeMo-transducer ASR. Each finalized German utterance is
committed to CaptionSessionStore and submitted to the shared translation pipeline.

### Nemotron 3.5

NemotronRecognizer retains one OnlineRecognizer/OnlineStream, forces language=de, and publishes
replaceable German partials plus endpoint finals.

### Gemini 3.5 Transcribe Live

GeminiLiveRecognizer owns one OkHttp WebSocket for one caption session. It connects to Google's
Gemini Live v1beta BidiGenerateContent endpoint with the user API key as the documented TLS
WebSocket query authentication parameter. No logging interceptor is installed and code never logs
the request URL, key, response body, audio, or transcript.

After WebSocket open, the recognizer sends setup for models/gemini-3.5-transcribe-live with TEXT
response modality and inputAudioTranscription.languageCodes=[de-DE]. CaptionService does not start
microphone capture until setupComplete arrives.

Each existing FloatArray audio chunk is converted to raw signed 16-bit little-endian PCM and sent as
audio/pcm;rate=16000. Before enqueueing another message, the recognizer checks OkHttp's WebSocket
queue and fails the session at 256 KiB rather than allowing a stalled network to accumulate a
minutes-old audio backlog. Repeated identical interim hypotheses are suppressed before they reach
translation. interimInputTranscription updates the replaceable German partial. inputTranscription
is authoritative final German text and uses the same final-caption path as the local recognizers.

On explicit Stop, CaptionService drains the app audio queue and Gemini sends audioStreamEnd. If a
live interim hypothesis exists, the recognizer waits up to five seconds for the matching final
transcript; a missing final is reported as Gemini connection failure rather than silently claiming
the visible pending speech was committed. If audio was sent but no interim arrived yet, the
recognizer gives the server up to two seconds to emit a final without turning silence/no-recognition
into a false failure. Then final translation work drains and the WebSocket is closed.

Async WebSocket/protocol failures disable further Gemini callbacks and signal one terminal session
failure. The app does not auto-reconnect or switch recognizers inside the active session. Google's
documented Live Transcribe session limit is up to 10 minutes; a server-closed session therefore
becomes a visible terminal failure and can be restarted manually.

### Shared translation stage

CaptionTranslationPipeline owns serialization of one non-thread-safe BergamotTranslator. The
pipeline has a bounded command channel for finalized work and at most one pending live partial.
Multiple partial submissions before translation catches up collapse to the newest source text.

After any partial translation returns, CaptionSessionStore accepts it only if that German source is
still the current partial. Final German lines receive stable line IDs and final translations update
only the matching line.

On explicit user Stop, accepted audio drains, ASR finalization completes, then translation drains
before the session becomes idle. On failure, the recognizer is closed first so asynchronous Gemini
callbacks cannot append new German after the bilingual translation pipeline is cancelled; native
and network resources are released in NonCancellable service teardown.

## State and durability

CaptionSessionStore is an application-scoped in-memory StateFlow. Each finalized CaptionLine owns
German source text plus nullable English text while translation is pending. State also contains at
most one current German partial and nullable English translation.

Transcript, partials, and backend selection are not process-durable. Downloaded local models and
the encrypted Gemini credential are durable app-private feature data. There is no database schema or
serialized transcript migration.

## Error and logging boundary

Stable user failures distinguish missing local ASR model, missing Bergamot model, missing Gemini
credential, Gemini connection/session failure, audio failure, local ASR initialization, translation
initialization/runtime failure, audio backpressure, and unexpected local failure.

Raw paths, URLs containing credentials, API keys, audio, German captions, English translations, and
partial text are never logged. Diagnostics log only safe lifecycle/failure categories, backend
names, and local model metadata.

## Persistence impact

There is no Room/DataStore schema migration. This change adds an app-private Gemini credential
SharedPreferences file containing only AES-GCM ciphertext/IV and an Android Keystore AES key alias.
Existing Primeline, Nemotron, and Bergamot installs remain valid and untouched. Removing app data
removes local models and the encrypted credential. App backup remains disabled.
