package com.github.andreyasadchy.xtra.repository.datasource

import com.github.andreyasadchy.xtra.graphql.type.Language
import com.github.andreyasadchy.xtra.graphql.type.StreamSort
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.gql.Error
import com.github.andreyasadchy.xtra.repository.StreamCreatedAtHydrator
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.repository.parsePersonalSections
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog
import com.github.andreyasadchy.xtra.util.C
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A page cursor carries the API that created it. Twitch cursors are not
 * interchangeable between GraphQL, persisted GraphQL, and Helix, so this
 * identity must survive the loader instance and process that created it.
 */
data class StreamFeedCursor(
    val api: String,
    val value: String,
)

data class StreamFeedPage(
    val items: List<Stream>,
    val nextCursor: StreamFeedCursor?,
)

interface StreamFeedPageLoader {
    suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage
}

private suspend fun StreamFeedPage.withHelixBroadcasterTypes(
    networkLibrary: String?,
    headers: Map<String, String>,
    helixRepository: HelixRepository,
): StreamFeedPage = copy(
    items = items.withHelixBroadcasterTypes(networkLibrary, headers, helixRepository),
)

internal fun nextStreamFeedCursor(
    api: String,
    currentCursor: String?,
    candidate: String?,
    hasNextPage: Boolean?,
): StreamFeedCursor? {
    if (hasNextPage != true) {
        return null
    }

    val next = candidate?.takeIf { it.isNotBlank() } ?: return null

    // A repeated cursor would otherwise cause an endless append loop.
    if (next == currentCursor) {
        return null
    }

    return StreamFeedCursor(
        api = api,
        value = next,
    )
}

internal fun followedStreamsFallbackSupportsSort(sort: String): Boolean {
    return sort == StreamsSortDialog.SORT_VIEWERS
}

private fun persistedStreamDataError(
    operation: String,
    errors: List<Error>?,
): IOException {
    val messages = errors.orEmpty().mapNotNull { it.message }.joinToString("; ")
    val notFound = messages.contains("PersistedQueryNotFound", ignoreCase = true)
    return IOException(
        buildString {
            append(operation)
            if (notFound) {
                append(" is unavailable (PersistedQueryNotFound)")
            } else {
                append(" returned no stream data")
            }
            if (messages.isNotEmpty()) {
                append(": ")
                append(messages)
            }
        }
    )
}

/** Select a compatible API only for the first page; subsequent cursors stay on it. */
internal suspend fun <T> loadFollowedFirstPageWithFallback(
    sort: String,
    onApiSelected: (String) -> Unit,
    gql: suspend () -> T,
    persistedGql: suspend () -> T,
    helix: suspend () -> T,
): T {
    return try {
        onApiSelected(C.GQL)
        gql()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (!followedStreamsFallbackSupportsSort(sort)) throw error
        try {
            onApiSelected(C.GQL_PERSISTED_QUERY)
            persistedGql()
        } catch (error2: Exception) {
            if (error2 is CancellationException) throw error2
            onApiSelected(C.HELIX)
            helix()
        }
    }
}

internal suspend fun <T> loadFollowedPageForCursor(
    cursor: StreamFeedCursor,
    gql: suspend (String) -> T,
    persistedGql: suspend (String) -> T,
    helix: suspend (String) -> T,
): T = when (cursor.api) {
    C.GQL -> gql(cursor.value)
    C.GQL_PERSISTED_QUERY -> persistedGql(cursor.value)
    C.HELIX -> helix(cursor.value)
    else -> throw IOException("Unknown followed-stream cursor API: ${cursor.api}")
}

