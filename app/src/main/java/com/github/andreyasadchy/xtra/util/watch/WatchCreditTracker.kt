package com.github.andreyasadchy.xtra.util.watch

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsCategory
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsField
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsFieldKey
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsLogger
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsSeverity
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsTransport
import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class WatchCreditStatusMode {
    COUNTING,
    STARTING,
    PAUSED_REWIND,
    PAUSED_USER,
    OFFLINE,
    NO_AUTH,
    MISSING_STREAM_ID,
    PING_FAILED,
}

data class ChannelWatchCreditStatus(
    val channelId: String? = null,
    val channelLogin: String? = null,
    val mode: WatchCreditStatusMode = WatchCreditStatusMode.STARTING,
    val isBuffering: Boolean = false,
    val isAudioOnly: Boolean = false,
    val isBackground: Boolean = false,
    val isMuted: Boolean = false,
    val inMultiview: Boolean = false,
    val isDropsPriority: Boolean = true,
    val minutesLogged: Int = 0,
    val consecutiveOkPings: Int = 0,
    val consecutiveFailedPings: Int = 0,
    val lastPingAtElapsedMs: Long? = null,
    val lastSuccessAtElapsedMs: Long? = null,
    val nextPingAtElapsedMs: Long? = null,
    val lastPingSuccess: Boolean? = null,
    val lastPingError: String? = null,
    val activeStreamId: String? = null,
    val gameId: String? = null,
    val gameName: String? = null,
)

data class TwitchDropSessionSnapshot(
    val dropId: String,
    val currentMinutesWatched: Int,
    val requiredMinutesWatched: Int? = null,
    val channelId: String? = null,
    val channelLogin: String? = null,
    val observedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
)

/**
 * App-scoped watch-credit coordinator for both single-player (including Audio-Only /
 * Background / disabled chat) and Multiview tiles.
 *
 * Ensures single-player heartbeats survive ChatFragment/Chat disabled states, counts live
 * buffering, fires fast initial beacons (~6s) while preserving strict 60s cadence, and tracks
 * Multiview viewer + Drops focus attribution independently.
 */
