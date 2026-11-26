package com.github.andreyasadchy.xtra.ui.multiview.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.annotation.SuppressLint
import com.github.andreyasadchy.xtra.BuildConfig
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import com.github.andreyasadchy.xtra.model.stats.mergeViewingCategoryPatch
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsPlaylistContinuityStore
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsPlaylistParserFactory
import com.github.andreyasadchy.xtra.player.lowlatency.HttpEngineDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.OkHttpDataSource
import com.github.andreyasadchy.xtra.repository.preload.StreamPlaybackConfiguration
import com.github.andreyasadchy.xtra.ui.common.logVideoSurfaceBinding
import com.github.andreyasadchy.xtra.ui.player.LiveBufferHealthTrend
import com.github.andreyasadchy.xtra.ui.player.LivePlaybackStallRecoveryState
import com.github.andreyasadchy.xtra.ui.player.PlaybackRenderersFactory
import com.github.andreyasadchy.xtra.ui.player.StreamHlsMediaSourceFactory
import com.github.andreyasadchy.xtra.ui.player.TwitchVaftController
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.HttpProxyAddress
import com.github.andreyasadchy.xtra.util.httpProxyEnabled
import com.github.andreyasadchy.xtra.util.httpProxyHost
import com.github.andreyasadchy.xtra.util.httpProxyPort
import com.github.andreyasadchy.xtra.util.AdaptiveLiveLoadControl
import com.github.andreyasadchy.xtra.util.AdaptiveLivePlaybackController
import com.github.andreyasadchy.xtra.util.AdaptiveLivePlaybackSpeedControl
import com.github.andreyasadchy.xtra.util.LivePlaybackPolicies
import com.github.andreyasadchy.xtra.util.LivePlaybackSettings
import com.github.andreyasadchy.xtra.util.LivePriorityCatchUpTracker
import com.github.andreyasadchy.xtra.util.NetworkUtils.proxyCandidates
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.util.watch.WatchCreditScheduleInput
import com.github.andreyasadchy.xtra.util.watch.WatchCreditScheduler
import com.github.andreyasadchy.xtra.util.watch.WatchCreditSession
import com.github.andreyasadchy.xtra.util.watch.WatchCreditStatusMode
import com.github.andreyasadchy.xtra.util.watch.WatchCreditTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import com.github.andreyasadchy.xtra.util.m3u8.TwitchVaftDetector
import com.github.andreyasadchy.xtra.util.isVaftEnabled
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import okhttp3.Credentials
import okhttp3.Call
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.preload.StreamPreloadPolicy