class TopStreamsPageLoader(
    private val gqlQueryLanguages: List<Language>?,
    private val gqlQuerySort: StreamSort?,
    private val gqlLanguages: List<String>?,
    private val gqlSort: String?,
    private val tags: List<String>?,
    private val gqlHeaders: () -> Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val helixHeaders: () -> Map<String, String>,
    private val helixRepository: HelixRepository,
    private val networkLibrary: String?,
    private val streamCreatedAtHydrator: StreamCreatedAtHydrator,
    private val pageSize: Int = 30,
) : StreamFeedPageLoader {
    private var api: String? = null
    @Volatile
    private var browseSessionId: String = Uuid.random().toString()

    override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
        if (cursor == null) {
            api = null
            browseSessionId = Uuid.random().toString()
        }
        val page = if (cursor != null) {
            loadFromApi(cursor)
        } else if (api == null) {
            try {
                api = C.GQL
                loadFromApi(cursor)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                try {
                    api = C.GQL_PERSISTED_QUERY
                    loadFromApi(cursor)
                } catch (error2: Exception) {
                    if (error2 is CancellationException) throw error2
                    api = C.HELIX
                    loadFromApi(cursor)
                }
            }
        } else {
            loadFromApi(cursor)
        }
        return page.withHelixBroadcasterTypes(networkLibrary, helixHeaders(), helixRepository)
    }

    private suspend fun loadFromApi(cursor: StreamFeedCursor?): StreamFeedPage = when (cursor?.api ?: api) {
        C.GQL -> gqlQueryLoad(cursor?.value)
        C.GQL_PERSISTED_QUERY -> gqlLoad(cursor?.value)
        C.HELIX -> if (!helixHeaders()[C.HEADER_TOKEN].isNullOrBlank() && (gqlSort == "VIEWER_COUNT" || gqlSort == null) && tags.isNullOrEmpty() && gqlQueryLanguages.isNullOrEmpty() && gqlLanguages.isNullOrEmpty()) {
            helixLoad(cursor?.value)
        } else {
            throw IOException("Helix cannot represent this stream filter")
        }
        else -> error("No stream API selected")
    }

    private suspend fun gqlQueryLoad(cursor: String?): StreamFeedPage {
        val headers = gqlHeaders().withBrowseSessionId(browseSessionId)
        if (gqlSort == "RELEVANCE") {
            val (response, personalItems) = coroutineScope {
                val streamsDeferred = async {
                    graphQLRepository.loadRecommendedTopStreams(
                        networkLibrary = networkLibrary,
                        headers = headers,
                        sort = gqlSort,
                        tags = tags,
                        languages = gqlLanguages,
                        first = pageSize,
                        cursor = cursor,
                    )
                }
                val personalDeferred = if (shouldBlendPersonalSections(cursor, headers)) {
                    async { loadPersonalSectionStreams(headers) }
                } else {
                    null
                }
                streamsDeferred.await() to (personalDeferred?.await() ?: emptyList())
            }
            val data = response.data?.streams
                ?: throw IOException(
                    buildString {
                        append("Recommended TopStreams returned no stream data")
                        response.errors
                            ?.mapNotNull { it.message }
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { messages ->
                                append(": ")
                                append(messages.joinToString("; "))
                            }
                    }
                )
            val edges = data.edges.orEmpty()
            val directoryItems = edges.mapNotNull { edge ->
                edge?.node?.let { node ->
                    Stream(
                        id = node.id,
                        channelId = node.broadcaster?.id,
                        channelLogin = node.broadcaster?.login,
                        channelName = node.broadcaster?.displayName,
                        channelImageURL = node.broadcaster?.profileImageURL,
                        gameId = node.game?.id,
                        gameSlug = node.game?.slug,
                        gameName = node.game?.displayName,
                        title = node.broadcaster?.broadcastSettings?.title,
                        thumbnailURL = node.previewImageURL,
                        createdAt = node.createdAt,
                        viewerCount = node.viewersCount,
                        tags = node.freeformTags?.mapNotNull { tag -> tag?.name },
                    ).takeIf { it.channelId != null || it.channelLogin != null }
                }
            }
            val hydratedPersonal = if (personalItems.any { it.createdAt.isNullOrBlank() }) {
                streamCreatedAtHydrator.hydrateMissingCreatedAt(personalItems, networkLibrary, headers)
            } else {
                personalItems
            }
            return StreamFeedPage(
                items = mergeTopStreams(hydratedPersonal, directoryItems),
                nextCursor = nextStreamFeedCursor(
                    api = C.GQL,
                    currentCursor = cursor,
                    candidate = edges.lastOrNull()?.cursor,
                    hasNextPage = data.pageInfo?.hasNextPage,
                ),
            )
        }
        val response = graphQLRepository.loadQueryTopStreams(
            networkLibrary,
            headers,
            gqlQuerySort,
            tags,
            gqlQueryLanguages,
            pageSize,
            cursor,
        )
        val data = response.data?.streams
            ?: throw IOException(
                buildString {
                    append("TopStreamsQuery returned no stream data")
                    response.errors
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { errors ->
                            append(": ")
                            append(errors.joinToString("; ") { it.message })
                        }
                }
            )
        val edges = data.edges.orEmpty()
        val items = edges.mapNotNull { edge ->
            edge?.node?.let { node ->
                Stream(
                    id = node.id,
                    channelId = node.broadcaster?.id,
                    channelLogin = node.broadcaster?.login,
                    channelName = node.broadcaster?.displayName,
                    channelImageURL = node.broadcaster?.profileImageURL,
                    gameId = node.game?.id,
                    gameSlug = node.game?.slug,
                    gameName = node.game?.displayName,
                    title = node.broadcaster?.broadcastSettings?.title,
                    thumbnailURL = node.previewImageURL,
                    createdAt = node.createdAt?.toString(),
                    viewerCount = node.viewersCount,
                    tags = node.freeformTags?.mapNotNull { tag -> tag.name },
                ).takeIf { it.channelId != null || it.channelLogin != null }
            }
        }
        return StreamFeedPage(
            items = items,
            nextCursor = nextStreamFeedCursor(
                api = C.GQL,
                currentCursor = cursor,
                candidate = edges.lastOrNull()?.cursor,
                hasNextPage = data.pageInfo?.hasNextPage,
            ),
        )
    }

    private suspend fun gqlLoad(cursor: String?): StreamFeedPage {
        val headers = gqlHeaders().withBrowseSessionId(browseSessionId)
        val (response, personalItems) = coroutineScope {
            val streamsDeferred = async {
                graphQLRepository.loadTopStreams(
                    networkLibrary,
                    headers,
                    gqlSort,
                    tags,
                    gqlLanguages,
                    pageSize,
                    cursor,
                )
            }
            val personalDeferred = if (shouldBlendPersonalSections(cursor, headers)) {
                async { loadPersonalSectionStreams(headers) }
            } else {
                null
            }
            streamsDeferred.await() to (personalDeferred?.await() ?: emptyList())
        }
        val data = response.data?.streams
            ?: throw persistedStreamDataError("TopStreams persisted query", response.errors)
        val edges = data.edges
        val directoryItems = edges.mapNotNull { edge ->
            edge.node.let { node ->
                Stream(
                    id = node.id,
                    channelId = node.broadcaster?.id,
                    channelLogin = node.broadcaster?.login,
                    channelName = node.broadcaster?.displayName,
                    channelImageURL = node.broadcaster?.profileImageURL,
                    gameId = node.game?.id,
                    gameSlug = node.game?.slug,
                    gameName = node.game?.displayName,
                    title = node.title,
                    thumbnailURL = node.previewImageURL,
                    createdAt = node.createdAt,
                    viewerCount = node.viewersCount,
                    tags = node.freeformTags?.mapNotNull { tag -> tag.name },
                ).takeIf { it.channelId != null || it.channelLogin != null }
            }
        }
        val items = mergeTopStreams(personalItems, directoryItems)
        return StreamFeedPage(
            items = streamCreatedAtHydrator.hydrateMissingCreatedAt(items, networkLibrary, headers),
            nextCursor = nextStreamFeedCursor(
                api = C.GQL_PERSISTED_QUERY,
                currentCursor = cursor,
                candidate = edges.lastOrNull()?.cursor,
                hasNextPage = data.pageInfo?.hasNextPage,
            ),
        )
    }

    private fun shouldBlendPersonalSections(cursor: String?, headers: Map<String, String>): Boolean =
        cursor == null &&
            (gqlSort == null || gqlSort == "RELEVANCE") &&
            tags.isNullOrEmpty() &&
            gqlLanguages.isNullOrEmpty() &&
            !headers[C.HEADER_TOKEN].isNullOrBlank()

    private suspend fun loadPersonalSectionStreams(headers: Map<String, String>): List<Stream> {
        return try {
            parsePersonalSections(graphQLRepository.loadPersonalSections(networkLibrary, headers))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            emptyList()
        }
    }

    private fun mergeTopStreams(personal: List<Stream>, directory: List<Stream>): List<Stream> {
        if (personal.isEmpty()) return directory
        val seenChannelIds = HashSet<String>()
        val seenLogins = HashSet<String>()
        val seenStreamIds = HashSet<String>()
        val merged = ArrayList<Stream>(personal.size + directory.size)
        fun appendUnique(stream: Stream) {
            val channelId = stream.channelId?.takeIf { it.isNotBlank() }
            val login = stream.channelLogin?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val streamId = stream.id?.takeIf { it.isNotBlank() }
            if (channelId != null && channelId in seenChannelIds) return
            if (login != null && login in seenLogins) return
            if (streamId != null && streamId in seenStreamIds) return
            channelId?.let(seenChannelIds::add)
            login?.let(seenLogins::add)
            streamId?.let(seenStreamIds::add)
            merged.add(stream)
        }
        personal.forEach(::appendUnique)
        directory.forEach(::appendUnique)
        return merged
    }

    private fun Map<String, String>.withBrowseSessionId(sessionId: String): Map<String, String> {
        if (!get("Client-Integrity").isNullOrBlank()) return this
        return buildMap(size + 1) {
            putAll(this@withBrowseSessionId)
            put("Client-Session-Id", sessionId)
        }
    }

    private suspend fun helixLoad(cursor: String?): StreamFeedPage {
        val response = helixRepository.getStreams(
            networkLibrary = networkLibrary,
            headers = helixHeaders(),
            limit = pageSize,
            offset = cursor,
        )
        val users = response.data.mapNotNull { it.channelId }.let {
            helixRepository.getUsers(networkLibrary, helixHeaders(), ids = it).data.also(::rememberHelixBroadcasterTypes)
        }
        return StreamFeedPage(
            items = response.data.mapNotNull { item ->
                Stream(
                    id = item.id,
                    channelId = item.channelId,
                    channelLogin = item.channelLogin,
                    channelName = item.channelName,
                    channelImageURL = item.channelId?.let { id -> users.find { user -> user.id == id }?.profileImageURL },
                    gameId = item.gameId,
                    gameName = item.gameName,
                    title = item.title,
                    thumbnailURL = item.thumbnailURL,
                    createdAt = item.startedAt,
                    viewerCount = item.viewerCount,
                    tags = item.tags,
                ).takeIf { it.channelId != null || it.channelLogin != null }
            },
            nextCursor = response.pagination?.cursor
                ?.takeIf { it.isNotBlank() && it != cursor }
                ?.let { StreamFeedCursor(C.HELIX, it) },
        )
    }
}

