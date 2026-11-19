package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.ImageView
import coil3.decode.BitmapFactoryDecoder
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageRequest.Listener
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.request.target
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** Shared, bounded image requests for the emote picker and its adapters. */
internal object EmotePickerImageLoader {
    const val INITIAL_PREFETCH_LIMIT = 64
    const val LOOKAHEAD_PREFETCH_LIMIT = 24
    private const val PREFETCH_TRACKER_LIMIT = 512
    /** Prefetches on the wire at once once visible cells have settled. */
    private const val PREFETCH_CONCURRENCY = 8
    private const val PREFETCH_PENDING_LIMIT = 64
    /** Background warming only ever fills idle connections and is capped so it cannot flood the disk cache. */
    private const val WARM_PENDING_LIMIT = 320
    private const val WARM_CONCURRENCY = 3
    private const val FALLBACK_DELAY_MS = 50L
    private val RETRY_DELAYS_MS = longArrayOf(600L, 1_500L, 3_000L, 6_000L, 12_000L)
    private const val PREFETCH_TRACKER_TTL_MS = 15_000L
    private val SEVENTV_SIZE_REGEX = Regex("""(/(?:1x|2x|3x|4x))(\.(?:webp|gif|png|avif)(?:\?.*)?)$""", RegexOption.IGNORE_CASE)
    private val BTTV_SIZE_REGEX = Regex("""(/(?:1x|2x|3x))(\.webp(?:\?.*)?|(?:\?.*)?)$""", RegexOption.IGNORE_CASE)
    private val TWITCH_SIZE_REGEX = Regex("""(/(?:1\.0|2\.0|3\.0))(\?.*)?$""", RegexOption.IGNORE_CASE)
    private val FFZ_SIZE_REGEX = Regex("""/emote/([^/]+)/(?:1|2|4)(\.gif(?:\?.*)?|(?:\?.*)?)$""", RegexOption.IGNORE_CASE)
    private val prefetchedAt = LinkedHashMap<String, Long>(PREFETCH_TRACKER_LIMIT, .75f, true)
    private val lock = Any()
    private val pending = LinkedHashMap<String, ImageRequest>()
    private val warming = LinkedHashMap<String, ImageRequest>()
    private val running = HashMap<String, Disposable?>()
    private val activeVisibleLoads = AtomicInteger(0)
    private val requestGeneration = AtomicLong(0L)
    private val targetsLock = Any()
    private data class TrackedTarget(val item: Emote, val quality: String, val primaryUrl: String)
    private data class InFlightEntry(
        val primaryUrl: String,
        val generation: Long,
        var disposable: Disposable? = null,
        var retryRunnable: Runnable? = null,
    )
    private val visibleTargets = WeakHashMap<ImageView, TrackedTarget>()
    private val inFlightTargets = WeakHashMap<ImageView, InFlightEntry>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Picker cells are 48dp, so the user's chat quality (often 4x animated GIFs) is far more
     * than needed. Downloading and decoding those is what makes a fast scroll crawl.
     * ChatV2 catalogs only carry `url4x`, so derive the compact `2x` CDN URL when `url2x` is absent
     * and keep `url4x` as an automatic fallback in [candidateUrls].
     */
    fun urlFor(context: Context, item: Emote, quality: String): String? {
        val capped = minOf(quality.toIntOrNull() ?: 1, pickerQualityCap(context))
        return effectiveUrlForTier(item, capped) ?: urlFor(item, quality)
    }

    private fun pickerQualityCap(context: Context): Int =
        (pickerRequestSizePx(context) / 48f).roundToInt().coerceIn(1, 2)

    private fun effectiveUrlForTier(item: Emote, tier: Int): String? = when (tier) {
        4 -> item.url4x ?: item.url3x ?: item.url2x ?: item.url1x
        3 -> item.url3x ?: item.url2x ?: item.url1x ?: item.url4x?.let { derivedTierUrl(it, 3) }
        2 -> item.url2x ?: item.url1x ?: item.url4x?.let { derivedTierUrl(it, 2) }
        else -> item.url1x ?: item.url4x?.let { derivedTierUrl(it, 1) }
    }