class WatchCreditTracker(
    private val applicationContext: Context,
    private val appScope: CoroutineScope,
    private val primaryPlaybackWatchState: PrimaryPlaybackWatchStateStore,
    private val graphQLRepository: GraphQLRepository,
    private val playerRepository: PlayerRepository,
    private val diagnosticsLogger: DiagnosticsLogger? = null,
) {
    private val started = AtomicBoolean(false)

    private val _channelStatuses = MutableStateFlow<Map<String, ChannelWatchCreditStatus>>(emptyMap())
    val channelStatuses: StateFlow<Map<String, ChannelWatchCreditStatus>> = _channelStatuses.asStateFlow()

    private val _activeMultiviewStreams = MutableStateFlow<List<Stream>>(emptyList())
    val activeMultiviewStreams: StateFlow<List<Stream>> = _activeMultiviewStreams.asStateFlow()

    private val _focusedDropsChannelKey = MutableStateFlow<String?>(null)
    val focusedDropsChannelKey: StateFlow<String?> = _focusedDropsChannelKey.asStateFlow()

    private val _twitchDropSession = MutableStateFlow<TwitchDropSessionSnapshot?>(null)
    val twitchDropSession: StateFlow<TwitchDropSessionSnapshot?> = _twitchDropSession.asStateFlow()

    private val _lastHermesDropEventAtElapsedMs = MutableStateFlow<Long?>(null)
    val lastHermesDropEventAtElapsedMs: StateFlow<Long?> = _lastHermesDropEventAtElapsedMs.asStateFlow()

    private val singleScheduleInput = MutableStateFlow(
        WatchCreditScheduleInput(
            session = null,
            liveEligible = false,
            dropsStreamLive = null,
            isPlaying = false,
            isBuffering = false,
        ),
    )
    private var singleSchedulerJob: Job? = null
    private var singleStreamResolveJob: Job? = null

    @Volatile private var singleObservedGeneration: Long? = null
    @Volatile private var singleObservedChannelKey: String? = null
    @Volatile private var singleResolvedStreamId: String? = null
    @Volatile private var singleResolvedGameId: String? = null
    @Volatile private var singleResolvedGameName: String? = null
    @Volatile private var singleStreamOffline: Boolean = false
    @Volatile private var lastSingleNextBeatAtMs: Long? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        ensureSingleSchedulerRunning()
        appScope.launch(Dispatchers.Default) {
            primaryPlaybackWatchState.state.collectLatest { playback ->
                handlePrimaryPlaybackChanged(playback)
            }
        }
    }

    private fun ensureSingleSchedulerRunning() {
        if (singleSchedulerJob?.isActive == true) return
        singleSchedulerJob = WatchCreditScheduler(
            scope = appScope,
            input = singleScheduleInput,
            initialDelayMillisOverride = FIRST_BEAT_DELAY_MS,
            countBuffering = true,
            onNextBeatScheduled = { nextBeatMs ->
                lastSingleNextBeatAtMs = nextBeatMs
                val current = singleScheduleInput.value.session ?: return@WatchCreditScheduler
                val key = channelKey(current.channelId, current.channelLogin, inMultiview = false)
                _channelStatuses.update { map ->
                    val existing = map[key] ?: return@update map
                    map + (key to existing.copy(nextPingAtElapsedMs = nextBeatMs))
                }
            },
            onMinuteWatched = { session, minute, game, gameId ->
                sendSingleHeartbeat(session, minute, game, gameId)
            },
        ).start()
    }

    private fun handlePrimaryPlaybackChanged(playback: PrimaryPlaybackWatchState?) {
        if (playback == null) {
            singleObservedGeneration = null
            singleObservedChannelKey = null
            singleResolvedStreamId = null
            singleResolvedGameId = null
            singleResolvedGameName = null
            singleStreamOffline = false
            singleStreamResolveJob?.cancel()
            singleStreamResolveJob = null
            singleScheduleInput.value = WatchCreditScheduleInput(
                session = null,
                liveEligible = false,
                dropsStreamLive = null,
                isPlaying = false,
                isBuffering = false,
            )
            _channelStatuses.update { map -> map.filterKeys { !it.startsWith("single:") } }
            return
        }

        val metadata = playback.metadata
        val channelId = metadata.normalizedChannelId
        val channelLogin = metadata.channelLogin?.trim()?.takeIf(String::isNotEmpty)
        val chKey = focusKey(channelId, channelLogin)
        if (singleObservedGeneration != playback.generation || singleObservedChannelKey != chKey) {
            singleObservedGeneration = playback.generation
            if (singleObservedChannelKey != chKey) {
                singleObservedChannelKey = chKey
                singleResolvedStreamId = null
                singleResolvedGameId = null
                singleResolvedGameName = null
                singleStreamOffline = false
            }
        }

        val metadataStreamId = metadata.contentId?.trim()?.takeIf(String::isNotEmpty)
        if (metadataStreamId != null) {
            singleResolvedStreamId = metadataStreamId
            singleStreamOffline = false
        }
        val activeStreamId = metadataStreamId ?: singleResolvedStreamId

        val userId = applicationContext.tokenPrefs().getString(C.USER_ID, null)?.takeIf(String::isNotBlank)
        val isLiveContent = metadata.contentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE
        val multiviewOwnsChannel = multiviewStreamForChannel(channelId, channelLogin) != null

        if (isLiveContent && !singleStreamOffline && activeStreamId == null &&
            (!channelId.isNullOrBlank() || !channelLogin.isNullOrBlank())
        ) {
            resolveSingleStreamMetadata(channelId, channelLogin, playback.generation)
        }

        val session = if (
            !multiviewOwnsChannel &&
            userId != null &&
            isLiveContent &&
            playback.liveEligible &&
            !singleStreamOffline &&
            !activeStreamId.isNullOrBlank() &&
            !channelId.isNullOrBlank() &&
            !channelLogin.isNullOrBlank()
        ) {
            WatchCreditSession(
                broadcastId = activeStreamId,
                channelId = channelId,
                channelLogin = channelLogin,
                userId = userId,
                playbackSessionId = playback.generation,
            )
        } else {
            null
        }

        val effectiveGameName = metadata.categoryName?.takeIf(String::isNotBlank) ?: singleResolvedGameName
        val effectiveGameId = metadata.categoryId?.takeIf(String::isNotBlank) ?: singleResolvedGameId

        singleScheduleInput.value = WatchCreditScheduleInput(
            session = session,
            liveEligible = playback.liveEligible && isLiveContent && !multiviewOwnsChannel,
            dropsStreamLive = !singleStreamOffline,
            isPlaying = playback.isPlaying,
            isBuffering = playback.isBuffering && playback.playWhenReady,
            game = effectiveGameName,
            gameId = effectiveGameId,
        )

        val statusKey = channelKey(channelId, channelLogin, inMultiview = false)
        _channelStatuses.update { map ->
            val existing = map[statusKey]
            val mode = when {
                userId == null -> WatchCreditStatusMode.NO_AUTH
                singleStreamOffline -> WatchCreditStatusMode.OFFLINE
                isLiveContent && !playback.liveEligible -> WatchCreditStatusMode.PAUSED_REWIND
                !playback.playWhenReady && !playback.isPlaying && !playback.isBuffering -> WatchCreditStatusMode.PAUSED_USER
                activeStreamId.isNullOrBlank() -> WatchCreditStatusMode.MISSING_STREAM_ID
                existing?.lastPingSuccess == false -> WatchCreditStatusMode.PING_FAILED
                (existing?.consecutiveOkPings ?: 0) > 0 -> WatchCreditStatusMode.COUNTING
                else -> WatchCreditStatusMode.STARTING
            }
            val resetCounts = existing?.activeStreamId != null && activeStreamId != null && existing.activeStreamId != activeStreamId
            val base = if (resetCounts || existing == null) {
                ChannelWatchCreditStatus(channelId = channelId, channelLogin = channelLogin, inMultiview = false)
            } else {
                existing
            }
            val cleaned = map.filterKeys { !it.startsWith("single:") || it == statusKey }
            cleaned + (statusKey to base.copy(
                channelId = channelId ?: base.channelId,
                channelLogin = channelLogin ?: base.channelLogin,
                mode = mode,
                isBuffering = playback.isBuffering && playback.playWhenReady,
                isAudioOnly = playback.isAudioOnly,
                isBackground = playback.isBackground,
                isMuted = playback.isMuted,
                inMultiview = false,
                isDropsPriority = true,
                nextPingAtElapsedMs = if (session != null) lastSingleNextBeatAtMs else null,
                activeStreamId = activeStreamId ?: base.activeStreamId,
                gameId = effectiveGameId ?: base.gameId,
                gameName = effectiveGameName ?: base.gameName,
            ))
        }
    }

    private fun resolveSingleStreamMetadata(
        channelId: String?,
        channelLogin: String?,
        expectedGeneration: Long,
    ) {
        if (singleStreamResolveJob?.isActive == true) return
        singleStreamResolveJob = appScope.launch(Dispatchers.IO) {
            try {
                val network = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                val headers = TwitchApiHelper.getGQLHeaders(applicationContext, true)
                val response = graphQLRepository.loadQueryUsersStream(
                    networkLibrary = network,
                    headers = headers,
                    ids = channelId?.takeIf(String::isNotBlank)?.let(::listOf),
                    logins = if (channelId.isNullOrBlank()) channelLogin?.takeIf(String::isNotBlank)?.let(::listOf) else null,
                )
                val stream = response.data?.users?.firstOrNull()?.stream
                val current = primaryPlaybackWatchState.state.value ?: return@launch
                if (current.generation != expectedGeneration) return@launch
                if (stream?.id?.isNotBlank() == true) {
                    singleResolvedStreamId = stream.id
                    singleResolvedGameId = stream.game?.id
                    singleResolvedGameName = stream.game?.displayName
                    singleStreamOffline = false
                }
                handlePrimaryPlaybackChanged(primaryPlaybackWatchState.state.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(WatchCreditTelemetry.LOG_TAG, "single stream metadata resolve failed", e)
            }
        }
    }

    private suspend fun sendSingleHeartbeat(
        session: WatchCreditSession,
        minute: WatchCreditMinute,
        game: String?,
        gameId: String?,
    ) {
        val currentPlayback = primaryPlaybackWatchState.state.value ?: return
        if (currentPlayback.generation != session.playbackSessionId) return
        if (multiviewStreamForChannel(session.channelId, session.channelLogin) != null) return

        val networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val sendResult = playerRepository.sendMinuteWatchedDetailed(
            networkLibrary = networkLibrary,
            userId = session.userId,
            streamId = session.broadcastId,
            channelId = session.channelId,
            channelLogin = session.channelLogin,
            playbackSessionId = session.playbackSessionId ?: 0L,
            isSessionCurrent = {
                val cur = primaryPlaybackWatchState.state.value
                cur != null &&
                    cur.generation == session.playbackSessionId &&
                    cur.liveEligible &&
                    !singleStreamOffline &&
                    applicationContext.tokenPrefs().getString(C.USER_ID, null) == session.userId
            },
            minutesLogged = minute.minutesLogged,
            secondsOffset = minute.secondsOffset,
            game = game,
            gameId = gameId,
            hidden = false,
            muted = false,
        )

        if (sendResult.errorReason == "cancelled") return

        val now = SystemClock.elapsedRealtime()
        val statusKey = channelKey(session.channelId, session.channelLogin, inMultiview = false)
        _channelStatuses.update { map ->
            val existing = map[statusKey] ?: ChannelWatchCreditStatus(
                channelId = session.channelId,
                channelLogin = session.channelLogin,
                inMultiview = false,
            )
            map + (statusKey to existing.copy(
                mode = if (sendResult.success) WatchCreditStatusMode.COUNTING else WatchCreditStatusMode.PING_FAILED,
                minutesLogged = if (sendResult.success) maxOf(existing.minutesLogged, minute.minutesLogged) else existing.minutesLogged,
                consecutiveOkPings = if (sendResult.success) existing.consecutiveOkPings + 1 else 0,
                consecutiveFailedPings = if (sendResult.success) 0 else existing.consecutiveFailedPings + 1,
                lastPingAtElapsedMs = now,
                lastSuccessAtElapsedMs = if (sendResult.success) now else existing.lastSuccessAtElapsedMs,
                lastPingSuccess = sendResult.success,
                lastPingError = sendResult.errorReason,
            ))
        }

        if (diagnosticsLogger?.isEnabled == true) {
            diagnosticsLogger.event(
                category = DiagnosticsCategory.PROGRESSION,
                transport = DiagnosticsTransport.WATCH_CREDIT,
                operation = "WatchProgress",
                event = "heartbeat_result",
                severity = if (sendResult.success) DiagnosticsSeverity.INFO else DiagnosticsSeverity.WARN,
                code = if (sendResult.success) "success" else (sendResult.errorReason ?: "heartbeat_failed"),
                fields = listOf(
                    DiagnosticsField(DiagnosticsFieldKey.LIVE, "true"),
                    DiagnosticsField(DiagnosticsFieldKey.COUNT, minute.minutesLogged.toString()),
                    DiagnosticsField(DiagnosticsFieldKey.TARGET, minute.secondsOffset.toString()),
                    DiagnosticsField(DiagnosticsFieldKey.CHANNEL_ID, session.channelId),
                    DiagnosticsField(DiagnosticsFieldKey.STREAM_ID, session.broadcastId),
                ),
            )
        }
    }

    /** Called by ChatViewModel when Hermes stream-up / stream-down arrives for the active channel. */
    fun notifySingleStreamLiveStatus(
        channelId: String?,
        channelLogin: String?,
        isLive: Boolean,
        newStreamId: String? = null,
    ) {
        val current = primaryPlaybackWatchState.state.value ?: return
        if (!matchesChannel(
                current.metadata.normalizedChannelId,
                current.metadata.channelLogin,
                channelId,
                channelLogin,
            )
        ) return
        singleStreamOffline = !isLive
        if (!isLive) {
            singleResolvedStreamId = null
        } else if (!newStreamId.isNullOrBlank()) {
            singleResolvedStreamId = newStreamId
        }
        handlePrimaryPlaybackChanged(primaryPlaybackWatchState.state.value)
    }

    // --- Multiview bridge ---

    fun syncMultiviewSession(
        streams: List<Stream>,
        activeIdentity: String?,
        focusedIdentity: String?,
        audioVolumes: Map<String, Float>,
    ) {
        _activeMultiviewStreams.value = streams
        if (streams.isEmpty()) {
            clearMultiviewState()
            return
        }

        // Preserve explicit user Drops focus if that channel is still in Multiview.
        val currentFocus = _focusedDropsChannelKey.value
        val currentFocusStillPresent = currentFocus != null && streams.any {
            matchesChannelKey(currentFocus, it.channelId, it.channelLogin)
        }
        if (!currentFocusStillPresent) {
            val defaultStream = streams.firstOrNull { stream ->
                val id = stream.channelId?.let { "id:${it.lowercase(Locale.ROOT)}" }
                val login = stream.channelLogin?.let { "login:${it.lowercase(Locale.ROOT)}" }
                (activeIdentity != null && (activeIdentity.equals(id, true) || activeIdentity.equals(login, true))) ||
                    (focusedIdentity != null && (focusedIdentity.equals(id, true) || focusedIdentity.equals(login, true)))
            } ?: streams.firstOrNull { stream ->
                val id = stream.channelId?.let { "id:${it.lowercase(Locale.ROOT)}" }
                val login = stream.channelLogin?.let { "login:${it.lowercase(Locale.ROOT)}" }
                (audioVolumes[id] ?: audioVolumes[login] ?: 0f) > 0.01f
            } ?: streams.first()
            _focusedDropsChannelKey.value = focusKey(defaultStream.channelId, defaultStream.channelLogin)
        }

        // Clean stale mv: entries for streams no longer in Multiview and update priority flags.
        val validMvKeys = streams.map { channelKey(it.channelId, it.channelLogin, inMultiview = true) }.toSet()
        _channelStatuses.update { map ->
            map.filterKeys { !it.startsWith("mv:") || it in validMvKeys }
                .mapValues { (k, v) ->
                    if (k.startsWith("mv:")) {
                        v.copy(isDropsPriority = isDropsPriorityChannel(v.channelId, v.channelLogin))
                    } else v
                }
        }
        handlePrimaryPlaybackChanged(primaryPlaybackWatchState.state.value)
    }

    fun setDropsFocusChannel(channelId: String?, channelLogin: String?) {
        val key = focusKey(channelId, channelLogin) ?: return
        _focusedDropsChannelKey.value = key
        _channelStatuses.update { map ->
            map.mapValues { (k, v) ->
                if (k.startsWith("mv:")) {
                    v.copy(isDropsPriority = isDropsPriorityChannel(v.channelId, v.channelLogin))
                } else v
            }
        }
    }

    fun isDropsPriorityChannel(channelId: String?, channelLogin: String?): Boolean {
        val mvStreams = _activeMultiviewStreams.value
        if (mvStreams.isEmpty()) return true
        val focused = _focusedDropsChannelKey.value
        if (focused != null) {
            return matchesChannelKey(focused, channelId, channelLogin)
        }
        val first = mvStreams.firstOrNull() ?: return true
        return matchesChannel(first.channelId, first.channelLogin, channelId, channelLogin)
    }

    fun updateMultiviewTileStatus(
        channelId: String?,
        channelLogin: String?,
        mode: WatchCreditStatusMode,
        isBuffering: Boolean,
        isAudioOnly: Boolean,
        isBackground: Boolean,
        isMuted: Boolean,
        nextPingAtElapsedMs: Long? = null,
        streamId: String? = null,
        gameId: String? = null,
        gameName: String? = null,
    ) {
        val key = channelKey(channelId, channelLogin, inMultiview = true)
        val priority = isDropsPriorityChannel(channelId, channelLogin)
        _channelStatuses.update { current ->
            val existing = current[key]
            val effectiveMode = if (mode == WatchCreditStatusMode.COUNTING && existing?.lastPingSuccess == false) {
                WatchCreditStatusMode.PING_FAILED
            } else if (mode == WatchCreditStatusMode.COUNTING && (existing?.consecutiveOkPings ?: 0) == 0) {
                WatchCreditStatusMode.STARTING
            } else {
                mode
            }
            val updated = (existing ?: ChannelWatchCreditStatus()).copy(
                channelId = channelId ?: existing?.channelId,
                channelLogin = channelLogin ?: existing?.channelLogin,
                mode = effectiveMode,
                isBuffering = isBuffering,
                isAudioOnly = isAudioOnly,
                isBackground = isBackground,
                isMuted = isMuted,
                inMultiview = true,
                isDropsPriority = priority,
                nextPingAtElapsedMs = nextPingAtElapsedMs ?: existing?.nextPingAtElapsedMs,
                activeStreamId = streamId ?: existing?.activeStreamId,
                gameId = gameId ?: existing?.gameId,
                gameName = gameName ?: existing?.gameName,
            )
            current + (key to updated)
        }
    }

    fun recordMultiviewHeartbeat(
        channelId: String?,
        channelLogin: String?,
        minutesLogged: Int,
        result: PlayerRepository.WatchCreditSendResult,
        nextPingAtElapsedMs: Long? = null,
    ) {
        if (result.errorReason == "cancelled") return
        val key = channelKey(channelId, channelLogin, inMultiview = true)
        val now = SystemClock.elapsedRealtime()
        val priority = isDropsPriorityChannel(channelId, channelLogin)
        _channelStatuses.update { current ->
            val existing = current[key] ?: ChannelWatchCreditStatus(
                channelId = channelId,
                channelLogin = channelLogin,
                inMultiview = true,
            )
            val updated = existing.copy(
                channelId = channelId ?: existing.channelId,
                channelLogin = channelLogin ?: existing.channelLogin,
                mode = if (result.success) WatchCreditStatusMode.COUNTING else WatchCreditStatusMode.PING_FAILED,
                inMultiview = true,
                isDropsPriority = priority,
                minutesLogged = if (result.success) maxOf(existing.minutesLogged, minutesLogged) else existing.minutesLogged,
                consecutiveOkPings = if (result.success) existing.consecutiveOkPings + 1 else 0,
                consecutiveFailedPings = if (result.success) 0 else existing.consecutiveFailedPings + 1,
                lastPingAtElapsedMs = now,
                lastSuccessAtElapsedMs = if (result.success) now else existing.lastSuccessAtElapsedMs,
                nextPingAtElapsedMs = nextPingAtElapsedMs ?: existing.nextPingAtElapsedMs,
                lastPingSuccess = result.success,
                lastPingError = result.errorReason,
            )
            current + (key to updated)
        }
    }

    fun clearMultiviewState() {
        _activeMultiviewStreams.value = emptyList()
        _focusedDropsChannelKey.value = null
        _channelStatuses.update { map -> map.filterKeys { !it.startsWith("mv:") } }
        handlePrimaryPlaybackChanged(primaryPlaybackWatchState.state.value)
    }

    // --- Account-wide Twitch drop session attribution ---

    fun recordHermesDropProgress(
        dropId: String,
        currentMinutesWatched: Int,
        requiredMinutesWatched: Int?,
        channelId: String?,
        channelLogin: String?,
    ) {
        val now = SystemClock.elapsedRealtime()
        _lastHermesDropEventAtElapsedMs.value = now
        val attributed = inferSessionChannel(channelId, channelLogin)
        _twitchDropSession.value = TwitchDropSessionSnapshot(
            dropId = dropId,
            currentMinutesWatched = currentMinutesWatched,
            requiredMinutesWatched = requiredMinutesWatched,
            channelId = attributed.first,
            channelLogin = attributed.second,
            observedAtElapsedMs = now,
        )
    }

    fun recordCurrentDropSession(
        dropId: String,
        currentMinutesWatched: Int,
        requiredMinutesWatched: Int?,
        queriedChannelId: String?,
        queriedChannelLogin: String?,
    ) {
        val now = SystemClock.elapsedRealtime()
        val attributed = inferSessionChannel(queriedChannelId, queriedChannelLogin)
        _twitchDropSession.value = TwitchDropSessionSnapshot(
            dropId = dropId,
            currentMinutesWatched = currentMinutesWatched,
            requiredMinutesWatched = requiredMinutesWatched,
            channelId = attributed.first,
            channelLogin = attributed.second,
            observedAtElapsedMs = now,
        )
    }

    fun clearTwitchDropSession() {
        _twitchDropSession.value = null
    }

    private fun inferSessionChannel(
        fallbackChannelId: String?,
        fallbackChannelLogin: String?,
    ): Pair<String?, String?> {
        val mvStreams = _activeMultiviewStreams.value
        if (mvStreams.isNotEmpty()) {
            val priorityStream = mvStreams.firstOrNull {
                isDropsPriorityChannel(it.channelId, it.channelLogin)
            }
            if (priorityStream != null) {
                return priorityStream.channelId to priorityStream.channelLogin
            }
        }
        val single = primaryPlaybackWatchState.state.value?.metadata
        if (mvStreams.isEmpty() && single != null) {
            return (single.normalizedChannelId ?: fallbackChannelId) to (single.channelLogin ?: fallbackChannelLogin)
        }
        return fallbackChannelId to fallbackChannelLogin
    }

    fun statusForChannel(channelId: String?, channelLogin: String?): ChannelWatchCreditStatus? {
        val values = _channelStatuses.value.values
        return values.firstOrNull {
            it.inMultiview && matchesChannel(it.channelId, it.channelLogin, channelId, channelLogin)
        } ?: values.firstOrNull {
            !it.inMultiview && matchesChannel(it.channelId, it.channelLogin, channelId, channelLogin)
        }
    }

    fun multiviewStreamForChannel(channelId: String?, channelLogin: String?): Stream? =
        _activeMultiviewStreams.value.firstOrNull {
            matchesChannel(it.channelId, it.channelLogin, channelId, channelLogin)
        }

    companion object {
        const val FIRST_BEAT_DELAY_MS = 6_000L
        const val MINUTE_INTERVAL_MS = 60_000L

        private fun channelKey(channelId: String?, channelLogin: String?, inMultiview: Boolean): String {
            val prefix = if (inMultiview) "mv:" else "single:"
            val id = channelId?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
            val login = channelLogin?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
            return prefix + (id?.let { "id:$it" } ?: "login:${login.orEmpty()}")
        }

        private fun focusKey(channelId: String?, channelLogin: String?): String? {
            val id = channelId?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
            if (id != null) return "id:$id"
            val login = channelLogin?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
            return login?.let { "login:$it" }
        }

        private fun matchesChannelKey(key: String, channelId: String?, channelLogin: String?): Boolean {
            if (key.startsWith("id:")) {
                val id = channelId?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
                return id != null && key == "id:$id"
            }
            if (key.startsWith("login:")) {
                val login = channelLogin?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
                return login != null && key == "login:$login"
            }
            return false
        }

        fun matchesChannel(
            firstId: String?,
            firstLogin: String?,
            secondId: String?,
            secondLogin: String?,
        ): Boolean {
            val id1 = firstId?.trim()?.takeIf(String::isNotEmpty)
            val id2 = secondId?.trim()?.takeIf(String::isNotEmpty)
            if (id1 != null && id2 != null) {
                return id1.equals(id2, ignoreCase = true)
            }
            val login1 = firstLogin?.trim()?.takeIf(String::isNotEmpty)
            val login2 = secondLogin?.trim()?.takeIf(String::isNotEmpty)
            return login1 != null && login2 != null && login1.equals(login2, ignoreCase = true)
        }
    }
}