class FollowedStreamsPageLoader(
    private val userId: String?,
    private val sort: String,
    private val gqlQuerySort: StreamSort,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
    private val gqlHeaders: () -> Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val helixHeaders: () -> Map<String, String>,
    private val helixRepository: HelixRepository,
    private val networkLibrary: String?,
) : StreamFeedPageLoader {
    private var api: String? = null

    private fun requireSupportedFallbackSort() {
        if (!followedStreamsFallbackSupportsSort(sort)) {
            throw IOException("Followed-stream fallback cannot represent sort: $sort")
        }
    }

    override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
        if (cursor != null) {
            // The persisted cursor is authoritative. This also works after a
            // process restart or when another loader refreshed the feed.
            return confirmCurrentLiveStreams(loadFromCursor(cursor))
                .withHelixBroadcasterTypes(networkLibrary, helixHeaders(), helixRepository)
        }
        api = null

        val hasRemoteCredentials = !gqlHeaders()[C.HEADER_TOKEN].isNullOrBlank() ||
                !helixHeaders()[C.HEADER_TOKEN].isNullOrBlank()
        val localIds = localChannelFollowsRepository.getAll().mapNotNull { it.userId }.distinct()
        val localItems = if (localIds.isEmpty()) {
            emptyList()
        } else {
            try {
                loadLocalWithFallback(localIds)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (!hasRemoteCredentials) throw error
                emptyList()
            }
        }
        val remotePage = if (hasRemoteCredentials) {
            // A followed-account request is required to be successful before
            // it can replace a prior cache. Local follows are an additive
            // fallback, not permission to destructively publish a partial feed.
            loadFirstPageWithFallback()
        } else {
            null
        }
        val merged = merge(localItems, remotePage?.items.orEmpty())
        return confirmCurrentLiveStreams(StreamFeedPage(merged, remotePage?.nextCursor))
            .withHelixBroadcasterTypes(networkLibrary, helixHeaders(), helixRepository)
    }

    /**
     * Twitch's followed-stream GraphQL result can briefly outlive a stream.
     * Helix returns only broadcasters that are live now. If that confirmation
     * request fails, keep the GraphQL snapshot because offline status is unknown.
     */
    private suspend fun confirmCurrentLiveStreams(page: StreamFeedPage): StreamFeedPage {
        val headers = helixHeaders()
        if (headers[C.HEADER_TOKEN].isNullOrBlank()) return page

        val channelIds = page.items.mapNotNull { stream ->
            stream.channelId?.takeIf { it.isNotBlank() }
        }.distinct()
        if (channelIds.isEmpty()) return page

        return try {
            val liveStreamIds = channelIds.chunked(100).flatMap { ids ->
                helixRepository.getStreams(
                    networkLibrary = networkLibrary,
                    headers = headers,
                    ids = ids,
                    limit = ids.size,
                ).data.mapNotNull { it.id }
            }.toSet()
            page.copy(items = page.items.filter { stream ->
                stream.channelId.isNullOrBlank() || stream.id?.let(liveStreamIds::contains) == true
            })
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            page
        }
    }

    private suspend fun loadFirstPageWithFallback(): StreamFeedPage {
        return loadFollowedFirstPageWithFallback(
            sort = sort,
            onApiSelected = { api = it },
            gql = { gqlQueryLoad(null) },
            persistedGql = { persistedGqlFallback(null) },
            helix = { helixFallback(null) },
        )
    }

    private suspend fun loadFromCursor(cursor: StreamFeedCursor): StreamFeedPage {
        val page = loadFollowedPageForCursor(
            cursor = cursor,
            gql = { gqlQueryLoad(it) },
            persistedGql = { persistedGqlFallback(it) },
            helix = { helixFallback(it) },
        )
        return page.copy(items = merge(emptyList(), page.items))
    }

    private suspend fun persistedGqlFallback(cursor: String?): StreamFeedPage {
        requireSupportedFallbackSort()
        return gqlLoad(cursor)
    }

    private suspend fun helixFallback(cursor: String?): StreamFeedPage {
        requireSupportedFallbackSort()
        return helixLoad(cursor)
    }

    private suspend fun loadLocalWithFallback(ids: List<String>): List<Stream> {
        if (ids.isEmpty()) return emptyList()
        return try {
            gqlQueryLocal(ids)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (helixHeaders()[C.HEADER_TOKEN].isNullOrBlank()) throw error
            helixLocal(ids)
        }
    }

    private suspend fun gqlQueryLoad(cursor: String?): StreamFeedPage {
        val response = graphQLRepository.loadQueryUserFollowedStreams(networkLibrary, gqlHeaders(), 100, cursor, gqlQuerySort)
        val data = response.data?.user?.followedLiveUsers
            ?: throw IOException(
                buildString {
                    append("FollowedStreamsQuery returned no stream data")
                    response.errors
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { errors ->
                            append(": ")
                            append(errors.joinToString("; ") { it.message })
                        }
                }
            )
        val edges = data.edges.orEmpty()
        return StreamFeedPage(
            items = edges.mapNotNull { edge ->
                edge?.node?.let { node ->
                    // `followedLiveUsers` can still contain an edge while its
                    // stream field is null. The Following Live adapter treats
                    // every row as live, so don't turn an offline user into a
                    // stream card.
                    val stream = node.stream ?: return@let null
                    Stream(
                        id = stream.id,
                        channelId = node.id,
                        channelLogin = node.login,
                        channelName = node.displayName,
                        channelImageURL = node.profileImageURL,
                        gameId = stream.game?.id,
                        gameSlug = stream.game?.slug,
                        gameName = stream.game?.displayName,
                        title = stream.broadcaster?.broadcastSettings?.title,
                        thumbnailURL = stream.previewImageURL,
                        createdAt = stream.createdAt?.toString(),
                        viewerCount = stream.viewersCount,
                        tags = stream.freeformTags?.mapNotNull { tag -> tag.name },
                    )
                }
            },
            nextCursor = nextStreamFeedCursor(
                api = C.GQL,
                currentCursor = cursor,
                candidate = edges.lastOrNull()?.cursor,
                hasNextPage = data.pageInfo?.hasNextPage,
            ),
        )
    }

    private suspend fun gqlLoad(cursor: String?): StreamFeedPage {
        val response = graphQLRepository.loadFollowedStreams(networkLibrary, gqlHeaders(), 100, cursor)
        val data = response.data?.currentUser?.followedLiveUsers
            ?: throw persistedStreamDataError("FollowedStreams persisted query", response.errors)
        val edges = data.edges
        return StreamFeedPage(
            items = edges.mapNotNull { edge ->
                edge.node.let { node ->
                    val stream = node.stream ?: return@let null
                    Stream(
                        id = stream.id,
                        channelId = node.id,
                        channelLogin = node.login,
                        channelName = node.displayName,
                        channelImageURL = node.profileImageURL,
                        gameId = stream.game?.id,
                        gameSlug = stream.game?.slug,
                        gameName = stream.game?.displayName,
                        title = stream.title,
                        thumbnailURL = stream.previewImageURL,
                        createdAt = stream.createdAt,
                        viewerCount = stream.viewersCount,
                        tags = stream.freeformTags?.mapNotNull { tag -> tag.name },
                    )
                }
            },
            nextCursor = nextStreamFeedCursor(
                api = C.GQL_PERSISTED_QUERY,
                currentCursor = cursor,
                candidate = edges.lastOrNull()?.cursor,
                hasNextPage = data.pageInfo?.hasNextPage,
            ),
        )
    }

    private suspend fun helixLoad(cursor: String?): StreamFeedPage {
        val response = helixRepository.getFollowedStreams(networkLibrary, helixHeaders(), userId, 100, cursor)
        val users = response.data.mapNotNull { it.channelId }.let {
            helixRepository.getUsers(networkLibrary, helixHeaders(), ids = it).data.also(::rememberHelixBroadcasterTypes)
        }
        return StreamFeedPage(
            items = response.data.map { item ->
                Stream(
                    id = item.id,
                    channelId = item.channelId,
                    channelLogin = item.channelLogin,
                    channelName = item.channelName,
                    channelImageURL = item.channelId?.let { id -> users.find { user -> user.id == id }?.profileImageURL },
                    gameId = item.gameId,
                    gameName = item.gameName,
                    title = item.title,
                    thumbnailURL = item.thumbnailURL,
                    createdAt = item.startedAt,
                    viewerCount = item.viewerCount,
                    tags = item.tags,
                )
            },
            nextCursor = response.pagination?.cursor
                ?.takeIf { it.isNotBlank() && it != cursor }
                ?.let { StreamFeedCursor(C.HELIX, it) },
        )
    }

    private suspend fun gqlQueryLocal(ids: List<String>): List<Stream> {
        val items = ids.chunked(100).map { chunk ->
            val response = graphQLRepository.loadQueryUsersStream(networkLibrary, gqlHeaders(), chunk)
            response.data!!.users!!
        }.flatMap { it }
        return items.mapNotNull { item ->
            item?.takeIf { it.stream?.id != null && it.stream.viewersCount != null }?.let {
                Stream(
                    id = it.stream?.id,
                    channelId = it.id,
                    channelLogin = it.login,
                    channelName = it.displayName,
                    channelImageURL = it.profileImageURL,
                    gameId = it.stream?.game?.id,
                    gameSlug = it.stream?.game?.slug,
                    gameName = it.stream?.game?.displayName,
                    title = it.stream?.broadcaster?.broadcastSettings?.title,
                    thumbnailURL = it.stream?.previewImageURL,
                    createdAt = it.stream?.createdAt?.toString(),
                    viewerCount = it.stream?.viewersCount,
                    tags = it.stream?.freeformTags?.mapNotNull { tag -> tag.name },
                )
            }
        }
    }

    private suspend fun helixLocal(ids: List<String>): List<Stream> {
        val items = ids.chunked(100).map {
            helixRepository.getStreams(networkLibrary, helixHeaders(), ids = it).data
        }.flatMap { it }
        val users = items.mapNotNull { it.channelId }.chunked(100).map {
            helixRepository.getUsers(networkLibrary, helixHeaders(), ids = it).data.also(::rememberHelixBroadcasterTypes)
        }.flatMap { it }
        return items.mapNotNull { item ->
            item.takeIf { it.viewerCount != null }?.let {
                Stream(
                    id = it.id,
                    channelId = it.channelId,
                    channelLogin = it.channelLogin,
                    channelName = it.channelName,
                    channelImageURL = it.channelId?.let { id -> users.find { user -> user.id == id }?.profileImageURL },
                    gameId = it.gameId,
                    gameName = it.gameName,
                    title = it.title,
                    thumbnailURL = it.thumbnailURL,
                    createdAt = it.startedAt,
                    viewerCount = it.viewerCount,
                    tags = it.tags,
                )
            }
        }
    }

    private fun merge(local: List<Stream>, remote: List<Stream>): List<Stream> {
        val result = remote.toMutableList()
        local.forEach { stream ->
            val existing = result.indexOfFirst { sameChannel(it, stream) }
            if (existing < 0) result += stream
        }
        return when (sort) {
            StreamsSortDialog.SORT_VIEWERS_ASC -> result.sortedBy { it.viewerCount ?: Int.MAX_VALUE }
            StreamsSortDialog.SORT_VIEWERS -> result.sortedByDescending { it.viewerCount ?: -1 }
            StreamsSortDialog.RECENT -> result.sortedByDescending {
                it.createdAt?.let { createdAt -> Instant.parseOrNull(createdAt)?.toEpochMilliseconds() }
                    ?: Long.MIN_VALUE
            }
            else -> result
        }
    }

    private fun sameChannel(first: Stream, second: Stream): Boolean {
        return when {
            !first.channelId.isNullOrBlank() && !second.channelId.isNullOrBlank() -> first.channelId == second.channelId
            !first.channelLogin.isNullOrBlank() && !second.channelLogin.isNullOrBlank() -> first.channelLogin.equals(second.channelLogin, true)
            else -> first.id != null && first.id == second.id
        }
    }
}

