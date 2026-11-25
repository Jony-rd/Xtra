package com.github.andreyasadchy.xtra.model.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteEmoteKeyTest {

    @Test
    fun providerScopesShareOneSevenTvKey() {
        val expected = FavoriteEmoteKey(EmoteProvider.SEVENTV, "7tv-id")

        assertEquals(expected, emote(Emote.PERSONAL_STV, "7tv-id").favoriteKey())
        assertEquals(expected, emote(Emote.CHANNEL_STV, "7tv-id").favoriteKey())
        assertEquals(expected, emote(Emote.GLOBAL_STV, "7tv-id").favoriteKey())
    }

    @Test
    fun everyThirdPartyScopeMapsToItsProvider() {
        assertEquals(EmoteProvider.BTTV, emote(Emote.CHANNEL_BTTV, "bttv-id").favoriteKey()?.provider)
        assertEquals(EmoteProvider.BTTV, emote(Emote.GLOBAL_BTTV, "bttv-id").favoriteKey()?.provider)
        assertEquals(EmoteProvider.FFZ, emote(Emote.CHANNEL_FFZ, "ffz-id").favoriteKey()?.provider)
        assertEquals(EmoteProvider.FFZ, emote(Emote.GLOBAL_FFZ, "ffz-id").favoriteKey()?.provider)
    }

    @Test
    fun providersWithTheSameNameAndIdRemainIndependent() {
        val twitch = Emote(name = "OMEGALUL", id = "same")
        val ffz = emote(Emote.CHANNEL_FFZ, "same", "OMEGALUL")

        assertEquals(FavoriteEmoteKey(EmoteProvider.TWITCH, "same"), twitch.favoriteKey())
        assertEquals(FavoriteEmoteKey(EmoteProvider.FFZ, "same"), ffz.favoriteKey())
    }

    @Test
    fun renamedEmoteKeepsItsFavoriteKey() {
        val before = emote(Emote.CHANNEL_BTTV, "bttv-id", "old_name")
        val after = emote(Emote.CHANNEL_BTTV, "bttv-id", "new_name")

        assertEquals(before.favoriteKey(), after.favoriteKey())
    }

    @Test
    fun blankIdCannotBecomeANameBasedFavorite() {
        assertNull(Emote(name = "PogChamp", id = null).favoriteKey())
        assertNull(Emote(name = "PogChamp", id = "   ").favoriteKey())
    }

    @Test
    fun scopeDuplicateUsesChannelInstance() {
        val global = emote(Emote.GLOBAL_STV, "same", "global_alias")
        val personal = emote(Emote.PERSONAL_STV, "same", "personal_alias")
        val channel = emote(Emote.CHANNEL_STV, "same", "channel_alias")

        val result = FavoriteEmoteCatalog.deduplicate(listOf(global, personal, channel))

        assertEquals(1, result.size)
        assertSame(channel, result.single())
    }

    @Test
    fun removingChannelScopeKeepsGlobalFavoriteFallback() {
        val global = emote(Emote.GLOBAL_STV, "same", "global_alias")
        val channel = emote(Emote.CHANNEL_STV, "same", "channel_alias")
        val catalog = mutableListOf(global, channel)

        FavoriteEmoteCatalog.removeMatchingScope(catalog, listOf(channel), Emote.CHANNEL_STV)

        assertEquals(listOf(global), catalog)
        assertEquals(
            listOf(global),
            FavoriteEmoteCatalog.availableFavorites(
                listOf(FavoriteEmote("SEVENTV", "same", 1L)),
                catalog,
            ),
        )
    }

    @Test
    fun projectionKeepsFavoriteOrderAndHidesUnavailableRows() {
        val favorites = listOf(
            FavoriteEmote("BTTV", "b", 30),
            FavoriteEmote("SEVENTV", "s", 20),
            FavoriteEmote("FFZ", "missing", 10),
        )
        val available = listOf(
            emote(Emote.GLOBAL_STV, "s", "renamed_7tv"),
            emote(Emote.CHANNEL_BTTV, "b", "bttv"),
        )

        val result = FavoriteEmoteCatalog.availableFavorites(favorites, available)

        assertEquals(listOf("bttv", "renamed_7tv"), result.map { it.name })
    }

    @Test
    fun reorderingAvailableFavoritesKeepsUnavailableSlots() {
        val hidden = FavoriteEmoteKey(EmoteProvider.FFZ, "hidden")
        val first = FavoriteEmoteKey(EmoteProvider.TWITCH, "first")
        val second = FavoriteEmoteKey(EmoteProvider.TWITCH, "second")
        val third = FavoriteEmoteKey(EmoteProvider.TWITCH, "third")

        val result = FavoriteEmoteCatalog.reorderAvailableFavorites(
            currentOrder = listOf(first, hidden, second, third),
            availableOrder = listOf(third, first, second),
        )

        assertEquals(listOf(third, hidden, first, second), result)
    }

    @Test
    fun reorderingIgnoresStaleAvailableKeys() {
        val first = FavoriteEmoteKey(EmoteProvider.TWITCH, "first")
        val second = FavoriteEmoteKey(EmoteProvider.TWITCH, "second")
        val stale = FavoriteEmoteKey(EmoteProvider.BTTV, "stale")

        val result = FavoriteEmoteCatalog.reorderAvailableFavorites(
            currentOrder = listOf(first, second),
            availableOrder = listOf(stale, second, first),
        )

        assertEquals(listOf(second, first), result)
    }

    @Test
    fun favoriteSavedFromAHiddenScopeStillResolvesByItsName() {
        val personal = emote(Emote.PERSONAL_STV, "personal-ez", "EZ")

        val result = FavoriteEmoteCatalog.availableFavorites(
            favorites = listOf(FavoriteEmote("SEVENTV", "global-ez", 1L)),
            emotes = listOf(personal),
            namesById = mapOf("global-ez" to "EZ"),
        )

        assertEquals(listOf("personal-ez"), result.map { it.id })
    }

    @Test
    fun nameMatchingDoesNotInventUnavailableFavorites() {
        assertTrue(
            FavoriteEmoteCatalog.availableFavorites(
                favorites = listOf(FavoriteEmote("SEVENTV", "global-ez", 1L)),
                emotes = listOf(emote(Emote.PERSONAL_STV, "personal-ez", "EZ")),
            ).isEmpty(),
        )
    }

    @Test
    fun nameMatchingKeepsProvidersApart() {
        val twitch = Emote(name = "LUL", id = "twitch-lul")

        assertTrue(
            FavoriteEmoteCatalog.availableFavorites(
                favorites = listOf(FavoriteEmote("SEVENTV", "7tv-lul", 1L)),
                emotes = listOf(twitch),
                namesById = mapOf("7tv-lul" to "LUL"),
            ).isEmpty(),
        )
    }

    @Test
    fun favoriteTogglesFromTheInstanceThePickerShows() {
        val personal = emote(Emote.PERSONAL_STV, "personal-ez", "EZ")
        val favorite = FavoriteEmote("SEVENTV", "global-ez", 1L)

        assertEquals(
            listOf(favorite),
            FavoriteEmoteCatalog.favoritesForEmote(
                favorites = listOf(favorite),
                emote = personal,
                namesById = mapOf("global-ez" to "EZ"),
            ),
        )
    }


    @Test
    fun persistedNameResolvesChannelOnlyFavoriteInAnotherChannel() {
        val channelBEmote = emote(Emote.CHANNEL_STV, "channel-b-ez", "EZ")
        val favorite = FavoriteEmote("SEVENTV", "channel-a-ez", 1L)
        val persistedNames = mapOf(FavoriteEmoteKey(EmoteProvider.SEVENTV, "channel-a-ez") to "EZ")

        val result = FavoriteEmoteCatalog.availableFavorites(
            favorites = listOf(favorite),
            emotes = listOf(channelBEmote),
            namesByKey = persistedNames,
        )

        assertEquals(listOf("channel-b-ez"), result.map { it.id })
    }

    @Test
    fun thirdPartyFavoriteResolvesAcrossThirdPartyProvidersWhenChannelUsesAnotherProvider() {
        val channelSevenTv = emote(Emote.CHANNEL_STV, "7tv-catjam", "catJAM")
        val bttvFavorite = FavoriteEmote("BTTV", "bttv-catjam", 1L)
        val persistedNames = mapOf(FavoriteEmoteKey(EmoteProvider.BTTV, "bttv-catjam") to "catJAM")

        val resolved = FavoriteEmoteCatalog.availableFavorites(
            favorites = listOf(bttvFavorite),
            emotes = listOf(channelSevenTv),
            namesByKey = persistedNames,
        )

        assertEquals(listOf("7tv-catjam"), resolved.map { it.id })
        assertEquals(
            listOf(bttvFavorite),
            FavoriteEmoteCatalog.favoritesForEmote(
                favorites = listOf(bttvFavorite),
                emote = channelSevenTv,
                namesByKey = persistedNames,
            ),
        )
    }

    @Test
    fun exactCaseNameMatchWinsOverCaseInsensitiveCandidate() {
        val lower = emote(Emote.CHANNEL_STV, "lower-id", "peped")
        val mixed = emote(Emote.CHANNEL_STV, "mixed-id", "pepeD")
        val favorite = FavoriteEmote("SEVENTV", "old-mixed-id", 1L)
        val names = mapOf(FavoriteEmoteKey(EmoteProvider.SEVENTV, "old-mixed-id") to "pepeD")

        val resolved = FavoriteEmoteCatalog.availableFavorites(
            favorites = listOf(favorite),
            emotes = listOf(lower, mixed),
            namesByKey = names,
        )

        assertEquals(listOf("mixed-id"), resolved.map { it.id })
    }

    @Test
    fun resolvedFavoriteKeysIncludesMatchedPickerInstanceAndToggleRemovesDuplicates() {
        val pickerEmote = emote(Emote.CHANNEL_STV, "channel-ez", "EZ")
        val staleExact = FavoriteEmote("SEVENTV", "channel-ez", 2L)
        val oldAlias = FavoriteEmote("BTTV", "bttv-ez", 1L)
        val names = mapOf(FavoriteEmoteKey(EmoteProvider.BTTV, "bttv-ez") to "EZ")

        val keys = FavoriteEmoteCatalog.resolvedFavoriteKeys(
            favorites = listOf(oldAlias),
            emotes = listOf(pickerEmote),
            namesByKey = names,
        )
        assertTrue(FavoriteEmoteKey(EmoteProvider.SEVENTV, "channel-ez") in keys)
        assertTrue(FavoriteEmoteKey(EmoteProvider.BTTV, "bttv-ez") in keys)

        val matched = FavoriteEmoteCatalog.favoritesForEmote(
            favorites = listOf(staleExact, oldAlias),
            emote = pickerEmote,
            namesByKey = names,
        )
        assertEquals(2, matched.size)
    }

    private fun emote(source: Int, id: String, name: String = "emote") = Emote(
        source = source,
        id = id,
        name = name,
    )
}
