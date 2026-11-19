package com.github.andreyasadchy.xtra.ui.chat.v2.assets

import android.os.SystemClock
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetDimensionsResolver
import com.github.andreyasadchy.xtra.util.ChatRenderDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.LinkedHashMap

/** Bounded async cache. A request can only invalidate interested attached views. */
class ChatAssetRepository(
    private val scope: CoroutineScope,
    private val loader: ChatAssetLoader,
    private val maxEntries: Int = 512,
    private val maxConcurrentLoads: Int = 8,
    /** Bulk assets (picker emoji) load in their own lane so they cannot starve chat emotes. */
    private val isBulkAsset: (ChatAssetKey) -> Boolean = { false },
    private val maxConcurrentBulkLoads: Int = 6,
    /** Prefetched-but-unobserved requests allowed to wait; the oldest are dropped first. */
    private val maxQueuedPrefetch: Int = 16,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val wait: suspend (Long) -> Unit = { delay(it) },
    /** High-priority assets (chat badges) get an isolated cache & lane so fast chat never starves or evicts them. */
    private val isPriorityAsset: (ChatAssetKey) -> Boolean = { false },
    private val maxPriorityEntries: Int = 512,
    private val maxConcurrentPriorityLoads: Int = 6,
) {
    private val repositoryJob: Job? = scope.coroutineContext[Job]
    private val states = LinkedHashMap<ChatAssetKey, ChatAssetState>(maxEntries, .75f, true)
    private val priorityStates = LinkedHashMap<ChatAssetKey, ChatAssetState>(maxPriorityEntries, .75f, true)
    private val listeners = HashMap<ChatAssetKey, MutableSet<() -> Unit>>()
    private val retryJobs = HashMap<ChatAssetKey, Job>()
    private val loadJobs = HashMap<ChatAssetKey, Job>()
    /** Loads that have acquired a permit and are inside the loader. */
    private val activeLoads = HashSet<ChatAssetKey>()
    private val loadPermits = Semaphore(maxConcurrentLoads.coerceAtLeast(1))
    private val bulkLoadPermits = Semaphore(maxConcurrentBulkLoads.coerceAtLeast(1))
    private val priorityLoadPermits = Semaphore(maxConcurrentPriorityLoads.coerceAtLeast(1))
    private val queuedPrefetch = LinkedHashSet<ChatAssetKey>()
    private val _changes = MutableStateFlow<ChatAssetKey?>(null)
    val changes: StateFlow<ChatAssetKey?> = _changes.asStateFlow()

    private fun storeFor(key: ChatAssetKey): LinkedHashMap<ChatAssetKey, ChatAssetState> =
        if (isPriorityAsset(key)) priorityStates else states

    @Synchronized fun peek(key: ChatAssetKey): ChatAssetState = storeFor(key)[key] ?: ChatAssetState.Missing

    @Synchronized internal fun cachedStateCount(): Int = states.size + priorityStates.size
    @Synchronized internal fun observerCount(key: ChatAssetKey): Int = listeners[key]?.size ?: 0

    /** Warms shareable assets without attaching work to a transient RecyclerView row. */
    fun prefetch(keys: Iterable<ChatAssetKey>) {
        keys.asSequence().distinct().forEach { key ->
            val shouldRequest = synchronized(this) {
                val store = storeFor(key)
                if (store.containsKey(key)) {
                    false
                } else {
                    store[key] = ChatAssetState.Missing
                    trimCacheLocked()
                    updateDiagnosticsLocked()
                    true
                }
            }
            if (shouldRequest) {
                if (!isPriorityAsset(key)) {
                    synchronized(this) { queuedPrefetch += key }
                }
                request(key)
            }
        }
        dropStalePrefetch()
    }

    /** Pre-warms high-priority badge assets into the isolated priority cache without eviction cap drops. */
    fun prefetchPriority(keys: Iterable<ChatAssetKey>) {
        val toLoad = ArrayList<ChatAssetKey>()
        synchronized(this) {
            keys.asSequence().distinct().forEach { key ->
                val store = storeFor(key)
                val current = store[key]
                if (current == null || current is ChatAssetState.Missing) {
                    store[key] = ChatAssetState.Missing
                    toLoad += key
                }
            }
            trimCacheLocked()
            updateDiagnosticsLocked()
        }
        toLoad.forEach(::request)
    }

    /** Clears transient network failure backoffs and immediately retries all observed failed assets. */
    fun onNetworkAvailable() {
        val now = nowMs()
        val toRetry = synchronized(this) {
            val observedFailed = ArrayList<ChatAssetKey>()
            fun resetMap(map: LinkedHashMap<ChatAssetKey, ChatAssetState>) {
                val iter = map.entries.iterator()
                while (iter.hasNext()) {
                    val (key, state) = iter.next()
                    if (state is ChatAssetState.Failed) {
                        val permanentUntil = state.permanentUntilMs
                        if (permanentUntil == null || permanentUntil <= now) {
                            retryJobs.remove(key)?.cancel()
                            if (!listeners[key].isNullOrEmpty()) {
                                map[key] = ChatAssetState.Missing
                                observedFailed += key
                            } else {
                                iter.remove()
                            }
                        }
                    }
                }
            }
            resetMap(states)
            resetMap(priorityStates)
            updateDiagnosticsLocked()
            observedFailed
        }
        toRetry.forEach(::request)
    }

    /** Fast scrolling must not leave a long queue of rows nobody can see anymore. */
    private fun dropStalePrefetch() {
        val dropped = ArrayList<Job>()
        synchronized(this) {
            val iterator = queuedPrefetch.iterator()
            while (iterator.hasNext()) {
                val key = iterator.next()
                if (states[key] != ChatAssetState.Loading || !listeners[key].isNullOrEmpty() || key in activeLoads) {
                    iterator.remove()
                }
            }
            val excess = queuedPrefetch.size - maxQueuedPrefetch
            if (excess > 0) {
                queuedPrefetch.take(excess).forEach { key ->
                    queuedPrefetch -= key
                    loadJobs.remove(key)?.let(dropped::add)
                    states.remove(key)
                }
                updateDiagnosticsLocked()
            }
        }
        dropped.forEach { it.cancel() }
    }

    fun observe(key: ChatAssetKey, listener: () -> Unit) {
        val readyImage = synchronized(this) {
            val store = storeFor(key)
            listeners.getOrPut(key) { LinkedHashSet() }.add(listener)
            if (store[key] == null) store[key] = ChatAssetState.Missing
            trimCacheLocked()
            updateDiagnosticsLocked()
            (store[key] as? ChatAssetState.Ready)?.image
        }
        readyImage?.intrinsicDimensions()?.let { dimensions ->
            ChatAssetDimensionsResolver.recordDecoded(key, dimensions.width, dimensions.height)
        }
        request(key)
    }

    fun removeObserver(key: ChatAssetKey, listener: () -> Unit) {
        var loadJob: Job? = null
        synchronized(this) {
            val store = storeFor(key)
            listeners[key]?.remove(listener)
            if (listeners[key].isNullOrEmpty()) {
                listeners.remove(key)
                retryJobs.remove(key)?.cancel()
                // RecyclerView can briefly recycle the last row using this asset while a
                // batched publication is being applied. Keep already-started loads and priority badge
                // downloads alive so they complete once and cover all upcoming rows.
                if (key !in activeLoads && !isPriorityAsset(key)) {
                    loadJob = loadJobs.remove(key)
                    if (store[key] is ChatAssetState.Loading) store.remove(key)
                }
                if ((store[key] as? ChatAssetState.Ready)?.image?.evictWhenUnobserved() == true) {
                    store.remove(key)
                }
            }
            trimCacheLocked()
            updateDiagnosticsLocked()
        }
        loadJob?.cancel()
    }

    /** Catalog revisions can make a previous negative result valid again. */
    fun invalidate(keys: Set<ChatAssetKey>) {
        val interested = synchronized(this) {
            keys.forEach { key ->
                val store = storeFor(key)
                if (store[key] is ChatAssetState.Failed) store.remove(key)
            }
            trimCacheLocked()
            updateDiagnosticsLocked()
            keys.filter { !listeners[it].isNullOrEmpty() }
        }
        interested.forEach(::request)
    }

    private fun request(key: ChatAssetKey) {
        val now = nowMs()
        val priority = isPriorityAsset(key)
        val loadJob = synchronized(this) {
            val store = storeFor(key)
            val attempt = when (val state = store[key]) {
                is ChatAssetState.Ready, ChatAssetState.Loading -> return
                is ChatAssetState.Failed if state.nextRetryAtMs > now -> {
                    scheduleRetry(key, state.nextRetryAtMs - now)
                    return
                }
                is ChatAssetState.Failed -> state.attempts + 1
                ChatAssetState.Missing, null -> 1
            }.also { store[key] = ChatAssetState.Loading }
            scope.launch(start = CoroutineStart.LAZY) {
                val currentJob = coroutineContext[Job]
                try {
                    var completedAt: Long
                    val state = try {
                        val permitPool = when {
                            priority -> priorityLoadPermits
                            isBulkAsset(key) -> bulkLoadPermits
                            else -> loadPermits
                        }
                        val loaded = permitPool.withPermit {
                            synchronized(this@ChatAssetRepository) { activeLoads += key }
                            try {
                                loader.load(key)
                            } finally {
                                synchronized(this@ChatAssetRepository) { activeLoads -= key }
                            }
                        }
                        loaded?.intrinsicDimensions()?.let { dimensions ->
                            ChatAssetDimensionsResolver.recordDecoded(key, dimensions.width, dimensions.height)
                        }
                        completedAt = nowMs()
                        loaded?.let(ChatAssetState::Ready)
                            ?: ChatAssetState.Failed(completedAt + retryDelay(attempt), attempt)
                    } catch (e: CancellationException) {
                        // A loader-local timeout is retryable while the repository is alive. A
                        // load canceled after its last observer disappears is discarded instead.
                        val stillObserved = synchronized(this@ChatAssetRepository) {
                            !listeners[key].isNullOrEmpty()
                        }
                        if (repositoryJob?.isActive == false) throw e
                        if (!stillObserved && !priority) {
                            synchronized(this@ChatAssetRepository) {
                                val store = storeFor(key)
                                if (
                                    loadJobs[key] === currentJob &&
                                    listeners[key].isNullOrEmpty() &&
                                    store[key] is ChatAssetState.Loading
                                ) {
                                    store.remove(key)
                                }
                            }
                            return@launch
                        }
                        completedAt = nowMs()
                        ChatAssetState.Failed(completedAt + retryDelay(attempt), attempt)
                    } catch (e: ChatAssetLoadException) {
                        completedAt = nowMs()
                        if (e.statusCode == 400 || e.statusCode == 404 || e.statusCode == 410) {
                            ChatAssetState.Failed(completedAt + 5 * 60_000L, attempt, completedAt + 5 * 60_000L)
                        } else ChatAssetState.Failed(completedAt + retryDelay(attempt), attempt)
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        completedAt = nowMs()
                        ChatAssetState.Failed(completedAt + retryDelay(attempt), attempt)
                    }
                    val callbacks = synchronized(this@ChatAssetRepository) {
                        if (loadJobs[key] !== currentJob) null else {
                            val store = storeFor(key)
                            val observers = listeners[key]?.toList().orEmpty()
                            if (
                                !priority &&
                                observers.isEmpty() &&
                                (state as? ChatAssetState.Ready)?.image?.evictWhenUnobserved() == true
                            ) {
                                // Do not retain a non-shareable decoded handle after a transient
                                // observer disappears while its request is completing.
                                store.remove(key)
                            } else {
                                store[key] = state
                            }
                            retryJobs.remove(key)
                            trimCacheLocked()
                            updateDiagnosticsLocked()
                            observers
                        }
                    } ?: return@launch
                    _changes.value = key
                    callbacks.forEach { it() }
                    if (state is ChatAssetState.Failed) scheduleRetry(key, state.nextRetryAtMs - nowMs())
                } finally {
                    synchronized(this@ChatAssetRepository) {
                        if (loadJobs[key] === currentJob) {
                            loadJobs.remove(key)
                            updateDiagnosticsLocked()
                        }
                    }
                }
            }.also {
                loadJobs[key] = it
                updateDiagnosticsLocked()
            }
        }
        if (!loadJob.start()) {
            synchronized(this) {
                if (loadJobs[key] === loadJob) {
                    loadJobs.remove(key)
                    val store = storeFor(key)
                    if (listeners[key].isNullOrEmpty() && store[key] is ChatAssetState.Loading) {
                        store.remove(key)
                    }
                    updateDiagnosticsLocked()
                }
            }
        }
    }

    /** Reopens a Ready state if Coil evicted the decoded value before this row needed a drawable. */
    internal fun retryIfDrawableUnavailable(key: ChatAssetKey) {
        val shouldRequest = synchronized(this) {
            val store = storeFor(key)
            if (listeners[key].isNullOrEmpty() || store[key] !is ChatAssetState.Ready) {
                false
            } else {
                store[key] = ChatAssetState.Missing
                updateDiagnosticsLocked()
                true
            }
        }
        if (shouldRequest) request(key)
    }

    private fun scheduleRetry(key: ChatAssetKey, delayMs: Long) {
        synchronized(this) {
            if (listeners[key].isNullOrEmpty() || retryJobs[key]?.isActive == true) return
            retryJobs[key] = scope.launch {
                wait(delayMs.coerceAtLeast(0L))
                request(key)
            }
        }
    }

    private fun retryDelay(attempt: Int): Long = when (attempt) {
        1 -> 1_000L
        2 -> 3_000L
        3 -> 10_000L
        4 -> 30_000L
        else -> 60_000L
    }

    private fun trimCacheLocked() {
        trimMapLocked(states, maxEntries)
        trimMapLocked(priorityStates, maxPriorityEntries)
    }

    private fun trimMapLocked(map: LinkedHashMap<ChatAssetKey, ChatAssetState>, limit: Int) {
        if (map.size <= limit) return
        val iterator = map.entries.iterator()
        while (map.size > limit && iterator.hasNext()) {
            val entry = iterator.next()
            if (listeners[entry.key].isNullOrEmpty() && entry.value !is ChatAssetState.Loading) {
                iterator.remove()
            }
        }
    }

    private fun updateDiagnosticsLocked() {
        if (!BuildConfig.PERF_DIAGNOSTICS) return
        val totalCached = states.size + priorityStates.size
        val decodedCount = states.values.count { state ->
            (state as? ChatAssetState.Ready)?.image?.holdsDecodedImage() == true
        } + priorityStates.values.count { state ->
            (state as? ChatAssetState.Ready)?.image?.holdsDecodedImage() == true
        }
        ChatRenderDiagnostics.setAssetRepositoryState(
            activeObservers = listeners.values.sumOf { it.size },
            inFlightLoads = loadJobs.size,
            cachedStates = totalCached,
            decodedHandles = decodedCount,
        )
    }
}
