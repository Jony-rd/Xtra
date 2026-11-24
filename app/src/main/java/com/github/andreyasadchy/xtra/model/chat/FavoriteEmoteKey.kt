package com.github.andreyasadchy.xtra.model.chat

enum class EmoteProvider {
    TWITCH,
    SEVENTV,
    BTTV,
    FFZ,
    TWEMOJI,
}

data class FavoriteEmoteKey(
    val provider: EmoteProvider,
    val emoteId: String,
)

fun Emote.favoriteKey(): FavoriteEmoteKey? {
    val normalizedId = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val provider = when (source) {
        null -> EmoteProvider.TWITCH
        Emote.PERSONAL_STV, Emote.CHANNEL_STV, Emote.GLOBAL_STV -> EmoteProvider.SEVENTV
        Emote.CHANNEL_BTTV, Emote.GLOBAL_BTTV -> EmoteProvider.BTTV
        Emote.CHANNEL_FFZ, Emote.GLOBAL_FFZ -> EmoteProvider.FFZ
        else -> return null
    }
    return FavoriteEmoteKey(provider, normalizedId)
}

fun FavoriteEmote.key(): FavoriteEmoteKey? {
    val normalizedId = emoteId.trim().takeIf { it.isNotEmpty() } ?: return null
    val parsedProvider = runCatching { EmoteProvider.valueOf(provider) }.getOrNull() ?: return null
    return FavoriteEmoteKey(parsedProvider, normalizedId)
}

/**
 * Keeps the live emote instance, including its current name and URLs, as the
 * source of truth for Favorites.
 */
object FavoriteEmoteCatalog {

    fun deduplicate(emotes: List<Emote>): List<Emote> {
        val result = mutableListOf<Emote>()
        val indexesByKey = mutableMapOf<FavoriteEmoteKey, Int>()
        emotes.forEach { emote ->
            val key = emote.favoriteKey()
            if (key == null) {
                result += emote
            } else {
                val existingIndex = indexesByKey[key]
                if (existingIndex == null) {
                    indexesByKey[key] = result.size
                    result += emote
                } else if (scopePriority(emote) < scopePriority(result[existingIndex])) {
                    result[existingIndex] = emote
                }
            }
        }
        return result
    }

    fun availableFavorites(
        favorites: List<FavoriteEmote>,
        emotes: List<Emote>,
        namesById: Map<String, String> = emptyMap(),
    ): List<Emote> = resolveFavorites(favorites, emotes, namesById).map { (_, emote) -> emote }

    /**
     * Resolves stored favorites against the emotes the picker currently offers.
     *
     * A favorite is stored as (provider, emote id), but the picker keeps a single instance
     * per name: channel beats personal beats global. When the viewer's favorite was saved
     * from one of those instances, the stored id can belong to an instance the picker no
     * longer lists, and nothing would match it by id anymore. [namesById] maps every id the
     * current catalog knows, including the hidden scopes, to its name so such a favorite can
     * still be resolved by name instead of silently disappearing.
     */
    fun resolveFavorites(
        favorites: List<FavoriteEmote>,
        emotes: List<Emote>,
        namesById: Map<String, String> = emptyMap(),
    ): List<Pair<FavoriteEmote, Emote>> {
        if (favorites.isEmpty() || emotes.isEmpty()) return emptyList()
        val candidates = deduplicate(emotes)
        val byKey = HashMap<FavoriteEmoteKey, Emote>(candidates.size)
        val byName = HashMap<String, MutableList<Emote>>()
        candidates.forEach { emote ->
            val key = emote.favoriteKey()
            if (key != null && key !in byKey) byKey[key] = emote
            val name = emote.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            byName.getOrPut(name.lowercase()) { mutableListOf() } += emote
        }
        val resolved = ArrayList<Pair<FavoriteEmote, Emote>>(favorites.size)
        val usedEmoteKeys = HashSet<FavoriteEmoteKey>()
        favorites.forEach { favorite ->
            val key = favorite.key() ?: return@forEach
            val emote = byKey[key]
                ?: nameMatchedEmote(favorite, key, byName, namesById)
                ?: return@forEach
            val emoteKey = emote.favoriteKey() ?: return@forEach
            if (usedEmoteKeys.add(emoteKey)) resolved += favorite to emote
        }
        return resolved
    }