@OptIn(UnstableApi::class)
class MultiviewPlaybackCoordinator(
    context: Context,
    private val loadPlaylist: suspend (String, Boolean) -> String,
    private val loadCleanPlaylist: suspend (String, List<String>, Boolean, Boolean) -> PlayerRepository.StreamPlaylistCandidate?,
    private val onSnapshot: (String, MultiviewPlaybackSnapshot) -> Unit,
    private val checkStreamLive: (suspend (String) -> Boolean?)? = null,
    private val sendWatchCredit: (suspend (WatchCreditSession, Int, Int, String?, String?) -> Boolean)? = null,
    private val resolveStreamMetadata: (suspend (String) -> Stream?)? = null,
) {
    private val applicationContext = context.applicationContext
    private var scope = newCoordinatorScope()
    private var lastStoppedRealtimeMs: Long? = null

    private fun newCoordinatorScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun ensureScope(): CoroutineScope {
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = newCoordinatorScope()
        }
        return scope
    }
    private val viewingStatsRecorder by lazy {
        (applicationContext as? XtraApp)?.xtraModule?.viewingStatsRecorder
    }
    private val watchCreditTracker by lazy {
        (applicationContext as? XtraApp)?.xtraModule?.watchCreditTracker
    }
    private val slots = linkedMapOf<String, MultiviewPlayerSlot>()
    private val watchCreditSnapshots = ConcurrentHashMap<Long, WatchCreditScheduleInput>()
    private var nextWatchGeneration = 1L
    private var nextWatchCreditStaggerSlot = 0
    private val tileBounds = mutableMapOf<String, Pair<Int, Int>>()
    private val healthWindow = Timeline.Window()
    private var activeIdentity: String? = null
    private var focusedIdentity: String? = null
    private var qualityMode = MultiviewQualityMode.AUTO
    private var qualityOverrides: Map<String, String> = emptyMap()
    private var audioVolumes: Map<String, Float> = emptyMap()
    private var foreground = true
    private var backgroundPlayback = false
    private var lifecycleStarted = false
    private var backgroundServicePrepared = false

    fun sync(
        streams: List<Stream>,
        activeIdentity: String?,
        focusedIdentity: String?,
        qualityMode: MultiviewQualityMode,
        qualityOverrides: Map<String, String>,
        audioVolumes: Map<String, Float> = emptyMap(),
    ) {
        this.activeIdentity = activeIdentity
        this.focusedIdentity = focusedIdentity
        this.qualityMode = qualityMode
        this.qualityOverrides = qualityOverrides
        this.audioVolumes = audioVolumes
        val desired = streams.mapNotNull { stream ->
            identityOf(stream)?.let { identity -> identity to stream }
        }.toMap()

        slots.keys.toList().filterNot(desired::containsKey).forEach { identity ->
            slots.remove(identity)?.let { slot ->
                releaseViewingStats(slot)
                slot.release()
            }
            tileBounds.remove(identity)
        }
        desired.forEach { (identity, stream) ->
            val slot = slots[identity]
            if (slot == null) {
                val newSlot = createSlot(identity, stream)
                slots[identity] = newSlot
                ensureWatchCreditStarted(newSlot)
                start(newSlot)
            } else {
                if (slot.stream !== stream) {
                    slot.stream = mergeResolvedStreamMetadata(
                        current = stream,
                        resolved = slot.stream,
                    )
                    updateViewingStats(slot)
                    ensureMetadataResolved(slot)
                }
                if (lifecycleStarted &&
                    !slot.hasMediaSource &&
                    slot.loadJob?.isActive != true &&
                    slot.retryJob?.isActive != true &&
                    slot.status == MultiviewSlotStatus.LOADING
                ) {
                    start(slot, force = true)
                }
            }
        }
        slots.values.forEach { applyQuality(it) }
        updateAudio()
        if (lifecycleStarted) maintainBackgroundPlaybackService()
    }

    fun attach(identity: String, playerView: PlayerView) {
        slots[identity]?.let { slot ->
            if (slot.attachedView !== playerView) {
                slot.attachedView?.let { oldView ->
                    logVideoSurfaceBinding("multiview_detach", slot.player, oldView, oldView.player)
                    oldView.player = null
                }
                slot.attachedView = playerView
            }
            if (playerView.player !== slot.player) {
                logVideoSurfaceBinding("multiview_attach", slot.player, playerView, playerView.player)
                playerView.player = slot.player
            }
        }
    }

    fun detach(identity: String, playerView: PlayerView) {
        slots[identity]?.takeIf { it.attachedView === playerView }?.let { slot ->
            logVideoSurfaceBinding("multiview_detach", slot.player, playerView, playerView.player)
            playerView.player = null
            slot.attachedView = null
        }
    }

    fun updateTileBounds(identity: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        tileBounds[identity] = width to height
        slots[identity]?.let(::applyQuality)
    }

    fun retry(identity: String) {
        slots[identity]?.let { slot ->
            slot.retryCount = 0
            slot.manualRetry = false
            slot.retainDowngradeAtMost(1)
            slot.retryJob?.cancel()
            slot.loadJob?.cancel()
            slot.liveCheckJob?.cancel()
            val login = slot.stream.channelLogin?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            if (slot.status == MultiviewSlotStatus.OFFLINE && !slot.customProxy && checkStreamLive != null && login != null) {
                publish(slot, MultiviewSlotStatus.LOADING, retryAvailable = false)
                slot.liveCheckJob = ensureScope().launch {
                    val live = withTimeoutOrNull(LIVE_STATUS_CHECK_TIMEOUT_MS) {
                        runCatching { checkStreamLive.invoke(login) }.getOrNull()
                    }
                    if (!isActive || slots[slot.identity] !== slot) return@launch
                    if (live == false) {
                        publish(slot, MultiviewSlotStatus.OFFLINE, retryAvailable = true)
                    } else {
                        start(slot, force = true)
                    }
                }
            } else {
                start(slot, force = true)
            }
        }
    }

    fun seekToLive(identity: String) {
        val slot = slots[identity] ?: return
        val player = slot.player
        if (player.isCurrentMediaItemLive || player.isCurrentMediaItemDynamic) {
            val now = SystemClock.elapsedRealtime()
            slot.lastIntentionalSeekRealtimeMs = now
            val liveOffsetMs = player.currentLiveOffset
                .takeIf { it != androidx.media3.common.C.TIME_UNSET && it >= 0L }
            slot.liveCatchUpTracker.recordSeek(liveOffsetMs, now)
            player.playWhenReady = true
            player.seekToDefaultPosition()
            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                player.prepare()
            }
        }
    }

    fun seekAllToLive() {
        val now = SystemClock.elapsedRealtime()
        slots.values.forEach { slot ->
            val player = slot.player
            if (player.isCurrentMediaItemLive || player.isCurrentMediaItemDynamic) {
                slot.lastIntentionalSeekRealtimeMs = now
                val liveOffsetMs = player.currentLiveOffset
                    .takeIf { it != androidx.media3.common.C.TIME_UNSET && it >= 0L }
                slot.liveCatchUpTracker.recordSeek(liveOffsetMs, now)
                player.playWhenReady = true
                player.seekToDefaultPosition()
                if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                    player.prepare()
                }
            }
        }
    }

    private fun scheduleBufferingUiUpdate(slot: MultiviewPlayerSlot) {
        if (slot.bufferingUiJob?.isActive == true) return
        slot.bufferingUiJob = ensureScope().launch {
            delay(BUFFERING_UI_DEBOUNCE_MS)
            if (!isActive || slots[slot.identity] !== slot) return@launch
            if (slot.player.playbackState == Player.STATE_BUFFERING &&
                slot.loadJob?.isActive != true &&
                slot.status != MultiviewSlotStatus.BUFFERING
            ) {
                publish(slot, MultiviewSlotStatus.BUFFERING)
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (slot.bufferingUiJob === job) slot.bufferingUiJob = null
            }
        }
    }

    private fun scheduleBufferingWatchdog(slot: MultiviewPlayerSlot, reset: Boolean = false) {
        if (!shouldPlayWhenReady(slot)) {
            slot.bufferingWatchdogJob?.cancel()
            slot.bufferingWatchdogJob = null
            return
        }
        if (!reset && slot.bufferingWatchdogJob?.isActive == true) return
        slot.bufferingWatchdogJob?.cancel()
        slot.bufferingWatchdogJob = ensureScope().launch {
            var baseline = MultiviewStallSnapshot(
                currentPositionMs = slot.player.currentPosition,
                bufferedPositionMs = slot.player.bufferedPosition,
            )
            while (isActive && slots[slot.identity] === slot) {
                delay(WATCHDOG_BUFFERING_TIMEOUT_MS)
                if (!isActive || slots[slot.identity] !== slot) return@launch
                val isStalled = shouldPlayWhenReady(slot) &&
                    slot.loadJob?.isActive != true && (
                    slot.player.playbackState == Player.STATE_BUFFERING ||
                        slot.status == MultiviewSlotStatus.BUFFERING ||
                        slot.status == MultiviewSlotStatus.LOADING ||
                        (slot.hasMediaSource && slot.player.playbackState == Player.STATE_IDLE)
                    )
                if (!isStalled) break
                val current = MultiviewStallSnapshot(
                    currentPositionMs = slot.player.currentPosition,
                    bufferedPositionMs = slot.player.bufferedPosition,
                )
                if (MultiviewStallProgress.hasAdvanced(baseline, current)) {
                    baseline = current
                    continue
                }
                Log.i(
                    TAG,
                    "channel=${slot.identity} watchdog triggered after buffering/loading stall timeout, recovering (playbackState=${slot.player.playbackState}, status=${slot.status})",
                )
                slot.reloadRequired = true
                recoverSlot(slot, null)
                break
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (slot.bufferingWatchdogJob === job) slot.bufferingWatchdogJob = null
            }
        }
    }

    private fun shouldPlayWhenReady(slot: MultiviewPlayerSlot): Boolean {
        return (slot.shouldPlay && foreground) ||
            (slot.shouldPlay && backgroundPlayback && configuredVolume(slot) > 0f)
    }

    fun onStart() {
        lifecycleStarted = true
        ensureNetworkCallback()
        ensureScope()
        foreground = true
        val wasPlayingInBackground = backgroundPlayback
        backgroundPlayback = false
        val stoppedAt = lastStoppedRealtimeMs
        lastStoppedRealtimeMs = null
        val staleAfterIdle = stoppedAt != null &&
            (SystemClock.elapsedRealtime() - stoppedAt) >= STALE_PLAYLIST_IDLE_MS
        slots.values.forEach { slot ->
            ensureWatchCreditStarted(slot)
            ensureMetadataResolved(slot)
            restoreBackgroundVideo(slot)
            slot.attachedView?.let { attach(slot.identity, it) }
            val stuckLoading = slot.status == MultiviewSlotStatus.LOADING && slot.loadJob?.isActive != true
            val needsPrepare = slot.hasMediaSource &&
                (slot.player.playbackState == Player.STATE_IDLE || slot.player.playbackState == Player.STATE_ENDED)
            val missingSource = !slot.hasMediaSource && slot.loadJob?.isActive != true
            val wasSlotPlayingInBackground = wasPlayingInBackground && configuredVolume(slot) > 0f
            val currentConfig = effectiveConfiguration(slot)
            val staleConfig = slot.configurationFingerprint != null &&
                slot.configurationFingerprint != currentConfig.fingerprint
            val staleStream = staleAfterIdle &&
                (!wasSlotPlayingInBackground || slot.player.playbackState != Player.STATE_READY)
            when {
                staleStream || stuckLoading || missingSource || staleConfig -> {
                    slot.reloadRequired = true
                    start(slot, force = true)
                }
                needsPrepare -> {
                    slot.reloadRequired = true
                    recoverSlot(slot, null)
                }
                else -> {
                    slot.player.playWhenReady = shouldPlayWhenReady(slot)
                    updateViewingStats(slot)
                    if (slot.player.playbackState == Player.STATE_BUFFERING ||
                        slot.status == MultiviewSlotStatus.BUFFERING ||
                        slot.status == MultiviewSlotStatus.LOADING
                    ) {
                        scheduleBufferingWatchdog(slot)
                    }
                }
            }
        }
        maintainBackgroundPlaybackService()
    }

    fun onStop(allowBackground: Boolean = true, inPictureInPicture: Boolean = false) {
        if (inPictureInPicture) {
            lifecycleStarted = true
            foreground = true
            backgroundPlayback = false
            slots.values.forEach { slot ->
                slot.player.playWhenReady = shouldPlayWhenReady(slot)
            }
            return
        }
        lifecycleStarted = false
        foreground = false
        lastStoppedRealtimeMs = SystemClock.elapsedRealtime()
        val keepAudioInBackground = allowBackground && applicationContext.prefs()
            .getBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, true)
        val canPlayInBackground = keepAudioInBackground && backgroundServicePrepared
        backgroundPlayback = canPlayInBackground
        slots.values.forEach { slot ->
            // onStop may be called once by the explicit external-player handoff
            // and again by Fragment lifecycle dispatch. Preserve the original
            // intent instead of turning a playing tile into a paused tile on
            // the second callback.
            slot.shouldPlay = slot.shouldPlay || slot.player.playWhenReady
            suppressBackgroundVideo(slot)
            val shouldPlayInBackground = shouldPlayWhenReady(slot)
            slot.player.playWhenReady = shouldPlayInBackground
            if (!shouldPlayInBackground) {
                slot.bufferingUiJob?.cancel()
                slot.bufferingUiJob = null
                slot.bufferingWatchdogJob?.cancel()
                slot.bufferingWatchdogJob = null
            }
            updateViewingStats(slot, forceNotPlaying = !shouldPlayInBackground)
        }
        if (!canPlayInBackground) {
            backgroundServicePrepared = false
            MultiviewBackgroundPlaybackService.stop(applicationContext)
        }
    }

    fun releaseAll() {
        unregisterNetworkCallback()
        scope.coroutineContext[Job]?.cancel()
        scope = newCoordinatorScope()
        lastStoppedRealtimeMs = null
        slots.values.forEach { slot ->
            releaseViewingStats(slot)
            slot.release()
        }
        slots.clear()
        watchCreditSnapshots.clear()
        watchCreditTracker?.clearMultiviewState()
        tileBounds.clear()
        lifecycleStarted = false
        backgroundPlayback = false
        backgroundServicePrepared = false
        MultiviewBackgroundPlaybackService.stop(applicationContext)
    }

    fun player(identity: String): ExoPlayer? = slots[identity]?.player

    /**
     * Applies metadata received while a tile keeps playing. Updating the
     * mutable Stream snapshot is enough to make the next recorder update use
     * the new attribution without restarting the tile or its viewing session.
     */
    fun updateStreamMetadata(
        identity: String,
        title: String?,
        categoryId: String?,
        categoryName: String?,
    ) {
        val slot = slots[identity] ?: return
        val stream = slot.stream
        val nextTitle = title ?: stream.title
        val nextCategory = mergeViewingCategoryPatch(
            currentId = stream.gameId,
            currentName = stream.gameName,
            patchId = categoryId,
            patchName = categoryName,
        )
        val nextCategoryId = nextCategory.id
        val nextCategoryName = nextCategory.name
        if (stream.title == nextTitle &&
            stream.gameId == nextCategoryId &&
            stream.gameName == nextCategoryName
        ) {
            return
        }
        stream.title = nextTitle
        stream.gameId = nextCategoryId
        stream.gameName = nextCategoryName
        updateViewingStats(slot)
    }

    fun updateStreamMetadataForChannel(
        channelId: String?,
        channelLogin: String?,
        title: String?,
        categoryId: String?,
        categoryName: String?,
    ) {
        val slot = slots.values.firstOrNull { slot ->
            (channelId != null && slot.stream.channelId == channelId) ||
                (channelId == null && channelLogin != null &&
                    slot.stream.channelLogin.equals(channelLogin, ignoreCase = true))
        } ?: return
        updateStreamMetadata(slot.identity, title, categoryId, categoryName)
    }

    fun availableQualities(identity: String): List<String> = slots[identity]?.availableQualities.orEmpty()

    fun currentTarget(identity: String): MultiviewQualityTarget? = slots[identity]?.target

    fun liveHealthSnapshot(): Map<String, MultiviewLiveHealth> {
        val nowMs = SystemClock.elapsedRealtime()
        val result = linkedMapOf<String, MultiviewLiveHealth>()
        slots.forEach { (identity, slot) ->
            val player = slot.player
            val state = player.playbackState
            val hasVideo = player.videoSize.width > 0 && player.videoSize.height > 0
            val bufferMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
            val timeline = player.currentTimeline
            val window = if (!timeline.isEmpty && player.currentMediaItemIndex in 0 until timeline.windowCount) {
                timeline.getWindow(player.currentMediaItemIndex, healthWindow)
            } else {
                null
            }
            val liveOffsetMs = if (window != null && window.windowStartTimeMs != androidx.media3.common.C.TIME_UNSET) {
                (window.currentUnixTimeMs - window.windowStartTimeMs - player.currentPosition).coerceAtLeast(0L)
            } else {
                player.currentLiveOffset.takeIf { it != androidx.media3.common.C.TIME_UNSET && it >= 0L }
            }
            val isAudioOnly = slot.target?.isAudioOnly == true
            val allowFpsReading = player.playWhenReady && hasVideo && !isAudioOnly &&
                (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
            val allowBufferReading = player.playWhenReady && (hasVideo || isAudioOnly) &&
                (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
            val targetFps = if (isAudioOnly) null else player.videoFormat?.frameRate?.takeIf { it > 0f }?.roundToInt()
            val renderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: -1L
            val renderedFps = if (!allowFpsReading || renderedFrames < 0L) {
                slot.lastFpsSampleMs = 0L
                slot.lastRenderedFrames = -1L
                slot.lastRenderedFps = null
                null
            } else {
                if (slot.lastFpsSampleMs <= 0L || slot.lastRenderedFrames < 0L || renderedFrames < slot.lastRenderedFrames) {
                    slot.lastFpsSampleMs = nowMs
                    slot.lastRenderedFrames = renderedFrames
                } else {
                    val elapsedMs = nowMs - slot.lastFpsSampleMs
                    if (elapsedMs >= 450L) {
                        val deltaFrames = (renderedFrames - slot.lastRenderedFrames).coerceAtLeast(0L)
                        val rawFps = ((deltaFrames * 1000.0) / elapsedMs).roundToInt()
                        slot.lastRenderedFps = targetFps?.let { rawFps.coerceIn(0, it) } ?: rawFps.coerceAtLeast(0)
                        slot.lastFpsSampleMs = nowMs
                        slot.lastRenderedFrames = renderedFrames
                    }
                }
                slot.lastRenderedFps ?: targetFps
            }
            val reading = if (allowBufferReading && liveOffsetMs != null) {
                slot.bufferHealthTrend.update(bufferMs, liveOffsetMs, nowMs)
            } else {
                slot.bufferHealthTrend.reset()
                null
            }
            if (reading != null) {
                result[identity] = MultiviewLiveHealth(
                    bufferSeconds = reading.bufferSeconds,
                    liveOffsetSeconds = reading.liveOffsetSeconds,
                    isDecreasing = reading.isDecreasing,
                    isBuffering = state == Player.STATE_BUFFERING,
                    isLiveReady = state == Player.STATE_READY,
                    renderedFps = renderedFps,
                    targetFps = targetFps,
                )
            } else if (
                state == Player.STATE_BUFFERING ||
                slot.status == MultiviewSlotStatus.BUFFERING ||
                slot.status == MultiviewSlotStatus.LOADING
            ) {
                result[identity] = MultiviewLiveHealth(
                    bufferSeconds = ((bufferMs + 500L) / 1_000L).toInt(),
                    liveOffsetSeconds = ((liveOffsetMs ?: 0L).coerceAtLeast(0L) / 1_000L).toInt(),
                    isDecreasing = false,
                    isBuffering = true,
                    isLiveReady = false,
                    renderedFps = if (state == Player.STATE_BUFFERING && allowFpsReading) renderedFps else null,
                    targetFps = targetFps,
                )
            }
        }
        return result
    }

    private fun createSlot(identity: String, stream: Stream): MultiviewPlayerSlot {
        val liveSettings = LivePlaybackSettings.fromPreferences(applicationContext.prefs())
        val livePolicies = liveSettings.policies()
        val initialLivePolicy = livePolicies.forMode(liveSettings.mode)
        val liveController = AdaptiveLivePlaybackController(
            initialPolicy = initialLivePolicy,
            adaptiveEnabled = false,
            policySet = livePolicies,
        )
        val liveSpeedControl = AdaptiveLivePlaybackSpeedControl(
            DefaultLivePlaybackSpeedControl.Builder().build(),
        ).apply {
            setAdaptiveTargetLiveOffsetUs(initialLivePolicy.targetOffsetMs * 1_000L)
            setRebufferTargetAdaptationEnabled(false)
        }
        val applyLivePolicy = {
            liveSpeedControl.setAdaptiveTargetLiveOffsetUs(liveController.currentPolicy().targetOffsetMs * 1_000L)
        }
        val player = ExoPlayer.Builder(applicationContext).apply {
            setRenderersFactory(
                PlaybackRenderersFactory(applicationContext).setEnableDecoderFallback(true),
            )
            setLoadControl(
                AdaptiveLiveLoadControl(
                    controller = liveController,
                    initialPolicy = initialLivePolicy,
                    onPolicyChanged = applyLivePolicy,
                    nonLivePolicy = LivePlaybackPolicies.NORMAL,
                    delegate = LivePlaybackPolicies.NORMAL.buffers.buildLoadControl(),
                ),
            )
            setLivePlaybackSpeedControl(liveSpeedControl)
            setAudioAttributes(
                AudioAttributes.DEFAULT,
                false,
            )
            setHandleAudioBecomingNoisy(false)
        }.build()
        val watchGeneration = nextWatchGeneration++
        val staggerSlot = nextWatchCreditStaggerSlot++ % WATCH_CREDIT_STAGGER_SLOTS
        val watchCreditInitialDelayMs =
            WATCH_CREDIT_FIRST_BEAT_BASE_MS + staggerSlot * WATCH_CREDIT_FIRST_BEAT_STEP_MS
        return MultiviewPlayerSlot(
            identity = identity,
            stream = stream,
            player = player,
            liveSettings = liveSettings,
            liveController = liveController,
            liveSpeedControl = liveSpeedControl,
            applyLivePolicy = applyLivePolicy,
            watchGeneration = watchGeneration,
            watchCreditInitialDelayMs = watchCreditInitialDelayMs,
        ).also { slot ->
            slot.livePriorityCatchUpJob = ensureScope().launch {
                while (isActive) {
                    delay(5_000L)
                    val liveOffsetMs = player.currentLiveOffset
                    val now = SystemClock.elapsedRealtime()
                    if (player.isPlaying &&
                        player.playbackState == Player.STATE_READY &&
                        player.totalBufferedDuration > 0L &&
                        player.isCurrentMediaItemDynamic &&
                        liveOffsetMs >= 0L &&
                        liveOffsetMs != androidx.media3.common.C.TIME_UNSET &&
                        applicationContext.prefs().getString(
                            C.PLAYER_VAFT_PLAYBACK_PRIORITY,
                            C.VAFT_PRIORITY_LIVE,
                        ) == C.VAFT_PRIORITY_LIVE &&
                        player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION) &&
                        slot.liveCatchUpTracker.requestSeek(
                            liveOffsetMs = liveOffsetMs,
                            targetOffsetMs = liveController.currentPolicy().targetOffsetMs,
                            realtimeMs = now,
                        )
                    ) {
                        slot.lastIntentionalSeekRealtimeMs = now
                        player.seekToDefaultPosition()
                    }
                }
            }
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateViewingStats(slot)
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            slot.stableRecoveryJob?.cancel()
                            val now = SystemClock.elapsedRealtime()
                            val canRecordRebuffer = shouldPlayWhenReady(slot) &&
                                slot.hasMediaSource &&
                                (now - slot.lastIntentionalSeekRealtimeMs >= SEEK_REBUFFER_GRACE_MS)
                            if (canRecordRebuffer && slot.recordRebuffer()) {
                                applyQuality(slot)
                            }
                            if (slot.status == MultiviewSlotStatus.LIVE || slot.status == MultiviewSlotStatus.LOADING) {
                                scheduleBufferingUiUpdate(slot)
                            } else {
                                slot.bufferingUiJob?.cancel()
                                slot.bufferingUiJob = null
                                publish(slot, MultiviewSlotStatus.BUFFERING)
                            }
                            scheduleBufferingWatchdog(slot)
                        }
                        Player.STATE_READY -> {
                            slot.bufferingUiJob?.cancel()
                            slot.bufferingUiJob = null
                            slot.bufferingWatchdogJob?.cancel()
                            slot.bufferingWatchdogJob = null
                            slot.liveCheckJob?.cancel()
                            slot.liveCheckJob = null
                            slot.markReady()
                            slot.retryCount = 0
                            slot.manualRetry = false
                            publish(slot, MultiviewSlotStatus.LIVE)
                            updateVaft(slot)
                            logSelectedFormat(slot, selectedVideoFormat(player) ?: player.videoFormat)
                            scheduleStableQualityRecovery(slot)
                        }
                        Player.STATE_ENDED -> {
                            slot.bufferingUiJob?.cancel()
                            slot.bufferingUiJob = null
                            slot.bufferingWatchdogJob?.cancel()
                            slot.bufferingWatchdogJob = null
                            publish(slot, MultiviewSlotStatus.OFFLINE, retryAvailable = true)
                        }
                        Player.STATE_IDLE -> {
                            slot.bufferingUiJob?.cancel()
                            slot.bufferingUiJob = null
                            if (slot.hasMediaSource &&
                                shouldPlayWhenReady(slot) &&
                                slot.loadJob?.isActive != true &&
                                (slot.status == MultiviewSlotStatus.LOADING || slot.status == MultiviewSlotStatus.BUFFERING)
                            ) {
                                scheduleBufferingWatchdog(slot)
                            } else {
                                slot.bufferingWatchdogJob?.cancel()
                                slot.bufferingWatchdogJob = null
                            }
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateViewingStats(slot)
                    if (isPlaying) {
                        updateVaft(slot)
                    }
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    updateFormats(slot)
                    updateVaft(slot)
                    val preferredHeight = slot.target?.preferredHeightPx
                    val selectedFormat = selectedVideoFormat(player) ?: player.videoFormat
                    if (!shouldDisableVideoTrack(slot)) {
                        preferredHeight?.let { height ->
                            val preferredSelection = findPreferredVideoSelection(
                                player,
                                height,
                                slot.target?.preferredFrameRate ?: slot.target?.maxFrameRate ?: 60,
                            )
                            val alreadyOverridden = preferredSelection != null &&
                                player.trackSelectionParameters.overrides[preferredSelection.override.mediaTrackGroup] == preferredSelection.override
                            if (preferredSelection != null &&
                                !alreadyOverridden &&
                                !selectedFormatMatches(selectedFormat, preferredSelection.format)
                            ) {
                                applyQuality(slot, force = true)
                            }
                        }
                    }
                    logSelectedFormat(slot, selectedVideoFormat(player) ?: player.videoFormat)
                }

                override fun onPlayerError(error: PlaybackException) {
                    handlePlayerError(slot, error)
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    updateVaft(slot)
                }
            })
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    debugLog("channel=${slot.identity} decoder=$decoderName initMs=$initializationDurationMs")
                }

                override fun onDroppedVideoFrames(
                    eventTime: AnalyticsListener.EventTime,
                    droppedFrames: Int,
                    elapsedMs: Long,
                ) {
                    debugLog("channel=${slot.identity} droppedFrames=$droppedFrames elapsedMs=$elapsedMs")
                }

                override fun onDownstreamFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    mediaLoadData: MediaLoadData,
                ) {
                    if (mediaLoadData.trackType == androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                        logSelectedFormat(slot, mediaLoadData.trackFormat)
                    }
                }

                override fun onLoadError(
                    eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData,
                    error: IOException,
                    wasCanceled: Boolean,
                ) {
                    extractResponseCode(error)?.let { responseCode ->
                        slot.lastResponseCode = responseCode
                        slot.lastResponseWasMaster = isMasterPlaylist(slot, loadEventInfo.uri.toString())
                        if (slot.lastResponseWasMaster) {
                            slot.lastMasterResponseCode = responseCode
                        }
                    }
                    Log.w(
                        TAG,
                        "channel=${slot.identity} loadError type=${mediaLoadData.dataType} canceled=$wasCanceled uri=${loadEventInfo.uri}",
                        error,
                    )
                }
            })
        }
    }

    private data class PlaylistSelection(
        val url: String,
        val playerType: String?,
        val customProxy: Boolean = false,
        val alternate: Boolean = false,
    )

    private fun effectiveConfiguration(slot: MultiviewPlayerSlot): StreamPlaybackConfiguration {
        val base = StreamPlaybackConfiguration.from(applicationContext)
        return if (slot.httpProxyDisabled) {
            base.copy(
                proxyPlaybackAccessToken = false,
                proxyHost = null,
                proxyPort = null,
                proxyBackupServers = emptyList(),
                proxyMultivariantPlaylist = false,
            )
        } else {
            base
        }
    }

    private fun ensureHlsFactory(
        slot: MultiviewPlayerSlot,
        xtraModule: com.github.andreyasadchy.xtra.XtraModule,
        configuration: StreamPlaybackConfiguration,
    ): StreamHlsMediaSourceFactory {
        val existing = slot.hlsFactory
        if (existing != null && slot.configurationFingerprint == configuration.fingerprint) {
            return existing
        }
        releaseActiveMediaItem(slot)
        return StreamHlsMediaSourceFactory(
            context = applicationContext,
            xtraModule = xtraModule,
            configuration = configuration,
        ).also {
            slot.hlsFactory = it
            slot.configurationFingerprint = configuration.fingerprint
        }
    }

    private fun releaseActiveMediaItem(slot: MultiviewPlayerSlot) {
        slot.controlledVaftBadgeMessage?.cancel()
        slot.controlledVaftBadgeMessage = null
        slot.controlledVaftBadgeKey = null
        slot.activeMediaId?.let { mediaId ->
            slot.hlsFactory?.releaseMediaItem(mediaId)
        }
        slot.activeMediaId = null
    }

    private fun start(
        slot: MultiviewPlayerSlot,
        force: Boolean = false,
        requestedSelection: PlaylistSelection? = null,
    ) {
        ensureWatchCreditStarted(slot)
        ensureMetadataResolved(slot)
        slot.retryJob?.cancel()
        slot.loadJob?.cancel()
        slot.liveCheckJob?.cancel()
        slot.bufferingUiJob?.cancel()
        slot.bufferingUiJob = null
        slot.bufferingWatchdogJob?.cancel()
        slot.bufferingWatchdogJob = null
        slot.target = null
        slot.effectiveQualityLabel = null
        slot.bufferHealthTrend.reset()
        slot.liveCatchUpTracker.reset()
        releaseActiveMediaItem(slot)
        if (force) {
            slot.player.stop()
            slot.hasMediaSource = false
            slot.reloadRequired = false
        }
        val initialConfig = effectiveConfiguration(slot)
        val liveSettings = initialConfig.livePlaybackSettings
        slot.liveSettings = liveSettings
        slot.configurationFingerprint = initialConfig.fingerprint
        val initialPolicies = liveSettings.policies()
        val initialPolicy = initialPolicies.forMode(liveSettings.mode)
        slot.liveController.reset(
            initialPolicy = initialPolicy,
            enabled = false,
            policies = initialPolicies,
        )
        slot.liveSpeedControl.setRebufferTargetAdaptationEnabled(false)
        slot.applyLivePolicy()
        slot.loadJob = ensureScope().launch {
            publish(slot, MultiviewSlotStatus.LOADING)
            try {
                val login = slot.stream.channelLogin?.trim()?.lowercase()
                    ?: throw IllegalArgumentException("missing channel login")
                val xtraModule = (applicationContext as? XtraApp)?.xtraModule
                val selection = requestedSelection ?: (
                    withTimeoutOrNull(PLAYLIST_LOAD_TIMEOUT_MS) {
                        loadPlaylistUrl(slot, login, useControlledVaft = xtraModule != null)
                    } ?: throw IOException("Playlist load timed out")
                )
                if (!isActive || slots[slot.identity] !== slot) return@launch
                slot.customProxy = selection.customProxy
                slot.currentPlayerType = selection.playerType
                slot.usingAlternateStream = selection.alternate
                slot.currentPlaylistUrl = selection.url
                slot.lastResponseCode = null
                slot.lastMasterResponseCode = null
                slot.lastResponseWasMaster = false
                slot.sourceGeneration++

                val sourceConfig = effectiveConfiguration(slot)
                val sourceLiveSettings = sourceConfig.livePlaybackSettings
                slot.liveSettings = sourceLiveSettings
                val preferences = applicationContext.prefs()
                val source = if (xtraModule != null) {
                    val hlsFactory = ensureHlsFactory(slot, xtraModule, sourceConfig)
                    val mediaId = if (selection.customProxy) {
                        "multiview-proxy:$login:${slot.watchGeneration}:${slot.sourceGeneration}"
                    } else {
                        "xtra-live:multiview:$login:${slot.watchGeneration}:${slot.sourceGeneration}"
                    }
                    val proxyMediaEnabled = !selection.customProxy &&
                        !slot.httpProxyDisabled &&
                        sourceConfig.httpProxyServers.isNotEmpty() &&
                        preferences.getBoolean(C.PROXY_MEDIA_PLAYLIST, true)
                    val proxyMultivariantEnabled = !selection.customProxy &&
                        !slot.httpProxyDisabled &&
                        sourceConfig.httpProxyServers.isNotEmpty() &&
                        sourceConfig.proxyMultivariantPlaylist
                    slot.httpProxyActive = proxyMediaEnabled || proxyMultivariantEnabled
                    hlsFactory.stateFor(mediaId).proxyMediaPlaylist = proxyMediaEnabled
                    hlsFactory.bindLiveTimelineContinuity(mediaId, slot.continuityStore)
                    val mediaItem = hlsFactory.createLiveMediaItem(
                        mediaId = mediaId,
                        uri = selection.url,
                        title = slot.stream.title,
                        channelName = slot.stream.channelName ?: slot.stream.channelLogin,
                        channelLogo = slot.stream.channelImage,
                    )
                    slot.activeMediaId = mediaId
                    hlsFactory.createMediaSource(mediaItem)
                } else {
                    val livePolicy = sourceLiveSettings.policies().forMode(sourceLiveSettings.mode)
                    val mediaItem = MediaItem.Builder()
                        .setUri(selection.url)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .setLiveConfiguration(sourceLiveSettings.liveConfiguration(livePolicy))
                        .setMediaMetadata(metadata(slot.stream))
                        .build()
                    HlsMediaSource.Factory(createDataSourceFactory(slot))
                        .setPlaylistParserFactory(
                            TwitchHlsPlaylistParserFactory(
                                lowLatencyEnabled = sourceLiveSettings.lowLatencyEnabled,
                                continuityStore = slot.continuityStore,
                            ),
                        )
                        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                        .createMediaSource(mediaItem)
                }
                slot.player.setMediaSource(source)
                slot.hasMediaSource = true
                applyQuality(slot)
                updateAudio()
                slot.player.prepare()
                slot.player.playWhenReady = shouldPlayWhenReady(slot)
                scheduleBufferingWatchdog(slot, reset = true)
                debugLog("channel=${slot.identity} playlistLoaded quality=${slot.target?.label} urlType=${if (slot.customProxy) "proxy" else "usher"} playerType=${slot.currentPlayerType ?: "custom"} alternate=${slot.usingAlternateStream} httpProxyDisabled=${slot.httpProxyDisabled} controlledVaft=${slot.activeMediaId?.startsWith("xtra-live:") == true}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                handleLoadError(slot, error)
            }
        }
    }

    private suspend fun loadPlaylistUrl(
        slot: MultiviewPlayerSlot,
        login: String,
        useControlledVaft: Boolean,
    ): PlaylistSelection {
        val preferences = applicationContext.prefs()
        val proxyUrl = preferences.getString(C.PLAYER_PROXY_URL, null)
        val proxyUrls = StreamPreloadPolicy.customStreamProxyUrls(proxyUrl, login)
        val useCustomProxy = !slot.customProxyDisabled &&
            preferences.getBoolean(C.PLAYER_STREAM_PROXY, false) && proxyUrls.isNotEmpty()
        if (useCustomProxy) {
            return PlaylistSelection(
                url = proxyUrls.first(),
                playerType = null,
                customProxy = true,
            )
        }

        val primaryPlayerType = preferences.getString(C.TOKEN_PLAYER_TYPE, "site") ?: "site"
        if (!useControlledVaft && preferences.isVaftEnabled()) {
            val cleanCandidate = loadCleanPlaylist(
                login,
                listOf(primaryPlayerType),
                false,
                slot.httpProxyDisabled,
            )
            if (cleanCandidate != null) {
                return PlaylistSelection(
                    url = cleanCandidate.url,
                    playerType = cleanCandidate.playerType,
                    alternate = cleanCandidate.playerType != primaryPlayerType,
                )
            }
        }
        return PlaylistSelection(
            url = loadPlaylist(login, slot.httpProxyDisabled),
            playerType = primaryPlayerType,
        )
    }

    private fun handleLoadError(slot: MultiviewPlayerSlot, error: Exception) {
        Log.w(TAG, "channel=${slot.identity} playlist load failed", error)
        val responseCode = extractResponseCode(error)
        val offline = !slot.customProxy && (responseCode == 404 || responseCode == 410)
        handleFailure(slot, error, isOffline = offline, responseCode = responseCode)
    }

    private fun handlePlayerError(slot: MultiviewPlayerSlot, error: PlaybackException) {
        Log.w(TAG, "channel=${slot.identity} player error code=${error.errorCodeName}", error)
        val responseCode = slot.lastResponseCode ?: extractResponseCode(error)
        slot.reloadRequired = true
        // A 404 from the master playlist or Usher response is authoritative evidence that the channel ended
        val offline = !slot.customProxy &&
            (slot.lastMasterResponseCode == 404 || slot.lastMasterResponseCode == 410 ||
             responseCode == 404 || responseCode == 410)
        val decoderFailure = error.errorCodeName.contains("DECODER", true)
        if (decoderFailure) {
            slot.markResourceFailure()
            applyQuality(slot)
        }
        handleFailure(slot, error, offline, responseCode)
    }

    private fun handleFailure(
        slot: MultiviewPlayerSlot,
        error: Throwable,
        isOffline: Boolean,
        responseCode: Int? = extractResponseCode(error),
    ) {
        if (isOffline) {
            slot.retryJob?.cancel()
            slot.bufferingWatchdogJob?.cancel()
            slot.liveCheckJob?.cancel()
            publish(slot, MultiviewSlotStatus.OFFLINE, retryAvailable = true)
            return
        }
        val login = slot.stream.channelLogin?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (!slot.customProxy && checkStreamLive != null && login != null) {
            slot.liveCheckJob?.cancel()
            slot.liveCheckJob = ensureScope().launch {
                val live = withTimeoutOrNull(LIVE_STATUS_CHECK_TIMEOUT_MS) {
                    runCatching { checkStreamLive.invoke(login) }.getOrNull()
                }
                if (!isActive || slots[slot.identity] !== slot) return@launch
                if (live == false && slot.status != MultiviewSlotStatus.LIVE) {
                    slot.retryJob?.cancel()
                    slot.bufferingWatchdogJob?.cancel()
                    publish(slot, MultiviewSlotStatus.OFFLINE, retryAvailable = true)
                }
            }
        }

        if (!hasValidatedNetwork()) {
            slot.retryJob?.cancel()
            publish(slot, MultiviewSlotStatus.RECONNECTING, retryAvailable = true)
            slot.retryJob = ensureScope().launch {
                delay(NETWORK_RETRY_DELAY_MS)
                if (!isActive || slots[slot.identity] !== slot) return@launch
                if (hasValidatedNetwork()) {
                    recoverSlot(slot, error)
                } else {
                    handleFailure(slot, error, isOffline = false, responseCode = responseCode)
                }
            }
            return
        }

        // Match the normal player: a broken custom stream proxy is disabled for
        // this slot and the normal Twitch path is retried immediately. The
        // decision is made only with validated connectivity so an offline device
        // cannot permanently disable a healthy proxy.
        if (responseCode != null && responseCode >= 400 && slot.customProxy) {
            slot.customProxyDisabled = true
            slot.retryCount = 0
            slot.manualRetry = false
            slot.reloadRequired = false
            start(slot, force = true)
            return
        }
        if (responseCode != null && responseCode >= 400 && slot.httpProxyActive) {
            slot.httpProxyDisabled = true
            slot.activeMediaId?.let { slot.hlsFactory?.findState(it)?.proxyMediaPlaylist = false }
            slot.retryCount = 0
            slot.manualRetry = false
            slot.reloadRequired = false
            start(slot, force = true)
            return
        }

        if (slot.retryCount >= MAX_RETRIES) {
            publish(slot, MultiviewSlotStatus.PLAYBACK_UNAVAILABLE, retryAvailable = true)
            slot.retryJob?.cancel()
            slot.retryJob = ensureScope().launch {
                delay(10_000L)
                if (!isActive || slots[slot.identity] !== slot) return@launch
                debugLog("channel=${slot.identity} persistent auto-recovery after max retries")
                slot.reloadRequired = true
                recoverSlot(slot, null)
            }
            return
        }
        val delayMs = RETRY_DELAYS_MS[slot.retryCount.coerceIn(0, RETRY_DELAYS_MS.lastIndex)]
        debugLog("channel=${slot.identity} retry=${slot.retryCount + 1}/$MAX_RETRIES delayMs=$delayMs responseCode=$responseCode reload=${slot.reloadRequired}")
        slot.retryCount++
        publish(slot, MultiviewSlotStatus.RECONNECTING, retryAvailable = true)
        slot.retryJob?.cancel()
        slot.retryJob = ensureScope().launch {
            delay(delayMs)
            if (!isActive || slots[slot.identity] !== slot) return@launch
            try {
                recoverSlot(slot, error)
            } catch (retryError: Exception) {
                if (retryError is CancellationException) throw retryError
                handleFailure(slot, retryError, false, extractResponseCode(retryError))
            }
        }
    }

    private fun recoverSlot(slot: MultiviewPlayerSlot, error: Throwable?) {
        slot.retryJob?.cancel()
        slot.bufferingWatchdogJob?.cancel()
        if (!hasValidatedNetwork()) {
            handleFailure(slot, error ?: IOException("Network unavailable"), isOffline = false)
        } else if (slot.hasMediaSource && !slot.reloadRequired) {
            slot.lastResponseCode = null
            slot.lastMasterResponseCode = null
            slot.player.prepare()
            slot.player.playWhenReady = shouldPlayWhenReady(slot)
            scheduleBufferingWatchdog(slot, reset = true)
        } else {
            slot.reloadRequired = false
            start(slot)
        }
    }

    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onNetworkValidated()
        }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                onNetworkValidated()
            }
        }
    }

    private fun ensureNetworkCallback() {
        if (networkCallbackRegistered) return
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        } catch (_: Throwable) {}
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        } catch (_: Throwable) {}
    }

    private fun onNetworkValidated() {
        ensureScope().launch(Dispatchers.Main) {
            slots.values.forEach { slot ->
                val needsRecovery = slot.player.playbackState == Player.STATE_BUFFERING ||
                    slot.status == MultiviewSlotStatus.RECONNECTING ||
                    slot.status == MultiviewSlotStatus.BUFFERING ||
                    slot.retryJob?.isActive == true
                if (needsRecovery) {
                    Log.i(TAG, "Network validated: recovering slot ${slot.identity}")
                    slot.bufferingWatchdogJob?.cancel()
                    slot.retryJob?.cancel()
                    slot.reloadRequired = true
                    recoverSlot(slot, null)
                }
            }
        }
    }

    private fun hasValidatedNetwork(): Boolean {
        val connectivityManager = applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return true
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun extractResponseCode(error: Throwable): Int? {
        val httpCode = generateSequence(error) { it.cause }
            .mapNotNull { throwable ->
                (throwable as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
            }
            .firstOrNull()
        if (httpCode != null) return httpCode

        return generateSequence(error) { it.cause }
            .mapNotNull { throwable ->
                val msg = throwable.message.orEmpty()
                when {
                    msg.contains("404") -> 404
                    msg.contains("410") -> 410
                    msg.contains("403") -> 403
                    else -> null
                }
            }
            .firstOrNull()
    }

    private fun isMasterPlaylist(slot: MultiviewPlayerSlot, uri: String): Boolean {
        return uri == slot.currentPlaylistUrl ||
            uri.contains("/api/v2/channel/hls/", ignoreCase = true) ||
            uri.contains("/api/channel/hls/", ignoreCase = true) ||
            uri.contains("/channel/hls/", ignoreCase = true)
    }

    private fun applyQuality(slot: MultiviewPlayerSlot, force: Boolean = false) {
        val bounds = tileBounds[slot.identity]
        val target = MultiviewQualityPolicy.target(
            MultiviewQualityInput(
                streamCount = slots.size,
                isActive = slot.identity == activeIdentity,
                isFocused = slot.identity == focusedIdentity,
                tileWidthPx = bounds?.first ?: 0,
                tileHeightPx = bounds?.second ?: 0,
                mode = qualityMode,
                manualOverride = qualityOverrides[slot.identity],
                bufferingDowngradeLevel = slot.downgradeLevel,
                resourcePressure = slot.resourceFailure,
            )
        )
        if (!force && slot.target == target) return
        slot.target = target
        if (target.isAudioOnly) {
            slot.effectiveQualityLabel = null
        }
        val builder = slot.player.trackSelectionParameters.buildUpon()
            .clearVideoSizeConstraints()
            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
            .setMaxVideoFrameRate(target.maxFrameRate)
            .setMaxVideoBitrate(Int.MAX_VALUE)
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, shouldDisableVideoTrack(slot))
        if (!target.isAudioOnly) {
            if (target.maxWidthPx != null && target.maxHeightPx != null) {
                builder.setMaxVideoSize(target.maxWidthPx, target.maxHeightPx)
            }
            target.preferredHeightPx?.let { preferredHeight ->
                findPreferredVideoSelection(
                    slot.player,
                    preferredHeight,
                    target.preferredFrameRate ?: target.maxFrameRate,
                )?.override?.let(builder::setOverrideForType)
            }
        }
        slot.player.trackSelectionParameters = builder.build()
        slot.activeMediaId?.let { mediaId ->
            slot.hlsFactory?.findState(mediaId)?.controlledPlaylist?.setAlternateQualityLimit(
                height = if (target.isAudioOnly) null else (target.preferredHeightPx ?: target.maxHeightPx),
                frameRate = if (target.isAudioOnly) null else (target.preferredFrameRate ?: target.maxFrameRate).takeIf { it > 0 }?.toFloat(),
            )
        }
        updateWatchCreditInput(slot)
        onSnapshot(slot.identity, slot.snapshot(target.label))
        debugLog("channel=${slot.identity} policy mode=$qualityMode streams=${slots.size} active=${slot.identity == activeIdentity} focus=${slot.identity == focusedIdentity} target=${target.label} downgrade=${slot.downgradeLevel}")
    }

    private fun shouldDisableVideoTrack(slot: MultiviewPlayerSlot): Boolean =
        slot.target?.isAudioOnly == true || slot.videoDisabledForBackground || slot.hiddenForVaft

    private data class PreferredVideoSelection(
        val override: TrackSelectionOverride,
        val format: androidx.media3.common.Format,
    )

    private fun findPreferredVideoSelection(
        player: ExoPlayer,
        preferredHeight: Int,
        preferredFrameRate: Int,
    ): PreferredVideoSelection? {
        data class SupportedFormat(
            val group: androidx.media3.common.Tracks.Group,
            val index: Int,
            val format: androidx.media3.common.Format,
        )

        val formats = player.currentTracks.groups
            .filter {
                it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && it.isSupported
            }
            .flatMap { group ->
                (0 until group.mediaTrackGroup.length)
                    .filter { index -> group.isTrackSupported(index) }
                    .map { index -> SupportedFormat(group, index, group.mediaTrackGroup.getFormat(index)) }
            }
        if (formats.isEmpty()) return null

        val atOrBelow = formats.filter { candidate ->
            candidate.format.height in 1..preferredHeight &&
                (candidate.format.frameRate <= 0f ||
                    candidate.format.frameRate <= preferredFrameRate + FRAME_RATE_TOLERANCE)
        }
        val selected = atOrBelow.maxWithOrNull(
            compareBy<SupportedFormat> { it.format.height }
                .thenBy { it.format.frameRate }
                .thenBy { it.format.bitrate },
        ) ?: formats.minWithOrNull(
            compareBy<SupportedFormat> {
                kotlin.math.abs(it.format.height - preferredHeight)
            }.thenBy {
                kotlin.math.abs(
                    (it.format.frameRate.takeIf { fps -> fps > 0f } ?: preferredFrameRate.toFloat()) -
                        preferredFrameRate.toFloat(),
                )
            }.thenByDescending { it.format.bitrate },
        ) ?: return null

        return PreferredVideoSelection(
            override = TrackSelectionOverride(selected.group.mediaTrackGroup, selected.index),
            format = selected.format,
        )
    }

    private fun selectedFormatMatches(
        selected: androidx.media3.common.Format?,
        candidate: androidx.media3.common.Format,
    ): Boolean {
        if (selected == null || selected.height != candidate.height) return false
        return candidate.frameRate <= 0f || selected.frameRate <= 0f ||
            kotlin.math.abs(selected.frameRate - candidate.frameRate) <= FRAME_RATE_TOLERANCE
    }

    private fun scheduleStableQualityRecovery(slot: MultiviewPlayerSlot) {
        if (slot.downgradeLevel <= 0 && !slot.resourceFailure) return
        slot.stableRecoveryJob?.cancel()
        slot.stableRecoveryJob = ensureScope().launch {
            while (isActive && slots[slot.identity] === slot &&
                (slot.downgradeLevel > 0 || slot.resourceFailure)
            ) {
                delay(MultiviewQualityRecovery.STABLE_PLAYBACK_MS)
                if (!isActive || slots[slot.identity] !== slot ||
                    slot.player.playbackState != Player.STATE_READY
                ) return@launch
                if (slot.recoverAfterStable()) {
                    applyQuality(slot)
                    publish(slot, slot.status)
                }
            }
        }
    }

    private fun selectedVideoFormat(player: Player): Format? {
        val group = player.currentTracks.groups.firstOrNull {
            it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && it.isSelected
        } ?: return null
        val selected = (0 until group.length).filter(group::isTrackSelected)
        return if (selected.size == 1) group.getTrackFormat(selected.single()) else null
    }

    private fun selectedControlledFormat(player: Player): Format? {
        val groups = player.currentTracks.groups
        val group = groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && it.isSelected }
            ?: groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSelected }
        val selected = group?.let { (0 until it.length).filter(it::isTrackSelected) }.orEmpty()
        return if (selected.size == 1) group?.getTrackFormat(selected.single()) else null
    }

    @androidx.media3.common.util.UnstableApi
    private fun updateVaft(slot: MultiviewPlayerSlot) {
        if (slots[slot.identity] !== slot) return
        val controlled = slot.activeMediaId?.let { slot.hlsFactory?.findState(it)?.controlledPlaylist }
        if (controlled != null) {
            restoreVaftPlayback(slot)
            slot.vaftAvoidanceJob?.cancel()
            slot.primaryRestoreJob?.cancel()
            if (!slot.player.currentTimeline.isEmpty) {
                val window = slot.player.currentTimeline.getWindow(slot.player.currentMediaItemIndex, healthWindow)
                controlled.selectFormat(selectedControlledFormat(slot.player))
                val windowStartMs = window.windowStartTimeMs
                if (windowStartMs != androidx.media3.common.C.TIME_UNSET) {
                    val windowStartUs = windowStartMs * 1_000L
                    val durationMs = window.durationMs.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0L
                    val windowEndUs = windowStartUs + durationMs * 1_000L
                    if (slot.player.playWhenReady) {
                        controlled.consumeRecovery(windowStartUs, windowEndUs)?.let { _ ->
                            val now = SystemClock.elapsedRealtime()
                            val liveOffsetMs = slot.player.currentLiveOffset
                                .takeIf { it != androidx.media3.common.C.TIME_UNSET && it >= 0L }
                            slot.lastIntentionalSeekRealtimeMs = now
                            slot.liveCatchUpTracker.recordSeek(liveOffsetMs, now)
                            slot.player.seekToDefaultPosition()
                        }
                    }
                    val epochUs = windowStartUs + slot.player.currentPosition.coerceAtLeast(0L) * 1_000L
                    val next = controlled.nextChangeAfter(epochUs)
                    val key = next?.let { "${slot.activeMediaId}:$it" }
                    if (key != slot.controlledVaftBadgeKey) {
                        slot.controlledVaftBadgeMessage?.cancel()
                        slot.controlledVaftBadgeKey = key
                        slot.controlledVaftBadgeMessage = next?.let { boundary ->
                            slot.player.createMessage { _, _ ->
                                slot.controlledVaftBadgeKey = null
                                slot.controlledVaftBadgeMessage = null
                                updateVaft(slot)
                            }.setLooper(Looper.getMainLooper())
                                .setPosition(
                                    slot.player.currentMediaItemIndex,
                                    ((boundary - windowStartUs) / 1_000L).coerceAtLeast(0L),
                                )
                                .send()
                        }
                    }
                }
            }
            return
        }
        slot.controlledVaftBadgeMessage?.cancel()
        slot.controlledVaftBadgeMessage = null
        slot.controlledVaftBadgeKey = null
        inspectVaftState(slot)
    }

    @androidx.media3.common.util.UnstableApi
    private fun inspectVaftState(slot: MultiviewPlayerSlot) {
        if (slots[slot.identity] !== slot) return
        val preferences = applicationContext.prefs()
        val vaftEnabled = preferences.isVaftEnabled()
        val useProxy = slot.httpProxyActive
        if (!vaftEnabled && !useProxy) return
        val playlist = (slot.player.currentManifest as? HlsManifest)?.mediaPlaylist ?: return
        val ads = TwitchVaftDetector.requiresVaft(playlist)
        val changed = ads != slot.vaftRequired
        slot.vaftRequired = ads
        if (changed) {
            debugLog("channel=${slot.identity} vaftState=$ads avoid=$vaftEnabled proxy=$useProxy playerType=${slot.currentPlayerType}")
        }

        if (ads) {
            if (vaftEnabled) {
                suppressVaftPlayback(slot)
                if (slot.vaftAvoidanceJob?.isActive != true) {
                    val currentPlayerType = slot.currentPlayerType
                        ?: preferences.getString(C.TOKEN_PLAYER_TYPE, "site")
                        ?: "site"
                    val playerTypes = slot.vaftController.playerTypesForVaft(currentPlayerType)
                    if (playerTypes.isNotEmpty()) {
                        val login = slot.stream.channelLogin?.trim()?.lowercase() ?: return
                        slot.vaftAvoidanceJob = ensureScope().launch {
                            val candidate = try {
                                loadCleanPlaylist(
                                    login,
                                    playerTypes,
                                    true,
                                    slot.httpProxyDisabled,
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Log.w(TAG, "channel=${slot.identity} alternate VAFT probe failed", error)
                                null
                            }
                            if (isActive && slots[slot.identity] === slot && candidate != null) {
                                start(
                                    slot,
                                    force = true,
                                    requestedSelection = PlaylistSelection(
                                        url = candidate.url,
                                        playerType = candidate.playerType,
                                        alternate = true,
                                    ),
                                )
                            } else if (isActive && slots[slot.identity] === slot) {
                                // Keep the tile quiet until either Twitch exposes
                                // another clean candidate or the VAFT window ends.
                                suppressVaftPlayback(slot)
                            }
                        }.also { job ->
                            job.invokeOnCompletion {
                                if (slot.vaftAvoidanceJob === job) slot.vaftAvoidanceJob = null
                            }
                        }
                    }
                }
            } else if (useProxy) {
                // With VAFT avoidance disabled, preserve the normal player's proxy
                // fallback: a proxy that returns a VAFT is bypassed for this slot.
                slot.httpProxyDisabled = true
                start(slot, force = true)
            }
        } else {
            slot.vaftController.onCleanPlaylist()
            restoreVaftPlayback(slot)
            schedulePrimaryStreamRestore(slot)
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun suppressVaftPlayback(slot: MultiviewPlayerSlot) {
        if (!slot.hiddenForVaft) {
            slot.hiddenForVaft = true
            slot.player.trackSelectionParameters = slot.player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, shouldDisableVideoTrack(slot))
                .build()
        }
        updateAudio()
    }

    @androidx.media3.common.util.UnstableApi
    private fun restoreVaftPlayback(slot: MultiviewPlayerSlot) {
        if (slot.hiddenForVaft) {
            slot.hiddenForVaft = false
            slot.player.trackSelectionParameters = slot.player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, shouldDisableVideoTrack(slot))
                .build()
        }
        updateAudio()
    }

    @androidx.media3.common.util.UnstableApi
    private fun schedulePrimaryStreamRestore(slot: MultiviewPlayerSlot) {
        if (!slot.usingAlternateStream || slot.primaryRestoreJob?.isActive == true || slot.customProxy) return
        val login = slot.stream.channelLogin?.trim()?.lowercase() ?: return
        val primaryPlayerType = applicationContext.prefs().getString(C.TOKEN_PLAYER_TYPE, "site") ?: "site"
        slot.primaryRestoreJob = ensureScope().launch {
            while (isActive && slots[slot.identity] === slot && slot.usingAlternateStream) {
                delay(PRIMARY_RESTORE_INTERVAL_MS)
                if (!isActive || slots[slot.identity] !== slot || !slot.usingAlternateStream) return@launch
                val candidate = try {
                    loadCleanPlaylist(
                        login,
                        listOf(primaryPlayerType),
                        true,
                        slot.httpProxyDisabled,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "channel=${slot.identity} primary restore probe failed", error)
                    null
                }
                if (candidate?.verifiedClean == true && isActive && slots[slot.identity] === slot) {
                    slot.usingAlternateStream = false
                    start(
                        slot,
                        force = true,
                        requestedSelection = PlaylistSelection(
                            url = candidate.url,
                            playerType = candidate.playerType,
                            alternate = false,
                        ),
                    )
                    slot.vaftController.reset()
                    restoreVaftPlayback(slot)
                    debugLog("channel=${slot.identity} restored primary playerType=${candidate.playerType}")
                    return@launch
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (slot.primaryRestoreJob === job) slot.primaryRestoreJob = null
            }
        }
    }

    private fun updateAudio() {
        val fallbackVolume = activeVolume()
        slots.values.forEach { slot ->
            slot.player.volume = MultiviewAudioPolicy.volumeFor(
                identity = slot.identity,
                audioVolumes = audioVolumes,
                hiddenForVaft = slot.hiddenForVaft,
                fallbackVolume = if (slot.identity == activeIdentity) fallbackVolume else 0f,
            )
            updateWatchCreditInput(slot)
        }
    }

    private fun maintainBackgroundPlaybackService() {
        val shouldPrepare = lifecycleStarted &&
            applicationContext.prefs().getBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, true) &&
            slots.values.any { it.shouldPlay && configuredVolume(it) > 0f }
        if (!shouldPrepare) {
            backgroundServicePrepared = false
            MultiviewBackgroundPlaybackService.stop(applicationContext)
            return
        }
        if (!backgroundServicePrepared) {
            backgroundServicePrepared = MultiviewBackgroundPlaybackService.start(applicationContext)
        }
    }

    private fun configuredVolume(slot: MultiviewPlayerSlot): Float {
        return MultiviewAudioPolicy.volumeFor(
            identity = slot.identity,
            audioVolumes = audioVolumes,
            hiddenForVaft = false,
            fallbackVolume = if (slot.identity == activeIdentity) activeVolume() else 0f,
        )
    }

    @androidx.media3.common.util.UnstableApi
    private fun suppressBackgroundVideo(slot: MultiviewPlayerSlot) {
        if (!slot.videoDisabledForBackground) {
            slot.videoDisabledForBackground = true
            slot.player.trackSelectionParameters = slot.player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                .build()
        }
        slot.attachedView?.let { view ->
            if (view.player === slot.player) {
                logVideoSurfaceBinding("multiview_detach", slot.player, view, view.player)
                view.player = null
            }
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun restoreBackgroundVideo(slot: MultiviewPlayerSlot) {
        if (slot.videoDisabledForBackground) {
            slot.videoDisabledForBackground = false
            slot.player.trackSelectionParameters = slot.player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, shouldDisableVideoTrack(slot))
                .build()
        }
    }

    private fun updateFormats(slot: MultiviewPlayerSlot) {
        val manifest = slot.player.currentManifest as? HlsManifest
        val playlist = manifest?.multivariantPlaylist ?: run {
            slot.availableQualities = emptyList()
            publish(slot, slot.status)
            return
        }
        val formats = playlist.variants.mapNotNull { variant ->
            val formatName = variant.format.label?.takeIf { it.isNotBlank() }
                ?: playlist.videos.find { it.groupId == variant.videoGroupId }?.name?.takeIf { it.isNotBlank() }
            variant.format.height.takeIf { it > 0 }?.let { height ->
                MultiviewQualityPolicy.AvailableFormat(
                    height = height,
                    frameRate = variant.format.frameRate,
                    isSource = formatName.equals("Source", true),
                )
            }
        }
        slot.availableQualities = MultiviewQualityPolicy.availableManualLabels(formats)
        publish(slot, slot.status)
    }

    private fun publish(
        slot: MultiviewPlayerSlot,
        status: MultiviewSlotStatus,
        retryAvailable: Boolean = false,
    ) {
        slot.status = status
        updateWatchCreditInput(slot)
        onSnapshot(slot.identity, slot.snapshot(slot.target?.label ?: "AUTO", retryAvailable))
    }

    private fun logSelectedFormat(slot: MultiviewPlayerSlot, format: Format?) {
        if (slot.target?.isAudioOnly == true) return
        if (format == null || format.width <= 0 || format.height <= 0) return
        slot.effectiveQualityLabel = MultiviewQualityPolicy.effectiveFormatLabel(format.width, format.height, format.frameRate)
        onSnapshot(slot.identity, slot.snapshot(slot.effectiveQualityLabel!!))
        debugLog("channel=${slot.identity} selectedFormat=${format.width}x${format.height} fps=${format.frameRate} bitrate=${format.bitrate} codec=${format.sampleMimeType ?: format.codecs}")
    }

    private fun metadata(stream: Stream): MediaMetadata = MediaMetadata.Builder()
        .setTitle(stream.title)
        .setArtist(stream.channelName ?: stream.channelLogin)
        .build()

    private fun activeVolume(): Float {
        return (applicationContext.prefs().getInt(C.PLAYER_VOLUME, 100) / 100f).coerceIn(0f, 1f)
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    @SuppressLint("NewApi")
    private fun createDataSourceFactory(slot: MultiviewPlayerSlot): DataSource.Factory {
        val application = applicationContext as XtraApp
        val module = application.xtraModule
        val preferences = applicationContext.prefs()
        val networkLibrary = preferences.getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val httpProxyEnabled = !slot.httpProxyDisabled && preferences.httpProxyEnabled()
        val proxyUser = preferences.getString(C.PROXY_USER, null)
        val proxyPassword = preferences.getString(C.PROXY_PASSWORD, null)
        val proxyServers = if (!httpProxyEnabled) {
            emptyList()
        } else {
            listOfNotNull(HttpProxyAddress.fromHostAndPort(preferences.httpProxyHost(), preferences.httpProxyPort())) +
                HttpProxyAddress.parseList(preferences.getString(C.PROXY_BACKUP_SERVERS, null))
        }.distinct()
        val proxyMultivariant = httpProxyEnabled &&
            preferences.getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false) &&
            proxyServers.isNotEmpty()
        val proxyMedia = httpProxyEnabled &&
            preferences.getBoolean(C.PROXY_MEDIA_PLAYLIST, true) &&
            proxyServers.isNotEmpty()
        slot.httpProxyActive = proxyMultivariant || proxyMedia
        val headers = preferences.getString(C.PLAYER_STREAM_HEADERS, null)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                hashMapOf<String, String>().apply {
                    json.keys().forEach { key -> put(key, json.optString(key)) }
                }
            }.getOrNull()
        }
        val multivariantProxy = selectiveProxyClient(
            enabled = proxyMultivariant,
            proxies = proxyServers,
            user = proxyUser,
            password = proxyPassword,
            regex = MULTIVARIANT_PLAYLIST_REGEX,
        )
        val mediaProxy = selectiveProxyClient(
            enabled = proxyMedia,
            proxies = proxyServers,
            user = proxyUser,
            password = proxyPassword,
            regex = MEDIA_PLAYLIST_REGEX,
        )
        val upstream = when {
            networkLibrary == C.HTTP_ENGINE && module.httpEngine.value != null -> {
                HttpEngineDataSource.Factory(
                    module.httpEngine.value,
                    module.httpExecutor.value,
                    proxyMultivariant,
                    proxyMedia,
                    null,
                    multivariantProxy,
                    mediaProxy,
                ) { !slot.httpProxyDisabled && preferences.getBoolean(C.PROXY_MEDIA_PLAYLIST, true) }
                    .apply { headers?.let(::setDefaultRequestProperties) }
            }
            else -> OkHttpDataSource.Factory(
                multivariantProxy ?: module.okHttpClient.value,
                mediaProxy,
            ) { !slot.httpProxyDisabled && preferences.getBoolean(C.PROXY_MEDIA_PLAYLIST, true) }
                .apply { headers?.let(::setDefaultRequestProperties) }
        }
        return DefaultDataSource.Factory(applicationContext, upstream)
    }

    private fun selectiveProxyClient(
        enabled: Boolean,
        proxies: List<HttpProxyAddress>,
        user: String?,
        password: String?,
        regex: String,
    ): Call.Factory? {
        if (!enabled || proxies.isEmpty()) return null
        val allowDirectFallback = applicationContext.prefs().getBoolean(C.PROXY_ALLOW_DIRECT_FALLBACK, true)
        return (applicationContext as XtraApp).xtraModule.okHttpClient.value.newBuilder().apply {
            proxySelector(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    return if (Regex(regex).matches(uri.host.orEmpty())) {
                        proxyCandidates(proxies.map(HttpProxyAddress::toProxy), allowDirectFallback)
                    } else {
                        listOf(Proxy.NO_PROXY)
                    }
                }

                override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
            })
            if (!user.isNullOrBlank() && !password.isNullOrBlank()) {
                proxyAuthenticator { _, response ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(user, password))
                        .build()
                }
            }
        }.build()
    }

    private fun identityOf(stream: Stream): String? {
        return stream.channelId?.takeIf { it.isNotBlank() }?.let { "id:${it.lowercase()}" }
            ?: stream.channelLogin?.trim()?.takeIf { it.isNotBlank() }?.let { "login:${it.lowercase()}" }
            ?: stream.id?.takeIf { it.isNotBlank() }?.let { "stream:${it.lowercase()}" }
    }

    /**
     * Statistics intentionally measure cumulative stream-hours: every tile is
     * a separately playing playback source and contributes independently.
     */
    fun isWatchCreditSessionCurrent(session: WatchCreditSession): Boolean {
        val playbackSessionId = session.playbackSessionId ?: return false
        val currentUserId = applicationContext.tokenPrefs().getString(C.USER_ID, null)
        if (currentUserId != session.userId) return false
        val current = watchCreditSnapshots[playbackSessionId] ?: return false
        return current.session == session &&
            current.liveEligible &&
            current.dropsStreamLive == true &&
            current.isPlaying
    }

    private fun ensureWatchCreditStarted(slot: MultiviewPlayerSlot) {
        val sender = sendWatchCredit ?: return
        if (slot.watchCreditJob?.isActive == true) return
        updateWatchCreditInput(slot)
        slot.watchCreditJob = WatchCreditScheduler(
            scope = ensureScope(),
            input = slot.watchCreditInput,
            onNextBeatScheduled = { nextBeatMs ->
                slot.nextWatchCreditAtElapsedMs = nextBeatMs
                val tracker = watchCreditTracker ?: return@WatchCreditScheduler
                val mode = if (nextBeatMs != null) {
                    WatchCreditStatusMode.COUNTING
                } else {
                    slot.lastWatchCreditMode.takeIf { it != WatchCreditStatusMode.COUNTING }
                        ?: WatchCreditStatusMode.PAUSED_USER
                }
                tracker.updateMultiviewTileStatus(
                    channelId = slot.stream.channelId,
                    channelLogin = slot.stream.channelLogin,
                    mode = mode,
                    isBuffering = slot.lastWatchCreditBuffering,
                    isAudioOnly = slot.lastWatchCreditAudioOnly,
                    isBackground = backgroundPlayback,
                    isMuted = slot.lastWatchCreditMuted,
                    nextPingAtElapsedMs = nextBeatMs,
                    streamId = slot.stream.id,
                    gameId = slot.stream.gameId,
                    gameName = slot.stream.gameName,
                )
            },
            onMinuteWatched = { session, minute, game, gameId ->
                val success = sender(
                    session,
                    minute.minutesLogged,
                    minute.secondsOffset,
                    game,
                    gameId,
                )
                Log.d(
                    WatchCreditTelemetry.LOG_TAG,
                    "multiview watch heartbeat channel=${session.channelLogin} success=$success minutes=${minute.minutesLogged}",
                )
            },
            initialDelayMillisOverride = slot.watchCreditInitialDelayMs,
        ).start()
    }

    private fun ensureMetadataResolved(slot: MultiviewPlayerSlot) {
        val resolver = resolveStreamMetadata ?: return
        val needsMetadata = slot.stream.id.isNullOrBlank() ||
            slot.stream.channelId.isNullOrBlank() ||
            slot.stream.gameId.isNullOrBlank()
        if (!needsMetadata || slot.metadataResolveJob?.isActive == true) return
        val login = slot.stream.channelLogin?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return
        slot.metadataResolveJob = ensureScope().launch {
            val resolved = runCatching {
                withTimeoutOrNull(LIVE_STATUS_CHECK_TIMEOUT_MS) {
                    resolver(login)
                }
            }.getOrNull() ?: return@launch
            if (!isActive || slots[slot.identity] !== slot) return@launch
            slot.stream = mergeResolvedStreamMetadata(
                current = slot.stream,
                resolved = resolved,
            )
            updateViewingStats(slot)
        }
    }

    private fun mergeResolvedStreamMetadata(current: Stream, resolved: Stream): Stream {
        return Stream(
            id = current.id?.takeIf { it.isNotBlank() } ?: resolved.id,
            channelId = current.channelId?.takeIf { it.isNotBlank() } ?: resolved.channelId,
            channelLogin = current.channelLogin?.takeIf { it.isNotBlank() } ?: resolved.channelLogin,
            channelName = current.channelName?.takeIf { it.isNotBlank() } ?: resolved.channelName,
            channelImageURL = current.channelImageURL?.takeIf { it.isNotBlank() } ?: resolved.channelImageURL,
            gameId = current.gameId?.takeIf { it.isNotBlank() } ?: resolved.gameId,
            gameSlug = current.gameSlug?.takeIf { it.isNotBlank() } ?: resolved.gameSlug,
            gameName = current.gameName?.takeIf { it.isNotBlank() } ?: resolved.gameName,
            title = current.title?.takeIf { it.isNotBlank() } ?: resolved.title,
            thumbnailURL = current.thumbnailURL?.takeIf { it.isNotBlank() } ?: resolved.thumbnailURL,
            createdAt = current.createdAt?.takeIf { it.isNotBlank() } ?: resolved.createdAt,
            viewerCount = current.viewerCount ?: resolved.viewerCount,
            tags = current.tags ?: resolved.tags,
            dropsAvailable = current.dropsAvailable ?: resolved.dropsAvailable,
            thumbnailGeneration = current.thumbnailGeneration,
            broadcasterType = current.broadcasterType ?: resolved.broadcasterType,
            broadcasterTypeFetchedAtEpochMs = current.broadcasterTypeFetchedAtEpochMs
                ?: resolved.broadcasterTypeFetchedAtEpochMs,
        )
    }

    private fun updateWatchCreditInput(slot: MultiviewPlayerSlot, forceNotPlaying: Boolean = false) {
        val userId = applicationContext.tokenPrefs().getString(C.USER_ID, null)?.takeIf { it.isNotBlank() }
        val streamId = slot.stream.id?.takeIf { it.isNotBlank() }
        val channelId = slot.stream.channelId?.takeIf { it.isNotBlank() }
        val channelLogin = slot.stream.channelLogin?.trim()?.takeIf { it.isNotBlank() }
        val session = if (userId != null && streamId != null && channelId != null && channelLogin != null) {
            WatchCreditSession(
                broadcastId = streamId,
                channelId = channelId,
                channelLogin = channelLogin,
                userId = userId,
                playbackSessionId = slot.watchGeneration,
            )
        } else {
            null
        }
        val liveEligible = slot.hasMediaSource &&
            slot.status != MultiviewSlotStatus.OFFLINE &&
            slot.status != MultiviewSlotStatus.PLAYBACK_UNAVAILABLE
        val dropsStreamLive = slot.status != MultiviewSlotStatus.OFFLINE
        // A tile counts while the viewer is sitting on it: playing normally or
        // still buffering, because the viewer is waiting for that stream to come
        // back. Paused, failed or finished tiles are not credited.
        val beingWatched = !forceNotPlaying &&
            slot.player.playWhenReady &&
            slot.player.playerError == null &&
            (
                slot.player.playbackState == Player.STATE_READY ||
                    slot.player.playbackState == Player.STATE_BUFFERING
                )
        val scheduleInput = WatchCreditScheduleInput(
            session = session,
            liveEligible = liveEligible,
            dropsStreamLive = dropsStreamLive,
            isPlaying = beingWatched,
            isBuffering = false,
            game = slot.stream.gameName,
            gameId = slot.stream.gameId,
        )
        slot.watchCreditInput.value = scheduleInput
        if (session != null) {
            watchCreditSnapshots[slot.watchGeneration] = scheduleInput
        } else {
            watchCreditSnapshots.remove(slot.watchGeneration)
        }
        val mode = when {
            userId == null -> WatchCreditStatusMode.NO_AUTH
            slot.status == MultiviewSlotStatus.OFFLINE || slot.status == MultiviewSlotStatus.PLAYBACK_UNAVAILABLE ->
                WatchCreditStatusMode.OFFLINE
            !beingWatched -> WatchCreditStatusMode.PAUSED_USER
            streamId == null -> WatchCreditStatusMode.MISSING_STREAM_ID
            else -> WatchCreditStatusMode.COUNTING
        }
        val isBufferingNow = slot.player.playbackState == Player.STATE_BUFFERING
        val isAudioOnly = slot.target?.isAudioOnly == true || slot.videoDisabledForBackground
        val isMuted = configuredVolume(slot) <= 0.01f
        slot.lastWatchCreditMode = mode
        slot.lastWatchCreditBuffering = isBufferingNow
        slot.lastWatchCreditAudioOnly = isAudioOnly
        slot.lastWatchCreditMuted = isMuted
        watchCreditTracker?.updateMultiviewTileStatus(
            channelId = slot.stream.channelId,
            channelLogin = slot.stream.channelLogin,
            mode = mode,
            isBuffering = isBufferingNow,
            isAudioOnly = isAudioOnly,
            isBackground = backgroundPlayback,
            isMuted = isMuted,
            nextPingAtElapsedMs = slot.nextWatchCreditAtElapsedMs,
            streamId = streamId,
            gameId = slot.stream.gameId,
            gameName = slot.stream.gameName,
        )
    }

    private fun updateViewingStats(slot: MultiviewPlayerSlot, forceNotPlaying: Boolean = false) {
        viewingStatsRecorder?.update(
            sourceId = "multiview:${slot.identity}",
            metadata = ViewingPlaybackMetadata(
                channelId = slot.stream.channelId,
                channelLogin = slot.stream.channelLogin,
                channelName = slot.stream.channelName,
                channelImage = slot.stream.channelImage,
                categoryId = slot.stream.gameId,
                categoryName = slot.stream.gameName,
                contentType = ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
                contentId = slot.stream.id,
                title = slot.stream.title,
            ),
            isPlaying = !forceNotPlaying && slot.player.isPlaying,
            isBuffering = slot.player.playbackState == Player.STATE_BUFFERING,
        )
        updateWatchCreditInput(slot, forceNotPlaying)
    }

    private fun releaseViewingStats(slot: MultiviewPlayerSlot) {
        viewingStatsRecorder?.release("multiview:${slot.identity}")
        watchCreditSnapshots.remove(slot.watchGeneration)
        slot.watchCreditInput.value = WatchCreditScheduleInput(
            session = null,
            liveEligible = false,
            dropsStreamLive = null,
            isPlaying = false,
            isBuffering = true,
        )
    }

    class MultiviewPlayerSlot(
        val identity: String,
        var stream: Stream,
        val player: ExoPlayer,
        var liveSettings: LivePlaybackSettings,
        val liveController: AdaptiveLivePlaybackController,
        val liveSpeedControl: AdaptiveLivePlaybackSpeedControl,
        val applyLivePolicy: () -> Unit,
        val watchGeneration: Long,
        val watchCreditInitialDelayMs: Long,
    ) {
        var attachedView: PlayerView? = null
        var loadJob: Job? = null
        var retryJob: Job? = null
        var stableRecoveryJob: Job? = null
        var livePriorityCatchUpJob: Job? = null
        val liveCatchUpTracker = LivePriorityCatchUpTracker()
        var lastIntentionalSeekRealtimeMs: Long = Long.MIN_VALUE
        var vaftAvoidanceJob: Job? = null
        var primaryRestoreJob: Job? = null
        var bufferingUiJob: Job? = null
        var bufferingWatchdogJob: Job? = null
        val continuityStore = TwitchHlsPlaylistContinuityStore()
        var hlsFactory: StreamHlsMediaSourceFactory? = null
        var configurationFingerprint: String? = null
        var sourceGeneration: Long = 0L
        var activeMediaId: String? = null
        var controlledVaftBadgeMessage: PlayerMessage? = null
        var controlledVaftBadgeKey: String? = null
        var liveCheckJob: Job? = null
        internal var watchCreditJob: Job? = null
        internal var metadataResolveJob: Job? = null
        @Volatile internal var nextWatchCreditAtElapsedMs: Long? = null
        @Volatile internal var lastWatchCreditMode: WatchCreditStatusMode = WatchCreditStatusMode.STARTING
        @Volatile internal var lastWatchCreditBuffering: Boolean = false
        @Volatile internal var lastWatchCreditAudioOnly: Boolean = false
        @Volatile internal var lastWatchCreditMuted: Boolean = false
        internal val watchCreditInput = MutableStateFlow(
            WatchCreditScheduleInput(
                session = null,
                liveEligible = false,
                dropsStreamLive = null,
                isPlaying = false,
                isBuffering = true,
            ),
        )
        val vaftController = TwitchVaftController()
        internal val bufferHealthTrend = LiveBufferHealthTrend()
        internal var lastFpsSampleMs: Long = 0L
        internal var lastRenderedFrames: Long = -1L
        internal var lastRenderedFps: Int? = null
        var retryCount: Int = 0
        private var recoveryState = MultiviewQualityRecoveryState()
        val downgradeLevel: Int get() = recoveryState.downgradeLevel
        val resourceFailure: Boolean get() = recoveryState.resourcePressure
        var customProxy: Boolean = false
        var customProxyDisabled: Boolean = false
        var httpProxyActive: Boolean = false
        var httpProxyDisabled: Boolean = false
        var currentPlayerType: String? = null
        var currentPlaylistUrl: String? = null
        var usingAlternateStream: Boolean = false
        var vaftRequired: Boolean = false
        var hiddenForVaft: Boolean = false
        var videoDisabledForBackground: Boolean = false
        var lastResponseCode: Int? = null
        var lastMasterResponseCode: Int? = null
        var lastResponseWasMaster: Boolean = false
        var reloadRequired: Boolean = false
        var effectiveQualityLabel: String? = null
        var manualRetry: Boolean = false
        var shouldPlay: Boolean = true
        var hasMediaSource: Boolean = false
        var status: MultiviewSlotStatus = MultiviewSlotStatus.LOADING
        var target: MultiviewQualityTarget? = null
        var availableQualities: List<String> = emptyList()

        fun markReady() {
            recoveryState = MultiviewQualityRecovery.onReady(recoveryState)
        }

        fun recordRebuffer(now: Long = System.currentTimeMillis()): Boolean {
            val previous = recoveryState
            recoveryState = MultiviewQualityRecovery.onBuffering(recoveryState, now)
            return recoveryState.downgradeLevel > previous.downgradeLevel
        }

        fun markResourceFailure(now: Long = System.currentTimeMillis()) {
            recoveryState = MultiviewQualityRecovery.onResourceFailure(recoveryState, now)
        }

        fun retainDowngradeAtMost(level: Int) {
            recoveryState = recoveryState.copy(
                downgradeLevel = recoveryState.downgradeLevel.coerceAtMost(level),
            )
        }

        fun recoverAfterStable(now: Long = System.currentTimeMillis()): Boolean {
            val previous = recoveryState
            recoveryState = MultiviewQualityRecovery.onStablePlayback(recoveryState, now)
            return recoveryState != previous
        }

        fun snapshot(qualityLabel: String, retryAvailable: Boolean = manualRetry) = MultiviewPlaybackSnapshot(
            status = status,
            qualityLabel = qualityLabel,
            availableQualities = availableQualities,
            retryCount = retryCount,
            retryAvailable = retryAvailable,
        )

        fun release() {
            loadJob?.cancel()
            retryJob?.cancel()
            stableRecoveryJob?.cancel()
            livePriorityCatchUpJob?.cancel()
            vaftAvoidanceJob?.cancel()
            primaryRestoreJob?.cancel()
            bufferingUiJob?.cancel()
            bufferingUiJob = null
            bufferingWatchdogJob?.cancel()
            bufferingWatchdogJob = null
            controlledVaftBadgeMessage?.cancel()
            controlledVaftBadgeMessage = null
            controlledVaftBadgeKey = null
            activeMediaId?.let { hlsFactory?.releaseMediaItem(it) }
            activeMediaId = null
            liveCheckJob?.cancel()
            liveCheckJob = null
            watchCreditJob?.cancel()
            watchCreditJob = null
            metadataResolveJob?.cancel()
            metadataResolveJob = null
            attachedView?.let { view ->
                if (view.player === player) {
                    logVideoSurfaceBinding("multiview_detach", player, view, view.player)
                    view.player = null
                }
            }
            attachedView = null
            player.release()
        }
    }

    companion object {
        private const val TAG = "MultiviewPlayback"
        private const val MAX_RETRIES = 6
        private const val BUFFERING_UI_DEBOUNCE_MS = 750L
        private const val SEEK_REBUFFER_GRACE_MS = 3_000L
        private const val WATCHDOG_BUFFERING_TIMEOUT_MS = LivePlaybackStallRecoveryState.DEFAULT_STALL_TIMEOUT_MS
        private const val PLAYLIST_LOAD_TIMEOUT_MS = 10_000L
        private const val STALE_PLAYLIST_IDLE_MS = 5 * 60_000L
        private const val LIVE_STATUS_CHECK_TIMEOUT_MS = 4_000L
        // The first beacon must represent a full minute of watching, like the
        // official player; the stagger only spreads tiles a few seconds apart.
        private const val WATCH_CREDIT_FIRST_BEAT_BASE_MS = 6_000L
        private const val WATCH_CREDIT_FIRST_BEAT_STEP_MS = 1_000L
        private const val WATCH_CREDIT_STAGGER_SLOTS = 4
        private const val NETWORK_RETRY_DELAY_MS = 15_000L
        private const val PRIMARY_RESTORE_INTERVAL_MS = 10_000L
        private const val FRAME_RATE_TOLERANCE = 0.5f
        private val RETRY_DELAYS_MS = longArrayOf(1_500L, 3_000L, 6_000L, 12_000L, 20_000L, 30_000L)
        private const val MULTIVARIANT_PLAYLIST_REGEX = "^usher\\.ttvnw\\.net$"
        private const val MEDIA_PLAYLIST_REGEX = "^(?:[a-z0-9-]+\\.playlist\\.(?:live-video|ttvnw)\\.net|video-weaver\\.[a-z0-9-]+\\.hls\\.ttvnw\\.net)$"
    }
}

enum class MultiviewSlotStatus {
    LOADING,
    LIVE,
    BUFFERING,
    RECONNECTING,
    OFFLINE,
    PLAYBACK_UNAVAILABLE,
}

data class MultiviewPlaybackSnapshot(
    val status: MultiviewSlotStatus,
    val qualityLabel: String,
    val availableQualities: List<String> = emptyList(),
    val retryCount: Int = 0,
    val retryAvailable: Boolean = false,
)

data class MultiviewLiveHealth(
    val bufferSeconds: Int,
    val liveOffsetSeconds: Int,
    val isDecreasing: Boolean,
    val isBuffering: Boolean,
    val isLiveReady: Boolean,
    val renderedFps: Int? = null,
    val targetFps: Int? = null,
)
