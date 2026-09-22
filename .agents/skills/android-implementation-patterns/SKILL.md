---
name: android-implementation-patterns
description: "Use when implementing an already-designed Android/Kotlin pattern: immutable state, lifecycle effects, latest-wins, single-flight, offline transactions, callbackFlow, WorkManager, safe errors. Owners first."
---

# Reusable Android Patterns

These are adaptation templates, not copy-paste mandates. Match the repository's package structure, result/error types, DI, lifecycle APIs, dependency versions, and test utilities. Delete unused layers rather than preserving ceremony.

## 1. Screen contract and immutable state

```kotlin
@JvmInline
value class ItemId(val value: String)

data class ExampleUiState(
    val content: Content = Content.Loading,
    val query: String = "",
) {
    sealed interface Content {
        data object Loading : Content
        data class Data(val items: List<ItemUi>) : Content
        data object Empty : Content
        data class Error(val message: UiText) : Content
    }
}

sealed interface ExampleAction {
    data class QueryChanged(val value: String) : ExampleAction
    data object Retry : ExampleAction
}

```

Use one sealed mode when loading/data/empty/error are mutually exclusive. Keep durable outcomes in `UiState` or the source of truth; effects are only transient presentation requests.

## 2. ViewModel state and latest-wins refresh

```kotlin
class ExampleViewModel(
    private val repository: ExampleRepository,
    private val errorMapper: UiErrorMapper,
) : ViewModel() {
    private val _state = MutableStateFlow(ExampleUiState())
    val state: StateFlow<ExampleUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var refreshGeneration = 0L

    init {
        refreshLatest()
    }

    fun onAction(action: ExampleAction) {
        when (action) {
            is ExampleAction.QueryChanged ->
                _state.update { it.copy(query = action.value) }

            ExampleAction.Retry -> refreshLatest()
        }
    }

    private fun refreshLatest() {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(content = ExampleUiState.Content.Loading) }

            val result = repository.loadItems()
            coroutineContext.ensureActive()
            if (generation != refreshGeneration) return@launch

            result.fold(
                onSuccess = { items ->
                    _state.update { current ->
                        current.copy(
                            content = if (items.isEmpty()) {
                                ExampleUiState.Content.Empty
                            } else {
                                ExampleUiState.Content.Data(items)
                            }
                        )
                    }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    _state.update { current ->
                        current.copy(
                            content = ExampleUiState.Content.Error(errorMapper.map(error))
                        )
                    }
                },
            )
        }
    }
}
```

The initial load and retry share one latest-wins owner: they cancel obsolete work and suppress late completion when the boundary ignores cancellation. The repository must preserve `CancellationException`; the generation guard is not write idempotency. Simple destination selection uses a host navigation callback directly from the UI action instead of an effect stream.

## 3. Compose route: lifecycle-aware state and direct navigation

```kotlin
@Composable
fun ExampleRoute(
    viewModel: ExampleViewModel,
    onOpenItem: (ItemId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnOpenItem by rememberUpdatedState(onOpenItem)

    ExampleScreen(
        state = state,
        onQueryChanged = { value ->
            viewModel.onAction(ExampleAction.QueryChanged(value))
        },
        onRetry = { viewModel.onAction(ExampleAction.Retry) },
        onItemClick = currentOnOpenItem,
    )
}
```

Use imports/APIs that exist in the project. The screen remains a stateless renderer except for UI mechanics such as focus, scroll, dialog expansion, or snackbar host state.


## 4. Optional best-effort presentation effect

Add an effect stream only when the screen has a real transient presentation request that is safe to miss, such as a snackbar or haptic. Do not add it pre-emptively and do not route a critical workflow outcome through it.

```kotlin
sealed interface ExampleEffect {
    data class ShowMessage(val message: UiText) : ExampleEffect
}

private val _effects = MutableSharedFlow<ExampleEffect>(
    replay = 0,
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
val effects: SharedFlow<ExampleEffect> = _effects.asSharedFlow()

fun onHelpClicked() {
    _effects.tryEmit(
        ExampleEffect.ShowMessage(UiText.Resource(R.string.example_help))
    )
}
```

This stream is intentionally lossy. It has no process-death or exactly-once guarantee and is unsuitable for payment, save completion, entitlement, destructive confirmation, or required navigation.

Collect it only while the relevant UI lifecycle is active:

```kotlin
val lifecycleOwner = LocalLifecycleOwner.current

LaunchedEffect(viewModel, lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ExampleEffect.ShowMessage ->
                    snackbarHostState.showSnackbar(effect.message.resolve())
            }
        }
    }
}
```