    /**
     * Stored favorites that refer to [emote]. Exact ids win; otherwise a third-party emote
     * is matched by name so that long-pressing the instance the picker shows toggles the
     * favorite the viewer already has instead of storing a second copy of the same emote.
     */
    fun favoritesForEmote(
        favorites: List<FavoriteEmote>,
        emote: Emote,
        namesById: Map<String, String> = emptyMap(),
    ): List<FavoriteEmote> {
        val key = emote.favoriteKey() ?: return emptyList()
        val exact = favorites.filter { favorite -> favorite.key() == key }
        if (exact.isNotEmpty()) return exact
        if (!supportsNameMatching(key.provider)) return emptyList()
        val name = emote.name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return favorites.filter { favorite ->
            val favoriteKey = favorite.key() ?: return@filter false
            favoriteKey.provider == key.provider && favoriteName(favorite, namesById) == name
        }
    }

    private fun nameMatchedEmote(
        favorite: FavoriteEmote,
        key: FavoriteEmoteKey,
        byName: Map<String, List<Emote>>,
        namesById: Map<String, String>,
    ): Emote? {
        if (!supportsNameMatching(key.provider)) return null
        val name = favoriteName(favorite, namesById) ?: return null
        return byName[name]?.firstOrNull { it.favoriteKey()?.provider == key.provider }
    }

    private fun favoriteName(favorite: FavoriteEmote, namesById: Map<String, String>): String? {
        val storedId = favorite.emoteId.trim()
        if (storedId.isEmpty()) return null
        // Older rows can store the emote name itself as the id.
        return (namesById[storedId] ?: storedId).trim().lowercase().takeIf { it.isNotEmpty() }
    }

    /** Only third-party emotes can change ids while keeping their name. */
    private fun supportsNameMatching(provider: EmoteProvider): Boolean =
        provider == EmoteProvider.SEVENTV ||
                provider == EmoteProvider.BTTV ||
                provider == EmoteProvider.FFZ

    /**
     * Reorders the currently available favorites while keeping unavailable
     * favorites in their existing global slots.
     */
    fun reorderAvailableFavorites(
        currentOrder: List<FavoriteEmoteKey>,
        availableOrder: List<FavoriteEmoteKey>,
    ): List<FavoriteEmoteKey> {
        val currentKeys = currentOrder.toSet()
        val reorderedAvailable = availableOrder
            .filter(currentKeys::contains)
            .distinct()
        if (reorderedAvailable.isEmpty()) return currentOrder

        val reorderedKeys = reorderedAvailable.toSet()
        var nextAvailableIndex = 0
        return currentOrder.map { key ->
            if (key in reorderedKeys) {
                reorderedAvailable[nextAvailableIndex++]
            } else {
                key
            }
        }
    }

    fun removeMatchingScope(
        emotes: MutableList<Emote>,
        removed: List<Emote>,
        source: Int,
    ) {
        val removedKeys = removed.mapNotNull { it.favoriteKey() }.toSet()
        val removedNames = removed.map { it.name }.toSet()
        emotes.removeAll { emote ->
            val key = emote.favoriteKey()
            emote.source == source &&
                    ((key != null && key in removedKeys) ||
                            (key == null && emote.name in removedNames))
        }
    }

    private fun scopePriority(emote: Emote): Int {
        return when (emote.source) {
            Emote.CHANNEL_STV, Emote.CHANNEL_BTTV, Emote.CHANNEL_FFZ -> 0
            Emote.PERSONAL_STV -> 1
            Emote.GLOBAL_STV, Emote.GLOBAL_BTTV, Emote.GLOBAL_FFZ -> 2
            else -> 0
        }
    }
}
