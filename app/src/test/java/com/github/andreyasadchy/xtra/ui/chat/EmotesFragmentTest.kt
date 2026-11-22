package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotesFragmentTest {

    @Test
    fun pendingRefreshDoesNotReplaceChangedDragOrder() {
        val pendingRefresh = listOf(
            Emote(name = "A"),
            Emote(name = "B"),
            Emote(name = "C"),
        )

        assertNull(pendingFavoriteItemsToApply(pendingRefresh, orderChanged = true))
    }

    @Test
    fun pendingRefreshAppliesWhenDragDidNotChangeOrder() {
        val pendingRefresh = listOf(Emote(name = "A"))

        assertEquals(
            pendingRefresh,
            pendingFavoriteItemsToApply(pendingRefresh, orderChanged = false),
        )
    }

    @Test
    fun favoritesEmptyStateShowsLoadingWhileV2CatalogIsLoading() {
        val favorites = listOf(FavoriteEmote("SEVENTV", "7tv-id", favoritedAt = 1L))
        val ui = favoritesEmptyStateUi(
            favoriteEmotes = favorites,
            usesV2 = true,
            pickerCatalog = ChatViewModel.PickerCatalog(
                twitch = emptyList(),
                thirdParty = emptyList(),
                twitchLoading = true,
            ),
        )

        assertEquals(R.string.loading, ui.textRes)
        assertFalse(ui.retryable)
    }

    @Test
    fun favoritesEmptyStateShowsRetryableErrorWhenFavoriteProviderFailed() {
        val favorites = listOf(FavoriteEmote("SEVENTV", "7tv-id", favoritedAt = 1L))
        val ui = favoritesEmptyStateUi(
            favoriteEmotes = favorites,
            usesV2 = true,
            pickerCatalog = ChatViewModel.PickerCatalog(
                twitch = emptyList(),
                thirdParty = emptyList(),
                twitchLoading = false,
                twitchFailed = false,
                thirdPartyFailed = true,
            ),
        )

        assertEquals(R.string.third_party_emotes_error, ui.textRes)
        assertTrue(ui.retryable)
    }

    @Test
    fun favoritesEmptyStateShowsUnavailableWhenSettledWithoutMatchingEmotes() {
        val favorites = listOf(FavoriteEmote("SEVENTV", "7tv-id", favoritedAt = 1L))
        val ui = favoritesEmptyStateUi(
            favoriteEmotes = favorites,
            usesV2 = true,
            pickerCatalog = ChatViewModel.PickerCatalog(
                twitch = emptyList(),
                thirdParty = emptyList(),
                twitchLoading = false,
                twitchFailed = false,
                thirdPartyFailed = false,
            ),
        )

        assertEquals(R.string.favorite_emotes_unavailable, ui.textRes)
        assertFalse(ui.retryable)
    }
}