## 5. Latest-wins search with Flow

```kotlin
class SearchViewModel(
    private val repository: SearchRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")

    val state: StateFlow<SearchUiState> = query
        .map(String::trim)
        .debounce(300)
        .distinctUntilChanged()
        .flatMapLatest { normalized ->
            if (normalized.isBlank()) {
                flowOf(SearchUiState.Idle)
            } else {
                repository.search(normalized)
                    .map<List<SearchItem>, SearchUiState> { items ->
                        if (items.isEmpty()) SearchUiState.Empty
                        else SearchUiState.Results(items)
                    }
                    .onStart { emit(SearchUiState.Loading) }
                    .catch { throwable ->
                        if (throwable is CancellationException) throw throwable
                        emit(SearchUiState.Error)
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SearchUiState.Idle,
        )

    fun onQueryChanged(value: String) {
        query.value = value
    }
}
```

`flatMapLatest` is appropriate for cancelable reads where only the newest result matters. Do not use latest-wins for non-idempotent writes unless the product explicitly supports cancellation/compensation.

## 6. Offline-first local truth and atomic refresh

```kotlin
class ItemRepository(
    private val database: AppDatabase,
    private val itemDao: ItemDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val api: ItemApi,
    private val clock: Clock,
) {
    fun observeItems(): Flow<List<Item>> =
        itemDao.observeAll().map { entities -> entities.map(ItemEntity::toDomain) }

    suspend fun refresh(): DataResult<Unit> = mapBoundaryErrors {
        val response = api.getItems()
        database.withTransaction {
            itemDao.replaceAll(response.items.map(ItemDto::toEntity))
            syncMetadataDao.upsert(
                SyncMetadataEntity(
                    key = "items",
                    refreshedAt = clock.instant(),
                    version = response.version,
                )
            )
        }
    }
}
```

Use a DAO `@Transaction` method instead when it naturally owns the entire atomic operation. Do not inject a DAO and assume it has `withTransaction`. Define whether replace deletes local-only/pending rows before using it.

## 7. Single-flight mutation gate

```kotlin
class FavoriteMutator(
    private val repository: FavoriteRepository,
) {
    private val mutex = Mutex()
    private val inFlight = mutableSetOf<ItemId>()

    suspend fun setFavorite(id: ItemId, favorite: Boolean): MutationResult {
        val accepted = mutex.withLock { inFlight.add(id) }
        if (!accepted) return MutationResult.AlreadyRunning

        return try {
            repository.setFavorite(id, favorite)
        } finally {
            mutex.withLock { inFlight.remove(id) }
        }
    }
}
```

This is first-wins per ID. It does not make a remote mutation idempotent. For retryable writes, use a server/client request ID and transactional persistence where the business operation requires it. Keep gates scoped to the owner; avoid an unmanaged process-global set.

## 8. Callback API to Flow

```kotlin
fun LocationSource.locations(): Flow<Location> = callbackFlow {
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            trySend(location).onFailure {
                // Optional bounded diagnostic; never log sensitive coordinates.
            }
        }
    }

    client.register(listener)
    awaitClose { client.unregister(listener) }
}.conflate()
```

Choose buffering deliberately. `conflate` is valid only when intermediate values may be dropped. Registration failure should close the flow with a mapped error. Always unregister in `awaitClose`; avoid retaining lifecycle owners in the callback source.

## 9. Durable idempotent WorkManager work

```kotlin
class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
    private val repository: UploadRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val uploadId = inputData.getString(KEY_UPLOAD_ID)
            ?: return Result.failure()

        return when (repository.uploadPending(UploadId(uploadId))) {
            UploadOutcome.Done,
            UploadOutcome.AlreadyDone -> Result.success()

            UploadOutcome.Retryable -> Result.retry()
            UploadOutcome.PermanentFailure -> Result.failure()
        }
    }

    companion object {
        const val KEY_UPLOAD_ID = "upload_id"
    }
}
```

Enqueue unique work when duplicate scheduling is possible, use stable IDs rather than large payloads, define constraints/backoff, and make repository mutation idempotent. Worker retry can repeat after partial completion.

## 10. Cancellation-safe error mapping

```kotlin
suspend inline fun <T> mapBoundaryErrors(
    crossinline block: suspend () -> T,
): DataResult<T> = try {
    DataResult.Success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (io: IOException) {
    DataResult.Failure(DataError.Network)
} catch (http: HttpException) {
    DataResult.Failure(DataError.Http(http.code()))
} catch (serialization: SerializationException) {
    DataResult.Failure(DataError.InvalidResponse)
} catch (database: SQLiteException) {
    DataResult.Failure(DataError.Storage)
}
```

