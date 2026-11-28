package com.github.andreyasadchy.xtra.model.gql.stream

import com.github.andreyasadchy.xtra.model.gql.Error
import com.github.andreyasadchy.xtra.model.gql.PageInfo
import kotlinx.serialization.Serializable

@Serializable
class RecommendedTopStreamsResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val streams: Streams? = null,
    )

    @Serializable
    class Streams(
        val edges: List<Item?>? = null,
        val pageInfo: PageInfo? = null,
    )

    @Serializable
    class Item(
        val node: Stream? = null,
        val cursor: String? = null,
    )

    @Serializable
    class Stream(
        val id: String? = null,
        val broadcaster: Broadcaster? = null,
        val game: Game? = null,
        val createdAt: String? = null,
        val previewImageURL: String? = null,
        val viewersCount: Int? = null,
        val freeformTags: List<Tag?>? = null,
    )

    @Serializable
    class Broadcaster(
        val id: String? = null,
        val login: String? = null,
        val displayName: String? = null,
        val profileImageURL: String? = null,
        val broadcastSettings: BroadcastSettings? = null,
    )

    @Serializable
    class BroadcastSettings(
        val title: String? = null,
    )

    @Serializable
    class Game(
        val id: String? = null,
        val slug: String? = null,
        val displayName: String? = null,
    )

    @Serializable
    class Tag(
        val name: String? = null,
    )
}
