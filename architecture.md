# Architecture

## Owner map

    ASR install authority/truth     ModelInstallVerifier + per-backend app-private files
    ASR install mutation            ModelRepository
    Translation install truth       BergamotModelInstallVerifier + app-private extracted bundle
    Translation install mutation    BergamotModelRepository
    Model download serialization    AppContainer shared Mutex
    Backend picker state            CaptionViewModel
    Active-session backend          CaptionService start input
    Screen projection               CaptionViewModel
    Bilingual session truth         CaptionSessionStore (process-local)
    Active-backend UI projection    CaptionSessionStore from CaptionService input
    Microphone/ASR lifecycle        CaptionService
    Translation work lifecycle      CaptionTranslationPipeline owned by CaptionService session
    Bergamot native model           BergamotTranslator
    AudioRecord resource            AudioCapture
    Primeline native resources      ParakeetRecognizer
    Nemotron native resources       NemotronRecognizer
    Permission launcher             MainActivity
    Dependency construction         TranscriptApplication -> AppContainer
    Navigation                      Single Activity; no navigation graph

There is intentionally no Room, DataStore, cloud backend, use-case layer, DI framework, or durable
transcript/backend-selection store.

## Dependency flow

    Compose UI
       | actions/state
       v
    CaptionViewModel ---- selected backend ----> MainActivity
       |         |                            mic permission |
       |         +-- Bergamot model state/install          |
       | ASR model state/install                           |
       v                                                   v
 ModelRepository + BergamotModelRepository       CaptionService start Intent
        \              /                               |
         shared download mutex                           | fixed ASR backend
                                                        v
                                                   AudioCapture
                                                        |
                                               bounded FloatArray queue
                                                        |
                                  +---------------------+---------------------+
                                  |                                           |
                           ParakeetRecognizer                         NemotronRecognizer
                           VAD + offline ASR                          streaming ASR
                                  | final German                 partial/final German
                                  +---------------------+---------------------+
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

The UI never opens the microphone, downloads model files directly, constructs native ASR/translation
engines, or chooses a backend after a session starts.

## Backend selection

CaptionViewModel is the only mutable owner of the picker selection. Primeline is the initial value.
The selection survives configuration recreation with the ViewModel but intentionally resets after
process death. The picker is disabled, and the ViewModel rejects changes, while a download or
caption session is active.

MainActivity captures the selected backend when Start is tapped and includes that stable value as
an explicit Intent extra after microphone permission succeeds. CaptionService resolves that input
once, records it in CaptionSessionStore for screen projection, and passes it through the session.
A recreated Activity therefore renders the backend actually in use.

## Model installation

AppContainer owns one download Mutex shared by ModelRepository and BergamotModelRepository. This
keeps the product's one-at-a-time model-download contract without making either repository own the
other.

ModelRepository retains independent immutable ASR bundle specs/directories for Primeline and
Nemotron. Each remote file is written to a partial file and verified before the revision marker is
committed.

BergamotModelRepository downloads the official de-en-base v2 tar.gz into app-private storage,
verifies the entire archive against the pinned SHA-256 from the Bergamot model registry, extracts
into a staging directory, rejects archive links/path traversal, verifies the required model,
SentencePiece vocabulary, lexical shortlist, and config files, writes an archive-SHA marker, then
renames the staging directory into its final install location. Interrupted archive/staging files are
not treated as installed.

The translation bundle is independent of ASR selection because both recognition backends consume
the same German-to-English translator.

## Caption session lifecycle

CaptionService is a non-exported foreground service with microphone service type. It owns a
structured service scope and one session Job. Repeated Start while that job is active is ignored.

A session first resolves the selected ASR files and shared Bergamot files. BergamotTranslator loads
one de->en native model for that session, then the selected recognizer is initialized. Audio capture
starts only after both native stages initialize successfully.

AudioCapture owns exactly one AudioRecord/capture thread. Audio chunks are 100 ms of 16 kHz mono
PCM and enter a bounded 64-element channel. Saturation is terminal; audio is never silently dropped.

CaptionRecognizer remains the narrow ASR substitution seam.

### Primeline

ParakeetRecognizer remains VAD + offline NeMo-transducer ASR. Each finalized German utterance is
committed to CaptionSessionStore and submitted to the shared translation pipeline. Primeline has no
ASR partials, so its English text follows each finalized German utterance.

### Nemotron 3.5

NemotronRecognizer retains one OnlineRecognizer/OnlineStream, forces language=de, and publishes
replaceable German partials plus endpoint finals. Every new partial clears the English partial
projection and submits the newest German hypothesis to the shared translation stage.

### Shared translation stage

CaptionTranslationPipeline owns serialization of one non-thread-safe BergamotTranslator. The
pipeline has a bounded command channel for finalized work and at most one pending live partial.
Multiple partial submissions before translation catches up collapse to the newest source text.

After any partial translation returns, CaptionSessionStore accepts it only if that German source is
still the current partial. This source match is the stale-completion guard.

Final German lines receive stable line IDs before translation. Final translations are submitted in
order and update only that matching line ID, so later transcript changes cannot attach English to
the wrong German line.

On explicit user Stop, the audio queue drains, ASR flushes, any trailing final enters translation,
then the translation pipeline drains before the session becomes idle. On failure, translation work
is cancelled and a cancellation check prevents a result from being committed after cancellation.
The pipeline closes Bergamot native resources in finally. CaptionService performs the final translation/recognizer teardown inside a NonCancellable cleanup section so a terminal failure cannot interrupt native-resource release or the final stopped-state handoff.

## State and durability

CaptionSessionStore is an application-scoped in-memory StateFlow. Each finalized CaptionLine owns
German source text plus nullable English text while translation is pending. State also contains at
most one current German partial and its nullable English translation.

German remains source truth. English is never independently appended and is updated only through a
source-match (partial) or line-ID match (final). History remains bounded to the latest 200 finalized
lines. Clearing transcript removes final pairs; an in-flight translation for a removed line becomes
a no-op.

Transcript, partials, and backend selection are not process-durable. Downloaded ASR and translation
models are the only durable feature data.

## Error and logging boundary

Stable user failures distinguish missing ASR model, missing translation model, audio failure, ASR
initialization, translation initialization/runtime failure, audio backpressure, and unexpected
local failure. Raw paths, URLs, audio, German captions, English translations, and partial text are
never logged. Diagnostics log only failure/lifecycle categories and backend/model metadata.

A bilingual session does not silently continue as German-only after a translation runtime failure.

## Persistence impact

There is no database schema, DataStore, or serialized user-data migration. The change adds one
app-private Bergamot model directory and its archive-SHA install marker. Existing Primeline and
Nemotron installs remain valid and untouched. App backup remains disabled. Removing app data
removes all downloaded models and in-memory caption state.