Catch only errors the boundary owns. Let programming errors surface. Do not return an empty list, `null`, or success for an unknown exception merely to keep the UI running.

## 11. Focused coroutine test

```kotlin
@Test
fun newest_query_wins() = runTest {
    val repository = FakeSearchRepository(testScheduler)
    val viewModel = SearchViewModel(repository)

    viewModel.onQueryChanged("old")
    advanceTimeBy(350)
    viewModel.onQueryChanged("new")
    advanceUntilIdle()

    assertThat(viewModel.state.value)
        .isEqualTo(SearchUiState.Results(repository.resultsFor("new")))
    assertThat(repository.wasCancelled("old")).isTrue()
}
```

Use the repository's test dispatcher/rule conventions. Assert externally observable behavior and cancellation/duplicate semantics, not private implementation details.

## Adaptation checklist

```text
[ ] Owners and concurrency semantics were named first.
[ ] Template APIs exist in installed dependency versions.
[ ] No new wrapper/layer/dependency was added merely for the pattern.
[ ] Cancellation, recreation, duplicate actions, and offline/stale behavior are defined.
[ ] Critical outcomes do not rely on a transient event stream.
[ ] Room operations use a real database/DAO transaction owner.
[ ] Errors are mapped at the owning boundary without swallowing cancellation.
[ ] Tests prove the risk that motivated the pattern.
```


## Pattern selection discipline

Do not paste a pattern because its shape resembles the task. Select it by invariant:

| Required invariant | Candidate pattern | Do not use when |
|---|---|---|
| latest input owns visible result | latest-wins Flow/job + generation guard if boundary ignores cancellation | operation is a non-idempotent write |
| at most one mutation in flight | mutex/in-flight state/single-flight gate | operations should queue independently |
| ordered durable mutation | Room outbox + unique worker/idempotency | immediate UI-only ephemeral action |
| local database is visible truth | observe DAO + atomic remote refresh | product explicitly requires online-only response |
| transient presentation request | lifecycle-aware effect stream | outcome must survive process death |
| critical once-per-ID outcome | durable state/record + idempotent acknowledgement | best-effort snackbar/haptic |
| callback resource as stream | `callbackFlow` + exact unregister/close | API is naturally one-shot suspendable |
| process-surviving work | unique idempotent WorkManager | work is immediate and screen-bound |

### Adaptation proof

Before using a template, replace every placeholder and verify:

```text
Owner scope:
Truth source:
Duplicate policy:
Cancellation policy:
Error mapping:
Restoration/process behavior:
Account key:
Transaction/idempotency boundary:
Dependency/dispatcher/clock seams actually needed:
Test oracle:
```

Do not preserve template types, layers, wrappers, or names that the repository does not need.

## Additional robust patterns

Read [references/advanced-concurrency-patterns.md](references/advanced-concurrency-patterns.md) when work needs durable outcome acknowledgement, cancellation-advisory generation guards, or account-scoped single-flight. Do not load those patterns for ordinary state/read/write work.

## AI-generated code hazards

- copying all template layers and names into a repository with different owners.
- exposing mutable fields from the template for test convenience.
- changing a Flow pattern to `launch { collect }` per action, accumulating collectors.
- using generation guard as a substitute for cancelling expensive work or making writes idempotent.
- using `Mutex` in one instance while DI creates multiple instances, so “single flight” is not global to the intended key.
- using in-memory acknowledgement for outcomes that must survive process death.
- worker unique name not keyed by account/entity, causing unrelated work replacement or duplication.
- transaction sample updated to include network calls inside Room transaction.
- fake repository behavior differs materially from real cache/serializer/transaction semantics.
- copied error mapper swallows cancellation or maps programmer defects to recoverable UI errors.

## Post-change audit

For every adapted pattern:

1. search for a second implementation of the same owner/policy;
2. test the invariant under rapid duplicate action;
3. test cancellation and stale completion;
4. test failure before and after the side-effect boundary;
5. test recreation/process behavior required by contract;
6. test account/key isolation;
7. inspect cleanup in `finally`/`awaitClose`/transaction rollback;
8. verify the test fails when the essential guard is removed or fault-injected.

### Evidence record

```text
Pattern selected and invariant:
Repository-specific adaptations:
Duplicate/stale evidence:
Cancellation/cleanup evidence:
Durability/restoration evidence:
Account/key isolation:
Fault-injected oracle check:
Template assumptions not applicable:
```