    private fun urlFor(item: Emote, quality: String): String? = when (quality) {
        "4" -> item.url4x ?: item.url3x ?: item.url2x ?: item.url1x
        "3" -> item.url3x ?: item.url2x ?: item.url1x
        "2" -> item.url2x ?: item.url1x
        else -> item.url1x
    }

    private fun derivedTierUrl(url: String, tier: Int): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.contains("cdn.7tv.app/") && SEVENTV_SIZE_REGEX.containsMatchIn(trimmed) ->
                SEVENTV_SIZE_REGEX.replace(trimmed) { match ->
                    "/${tier.coerceIn(1, 4)}x${match.groupValues[2]}"
                }
            trimmed.contains("cdn.betterttv.net/emote/") && BTTV_SIZE_REGEX.containsMatchIn(trimmed) ->
                BTTV_SIZE_REGEX.replace(trimmed) { match ->
                    val bttvTier = when {
                        tier <= 1 -> "1x"
                        tier == 2 -> "2x"
                        else -> "3x"
                    }
                    "/$bttvTier${match.groupValues[2]}"
                }
            trimmed.contains("static-cdn.jtvnw.net/emoticons/v2/") && TWITCH_SIZE_REGEX.containsMatchIn(trimmed) ->
                TWITCH_SIZE_REGEX.replace(trimmed) { match ->
                    val twitchTier = when {
                        tier <= 1 -> "1.0"
                        tier == 2 -> "2.0"
                        else -> "3.0"
                    }
                    "/$twitchTier${match.groupValues[2]}"
                }
            trimmed.contains("cdn.frankerfacez.com/emote/") && FFZ_SIZE_REGEX.containsMatchIn(trimmed) ->
                FFZ_SIZE_REGEX.replace(trimmed) { match ->
                    val ffzTier = when {
                        tier <= 1 -> "1"
                        tier == 2 -> "2"
                        else -> "4"
                    }
                    "/emote/${match.groupValues[1]}/$ffzTier${match.groupValues[2]}"
                }
            else -> null
        }
    }

    private fun animated(context: Context): Boolean =
        context.prefs().getBoolean(C.CHAT_ANIMATE_PICKER_EMOTES, true)

    private fun candidateUrls(context: Context, item: Emote, quality: String): List<String> =
        listOfNotNull(
            urlFor(context, item, quality),
            item.url4x,
            item.url3x,
            item.url2x,
            item.url1x,
            item.url4x?.let { derivedTierUrl(it, 1) },
        )
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    fun isInFlight(target: ImageView, primaryUrl: String?): Boolean {
        if (primaryUrl.isNullOrEmpty()) return false
        synchronized(targetsLock) {
            val entry = inFlightTargets[target] ?: return false
            if (entry.primaryUrl != primaryUrl) return false
            return entry.retryRunnable != null || entry.disposable?.isDisposed == false
        }
    }

    fun request(
        context: Context,
        item: Emote,
        quality: String,
        explicitUrl: String? = null,
    ): ImageRequest.Builder? {
        val url = explicitUrl ?: urlFor(context, item, quality) ?: return null
        val sizePx = pickerRequestSizePx(context)
        val animated = animated(context)
        return ImageRequest.Builder(context)
            .data(url)
            .size(sizePx, sizePx)
            // A still picker decodes only the first frame, which is much cheaper than animating.
            .apply { if (!animated) decoderFactory(BitmapFactoryDecoder.Factory()) }
            .memoryCacheKey(memoryCacheKey(url, sizePx, animated))
            .diskCacheKey(url)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .apply {
                if (item.thirdParty) {
                    httpHeaders(NetworkHeaders.Builder().apply {
                        add("User-Agent", "Xtra/${BuildConfig.VERSION_NAME}")
                    }.build())
                }
            }
    }

    fun loadInto(context: Context, item: Emote, quality: String, target: ImageView) {
        val appContext = context.applicationContext
        val primaryUrl = urlFor(appContext, item, quality) ?: return
        val candidates = candidateUrls(appContext, item, quality)
        if (candidates.isEmpty()) return
        val oldEntry: InFlightEntry?
        val generation: Long
        synchronized(targetsLock) {
            val existing = inFlightTargets[target]
            if (existing != null &&
                existing.primaryUrl == primaryUrl &&
                (existing.retryRunnable != null || existing.disposable?.isDisposed == false)
            ) {
                visibleTargets[target] = TrackedTarget(item, quality, primaryUrl)
                return
            }
            oldEntry = existing
            generation = requestGeneration.incrementAndGet()
            visibleTargets[target] = TrackedTarget(item, quality, primaryUrl)
            inFlightTargets[target] = InFlightEntry(primaryUrl, generation)
        }
        oldEntry?.retryRunnable?.let(target::removeCallbacks)
        oldEntry?.disposable?.dispose()
        loadInto(
            appContext = appContext,
            item = item,
            quality = quality,
            target = target,
            attempt = 0,
            primaryUrl = primaryUrl,
            candidates = candidates,
            generation = generation,
        )
    }

    fun retryIfNeeded(
        context: Context,
        item: Emote,
        quality: String,
        target: ImageView,
        forceImmediate: Boolean = false,
    ) {
        if (target.drawable != null) return
        val appContext = context.applicationContext
        val primaryUrl = urlFor(appContext, item, quality) ?: return
        if (target.getTag(R.id.emote_image_url_key) != primaryUrl) return
        val existingRetryToCancel: InFlightEntry?
        synchronized(targetsLock) {
            val entry = inFlightTargets[target]
            if (entry != null && entry.primaryUrl == primaryUrl) {
                if (entry.disposable?.isDisposed == false) return
                if (!forceImmediate && entry.retryRunnable != null) return
                existingRetryToCancel = inFlightTargets.remove(target)
            } else {
                existingRetryToCancel = null
            }
        }
        existingRetryToCancel?.retryRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
            target.removeCallbacks(runnable)
        }
        loadInto(appContext, item, quality, target)
    }

    fun clearTarget(target: ImageView) {
        val removed = synchronized(targetsLock) {
            visibleTargets.remove(target)
            inFlightTargets.remove(target)
        }
        removed?.retryRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
            target.removeCallbacks(runnable)
        }
        removed?.disposable?.dispose()
    }

    fun onNetworkAvailable(context: Context) {
        val appContext = context.applicationContext
        synchronized(prefetchedAt) {
            prefetchedAt.clear()
        }
        val snapshot = synchronized(targetsLock) {
            visibleTargets.entries.mapNotNull { entry ->
                val view = entry.key
                val tracked = entry.value
                if (view != null && tracked != null) view to tracked else null
            }
        }
        if (snapshot.isNotEmpty()) {
            mainHandler.post {
                snapshot.forEach { (view, tracked) ->
                    if (view.isAttachedToWindow &&
                        view.drawable == null &&
                        view.getTag(R.id.emote_image_url_key) == tracked.primaryUrl
                    ) {
                        retryIfNeeded(appContext, tracked.item, tracked.quality, view, forceImmediate = true)
                    }
                }
            }
        }
        pump(appContext)
    }

    private fun loadInto(
        appContext: Context,
        item: Emote,
        quality: String,
        target: ImageView,
        attempt: Int,
        primaryUrl: String,
        candidates: List<String>,
        generation: Long,
    ) {
        val urlToTry = candidates[attempt % candidates.size]
        prefetchKey(appContext, item, quality)?.let(::cancelPrefetch)
        val requestBuilder = request(appContext, item, quality, explicitUrl = urlToTry) ?: run {
            synchronized(targetsLock) {
                if (inFlightTargets[target]?.generation == generation) {
                    inFlightTargets.remove(target)
                }
            }
            return
        }
        val finished = AtomicBoolean(false)
        fun markFinished() {
            if (finished.compareAndSet(false, true)) {
                activeVisibleLoads.decrementAndGet()
                pump(appContext)
            }
        }
        val maxRetries = candidates.lastIndex + RETRY_DELAYS_MS.size
        activeVisibleLoads.incrementAndGet()
        val built = requestBuilder
            .target(target)
            .listener(object : Listener {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    synchronized(targetsLock) {
                        if (inFlightTargets[target]?.generation == generation) {
                            inFlightTargets.remove(target)
                        }
                    }
                    markFinished()
                }

                override fun onCancel(request: ImageRequest) {
                    synchronized(targetsLock) {
                        if (inFlightTargets[target]?.generation == generation) {
                            inFlightTargets.remove(target)
                        }
                    }
                    markFinished()
                }

                override fun onError(request: ImageRequest, result: ErrorResult) {
                    markFinished()
                    if (attempt >= maxRetries) {
                        synchronized(targetsLock) {
                            if (inFlightTargets[target]?.generation == generation) {
                                inFlightTargets.remove(target)
                            }
                        }
                        return
                    }
                    val delayMs = if (attempt < candidates.lastIndex) {
                        FALLBACK_DELAY_MS
                    } else {
                        val backoffIndex = (attempt - candidates.lastIndex).coerceIn(0, RETRY_DELAYS_MS.lastIndex)
                        RETRY_DELAYS_MS[backoffIndex]
                    }
                    val retryRunnable = Runnable {
                        synchronized(targetsLock) {
                            val current = inFlightTargets[target]
                            if (current?.generation != generation) return@Runnable
                            current.retryRunnable = null
                            if (!target.isAttachedToWindow ||
                                target.drawable != null ||
                                target.getTag(R.id.emote_image_url_key) != primaryUrl
                            ) {
                                inFlightTargets.remove(target)
                                return@Runnable
                            }
                        }
                        loadInto(appContext, item, quality, target, attempt + 1, primaryUrl, candidates, generation)
                    }
                    val scheduled = synchronized(targetsLock) {
                        val current = inFlightTargets[target]
                        if (current?.generation == generation) {
                            current.disposable = null
                            current.retryRunnable = retryRunnable
                            true
                        } else {
                            false
                        }
                    }
                    if (scheduled) {
                        mainHandler.postDelayed(retryRunnable, delayMs)
                    }
                }
            })
            .build()
        val disposable = appContext.imageLoader.enqueue(built)
        synchronized(targetsLock) {
            val current = inFlightTargets[target]
            if (current?.generation == generation && current.retryRunnable == null && !disposable.isDisposed) {
                current.disposable = disposable
            }
        }
    }

    /**
     * Prefetches go through a small queue instead of straight to the network so they can never
     * occupy every connection a visible cell needs. The newest batch (nearest the scroll position)
     * runs first, and entries the user has scrolled away from fall off the back.
     */
    fun prefetch(context: Context, items: Iterable<Emote>, quality: String) {
        val appContext = context.applicationContext
        val batch = prefetchBatch(appContext, items, quality, PREFETCH_PENDING_LIMIT)
        if (batch.isEmpty()) return
        val dropped = ArrayList<String>()
        synchronized(lock) {
            val older = LinkedHashMap(pending)
            pending.clear()
            batch.forEach { (key, request) ->
                warming.remove(key)
                pending[key] = request
            }
            older.forEach { (key, request) -> if (!pending.containsKey(key)) pending[key] = request }
            while (pending.size > PREFETCH_PENDING_LIMIT) {
                val last = pending.keys.last()
                pending.remove(last)
                dropped += last
            }
        }
        dropped.forEach(::forgetPrefetch)
        // Defer pump to after the current RecyclerView layout/bind pass so visible cells claim slots first.
        mainHandler.post { pump(appContext) }
    }

    /**
     * Quietly fills the disk cache with a channel's picker emotes so the picker opens already
     * populated. Runs only on unmetered networks and only on connections nothing else wants.
     */
    fun warm(context: Context, items: Iterable<Emote>, quality: String) {
        val appContext = context.applicationContext
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivity == null || connectivity.isActiveNetworkMetered) return
        val batch = prefetchBatch(appContext, items, quality, WARM_PENDING_LIMIT)
        if (batch.isEmpty()) return
        val dropped = ArrayList<String>()
        synchronized(lock) {
            batch.forEach { (key, request) ->
                if (!pending.containsKey(key) && !running.containsKey(key)) warming[key] = request
            }
            while (warming.size > WARM_PENDING_LIMIT) {
                val first = warming.keys.first()
                warming.remove(first)
                dropped += first
            }
        }
        dropped.forEach(::forgetPrefetch)
        pump(appContext)
    }

    private fun prefetchBatch(
        appContext: Context,
        items: Iterable<Emote>,
        quality: String,
        limit: Int,
    ): List<Pair<String, ImageRequest>> {
        if (limit <= 0) return emptyList()
        val sizePx = pickerRequestSizePx(appContext)
        val isAnimated = animated(appContext)
        val cappedTier = minOf(quality.toIntOrNull() ?: 1, pickerQualityCap(appContext))
        val keySuffix = "@${sizePx}${if (isAnimated) "" else ":still"}"
        val result = ArrayList<Pair<String, ImageRequest>>(limit)
        val seenUrls = HashSet<String>()
        for (item in items) {
            val url = effectiveUrlForTier(item, cappedTier) ?: urlFor(item, quality) ?: continue
            if (!seenUrls.add(url)) continue
            val key = url + keySuffix
            if (!markForPrefetch(key)) continue
            val built = ImageRequest.Builder(appContext)
                .data(url)
                .size(sizePx, sizePx)
                .apply { if (!isAnimated) decoderFactory(BitmapFactoryDecoder.Factory()) }
                .memoryCacheKey(memoryCacheKey(url, sizePx, isAnimated))
                .diskCacheKey(url)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .apply {
                    if (item.thirdParty) {
                        httpHeaders(NetworkHeaders.Builder().apply {
                            add("User-Agent", "Xtra/${BuildConfig.VERSION_NAME}")
                        }.build())
                    }
                }
                .listener(object : Listener {
                    override fun onCancel(request: ImageRequest) = finishPrefetch(appContext, key, retry = true)
                    override fun onError(request: ImageRequest, result: ErrorResult) = finishPrefetch(appContext, key, retry = true)
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) = finishPrefetch(appContext, key, retry = false)
                })
                .build()
            result += key to built
            if (result.size >= limit) break
        }
        return result
    }

    private fun pump(context: Context) {
        while (true) {
            val activeVisible = activeVisibleLoads.get()
            val next = synchronized(lock) {
                // Visible cells take priority: pause lookahead while visible cells are actively downloading,
                // and pause background warming until zero visible loads remain.
                val (queue, limit) = when {
                    activeVisible > 2 -> return
                    pending.isNotEmpty() -> pending to if (activeVisible == 0) PREFETCH_CONCURRENCY else 2
                    activeVisible == 0 -> warming to WARM_CONCURRENCY
                    else -> return
                }
                if (running.size >= limit) return
                val entry = queue.entries.firstOrNull() ?: return
                queue.remove(entry.key)
                running[entry.key] = null
                entry.key to entry.value
            }
            val disposable = context.imageLoader.enqueue(next.second)
            synchronized(lock) {
                // A memory-cache hit may already have finished the request.
                if (running.containsKey(next.first)) running[next.first] = disposable
            }
        }
    }

    private fun finishPrefetch(context: Context, key: String, retry: Boolean) {
        synchronized(lock) { running.remove(key) }
        if (retry) forgetPrefetch(key)
        pump(context)
    }

    /** A dropped prefetch never loaded, so it must be allowed to come back when scrolled to. */
    private fun forgetPrefetch(key: String) {
        synchronized(prefetchedAt) { prefetchedAt.remove(key) }
    }

    private fun markForPrefetch(key: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(prefetchedAt) {
            val previous = prefetchedAt[key]
            if (previous != null && now - previous < PREFETCH_TRACKER_TTL_MS) return false
            prefetchedAt[key] = now
            while (prefetchedAt.size > PREFETCH_TRACKER_LIMIT) {
                prefetchedAt.remove(prefetchedAt.entries.first().key)
            }
            return true
        }
    }

    private fun prefetchKey(context: Context, item: Emote, quality: String): String? =
        urlFor(context, item, quality)?.let { "$it@${pickerRequestSizePx(context)}${if (animated(context)) "" else ":still"}" }

    private fun cancelPrefetch(key: String) {
        val wasQueued: Boolean
        val disposable: Disposable?
        synchronized(lock) {
            wasQueued = (pending.remove(key) ?: warming.remove(key)) != null
            disposable = running.remove(key)
        }
        if (wasQueued || disposable != null) forgetPrefetch(key)
        disposable?.dispose()
    }

    private fun pickerRequestSizePx(context: Context): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        48f,
        context.resources.displayMetrics,
    ).toInt().coerceAtLeast(1)

    private fun memoryCacheKey(url: String, sizePx: Int, animated: Boolean): String =
        "xtra:emote-picker:$url:${sizePx}x$sizePx" + if (animated) "" else ":still"
}