class GameStreamsPageLoader(
    private val gameId: String?,
    private val gameSlug: String?,
    private val gameName: String?,
    private val gqlQueryLanguages: List<Language>?,
    private val gqlQuerySort: StreamSort?,
    private val gqlLanguages: List<String>?,
    private val gqlSort: String?,
    private val tags: List<String>?,
    private val gqlHeaders: () -> Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val helixHeaders: () -> Map<String, String>,
    private val helixRepository: HelixRepository,
    private val networkLibrary: String?,
    private val pageSize: Int = 30,
) : StreamFeedPageLoader {
    private var api: String? = null

    override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
        if (cursor == null) api = null
        val page = if (cursor != null) {
            loadFromApi(cursor)
        } else if (api == null) {
            try {
                api = C.GQL
                loadFromApi(cursor)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                try {
                    api = C.GQL_PERSISTED_QUERY
                    loadFromApi(cursor)
                } catch (error2: Exception) {
                    if (error2 is CancellationException) throw error2
                    api = C.HELIX
                    loadFromApi(cursor)
                }
            }
        } else {
            loadFromApi(cursor)
        }
        return page.withHelixBroadcasterTypes(networkLibrary, helixHeaders(), helixRepository)
    }

    private suspend fun loadFromApi(cursor: StreamFeedCursor?): StreamFeedPage = when (cursor?.api ?: api) {
        C.GQL -> if (gameId != null || gameSlug != null || gameName != null) gqlQueryLoad(cursor?.value) else throw IOException("Missing game identity")
        C.GQL_PERSISTED_QUERY -> gqlLoad(cursor?.value)
        C.HELIX -> if (!helixHeaders()[C.HEADER_TOKEN].isNullOrBlank() && (gqlSort == "VIEWER_COUNT" || gqlSort == null) && tags.isNullOrEmpty() && gqlQueryLanguages.isNullOrEmpty() && gqlLanguages.isNullOrEmpty()) {
            helixLoad(cursor?.value)
        } else {
            throw IOException("Helix cannot represent this game stream filter")
        }
        else -> error("No stream API selected")
    }

    private suspend fun gqlQueryLoad(cursor: String?): StreamFeedPage {
        val response = graphQLRepository.loadQueryGameStreams(
            networkLibrary = networkLibrary,
            headers = gqlHeaders(),
            id = gameId,
            slug = gameSlug.takeIf { gameId.isNullOrBlank() },
            name = gameName.takeIf { gameId.isNullOrBlank() && gameSlug.isNullOrBlank() },
            sort = gqlQuerySort,
            tags = tags,
            languages = gqlQueryLanguages,
            first = pageSize,
            after = cursor,
        )
        val data = response.data?.game?.streams
            ?: throw IOException(
                buildString {
                    append("GameStreamsQuery returned no stream data")
                    response.errors
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { errors ->
                            append(": ")
                            append(errors.joinToString("; ") { it.message })
                        }
                }
            )
        val edges = data.edges.orEmpty()
        return StreamFeedPage(
            items = edges.mapNotNull { edge ->
                edge?.node?.let { node ->
                    Stream(
                        id = node.id,
                        channelId = node.broadcaster?.id,
                        channelLogin = node.broadcaster?.login,
                        channelName = node.broadcaster?.displayName,
                        channelImageURL = node.broadcaster?.profileImageURL,
                        gameId = gameId,
                        gameSlug = gameSlug,
                        gameName = gameName,
                        title = node.broadcaster?.broadcastSettings?.title,
                        thumbnailURL = node.previewImageURL,
                        createdAt = node.createdAt?.toString(),
                        viewerCount = node.viewersCount,
                        tags = node.freeformTags?.mapNotNull { tag -> tag.name },
                    ).takeIf { it.channelId != null || it.channelLogin != null }
                }
            },
            nextCursor = nextStreamFeedCursor(
                api = C.GQL,
                currentCursor = cursor,
                candidate = edges.lastOrNull()?.cursor,
                hasNextPage = data.pageInfo?.hasNextPage,
            ),
        )
    }

    private suspend fun gqlLoad(cursor: String?): StreamFeedPage {
        val response = graphQLRepository.loadGameStreams(networkLibrary, gqlHeaders(), gameSlug, gqlSort, tags, gqlLanguages, pageSize, cursor)
        val data = response.data?.game?.streams
            ?: throw persistedStreamDataError("GameStreams persisted query", response.errors)
        val edges = data.edges
        return StreamFeedPage(
            items = edges.mapNotNull { edge ->
                edge.node.let { node ->
                    Stream(
                        id = node.id,
                        channelId = node.broadcaster?.id,
                        channelLogin = node.broadcaster?.login,
                        channelName = node.broadcaster?.displayName,
                        channelImageURL = node.broadcaster?.profileImageURL,
                        gameId = gameId,
                        gameSlug = gameSlug,
                        gameName = gameName,
                        title = node.title,
                        thumbnailURL = node.previewImageURL,
                        createdAt = node.createdAt,
                        viewerCount = node.viewersCount,
                        tags = node.freeformTags?.mapNotNull { tag -> tag.name },
                    ).takeIf { it.channelId != null || it.channelLogin != null }
                }
            },
            nextCursor = nextStreamFeedCursor(
                api = C.GQL_PERSISTED_QUERY,
                currentCursor = cursor,
                candidate = edges.lastOrNull()?.cursor,
                hasNextPage = data.pageInfo?.hasNextPage,
            ),
        )
    }

    private suspend fun helixLoad(cursor: String?): StreamFeedPage {
        val response = helixRepository.getStreams(
            networkLibrary,
            helixHeaders(),
            gameId = gameId,
            limit = pageSize,
            offset = cursor,
        )
        val users = response.data.mapNotNull { it.channelId }.let {
            helixRepository.getUsers(networkLibrary, helixHeaders(), ids = it).data.also(::rememberHelixBroadcasterTypes)
        }
        return StreamFeedPage(
            items = response.data.mapNotNull { item ->
                Stream(
                    id = item.id,
                    channelId = item.channelId,
                    channelLogin = item.channelLogin,
                    channelName = item.channelName,
                    channelImageURL = item.channelId?.let { id -> users.find { user -> user.id == id }?.profileImageURL },
                    gameId = gameId,
                    gameSlug = gameSlug,
                    gameName = gameName,
                    title = item.title,
                    thumbnailURL = item.thumbnailURL,
                    createdAt = item.startedAt,
                    viewerCount = item.viewerCount,
                    tags = item.tags,
                ).takeIf { it.channelId != null || it.channelLogin != null }
            },
            nextCursor = response.pagination?.cursor
                ?.takeIf { it.isNotBlank() && it != cursor }
                ?.let { StreamFeedCursor(C.HELIX, it) },
        )
    }
}
