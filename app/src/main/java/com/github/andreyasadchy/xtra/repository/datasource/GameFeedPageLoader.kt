package com.github.andreyasadchy.xtra.repository.datasource

import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.uuid.Uuid

data class GameFeedCursor(
    val api: String,
    val value: String,
)

data class GameFeedPage(
    val items: List<Game>,
    val nextCursor: GameFeedCursor?,
)

interface GameFeedPageLoader {
    suspend fun load(cursor: GameFeedCursor?): GameFeedPage
}

/** Loads category pages while keeping the existing GQL, persisted GQL, Helix fallback order. */
class TwitchGameFeedPageLoader(
    private val tags: List<String>?,
    private val gqlHeaders: () -> Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val helixHeaders: () -> Map<String, String>,
    private val helixRepository: HelixRepository,
    private val networkLibrary: String?,
    private val pageSize: Int = 30,
) : GameFeedPageLoader {
    private var api: String? = null
    @Volatile
    private var browseSessionId: String = Uuid.random().toString()

    override suspend fun load(cursor: GameFeedCursor?): GameFeedPage {
        if (cursor != null) return loadFromApi(cursor)
        api = null
        browseSessionId = Uuid.random().toString()
        return try {
            api = C.GQL
            loadFromApi(null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            try {
                api = C.GQL_PERSISTED_QUERY
                loadFromApi(null)
            } catch (persistedError: Exception) {
                if (persistedError is CancellationException) throw persistedError
                api = C.HELIX
                loadFromApi(null)
            }
        }
    }

    private suspend fun loadFromApi(cursor: GameFeedCursor?): GameFeedPage {
        return when (cursor?.api ?: api) {
            C.GQL -> gqlQueryLoad(cursor?.value)
            C.GQL_PERSISTED_QUERY -> gqlLoad(cursor?.value)
            C.HELIX -> if (!helixHeaders()[C.HEADER_TOKEN].isNullOrBlank() && tags.isNullOrEmpty()) {
                helixLoad(cursor?.value)
            } else {
                throw IOException("Helix cannot represent this category filter")
            }
            else -> error("No game feed API selected")
        }
    }

    private suspend fun gqlQueryLoad(cursor: String?): GameFeedPage {
        val response = graphQLRepository.loadRecommendedTopGames(
            networkLibrary = networkLibrary,
            headers = gqlHeaders().withBrowseSessionId(browseSessionId),
            sort = "RELEVANCE",
            tags = tags,
            first = pageSize,
            cursor = cursor,
        )
        val data = response.data?.games ?: throw IOException(
            buildString {
                append("Top games response did not contain games")
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
        return GameFeedPage(
            items = edges.mapNotNull { edge ->
                edge?.node?.let { game ->
                    Game(
                        id = game.id,
                        slug = game.slug,
                        name = game.displayName,
                        boxArtURL = game.boxArtURL,
                        viewerCount = game.viewersCount,
                        broadcasterCount = game.broadcastersCount,
                        tags = game.tags?.mapNotNull { tag ->
                            tag?.let { Tag(it.id, it.localizedName) }
                        },
                    )
                }
            },
            nextCursor = edges.lastOrNull()?.cursor
                ?.takeIf { it.isNotBlank() && data.pageInfo?.hasNextPage != false }
                ?.let { GameFeedCursor(C.GQL, it) },
        )
    }

    private suspend fun gqlLoad(cursor: String?): GameFeedPage {
        val response = graphQLRepository.loadTopGames(
            networkLibrary = networkLibrary,
            headers = gqlHeaders().withBrowseSessionId(browseSessionId),
            tags = tags,
            limit = pageSize,
            cursor = cursor,
        )
        val data = response.data?.directoriesWithTags ?: error("Top games response did not contain directories")
        val edges = data.edges
        return GameFeedPage(
            items = edges.map { edge ->
                edge.node.let { game ->
                    Game(
                        id = game.id,
                        slug = game.slug,
                        name = game.displayName,
                        boxArtURL = game.avatarURL,
                        viewerCount = game.viewersCount,
                        tags = game.tags?.map { tag -> Tag(tag.id, tag.localizedName) },
                    )
                }
            },
            nextCursor = edges.lastOrNull()?.cursor
                ?.takeIf { it.isNotBlank() && data.pageInfo?.hasNextPage != false }
                ?.let { GameFeedCursor(C.GQL_PERSISTED_QUERY, it) },
        )
    }

    private suspend fun helixLoad(cursor: String?): GameFeedPage {
        val response = helixRepository.getTopGames(
            networkLibrary = networkLibrary,
            headers = helixHeaders(),
            limit = pageSize,
            offset = cursor,
        )
        return GameFeedPage(
            items = response.data.map { game ->
                Game(id = game.id, name = game.name, boxArtURL = game.boxArtURL)
            },
            nextCursor = response.pagination?.cursor?.takeIf { it.isNotBlank() }?.let { GameFeedCursor(C.HELIX, it) },
        )
    }

    private fun Map<String, String>.withBrowseSessionId(sessionId: String): Map<String, String> {
        if (!get("Client-Integrity").isNullOrBlank()) return this
        return buildMap(size + 1) {
            putAll(this@withBrowseSessionId)
            put("Client-Session-Id", sessionId)
        }
    }
}
