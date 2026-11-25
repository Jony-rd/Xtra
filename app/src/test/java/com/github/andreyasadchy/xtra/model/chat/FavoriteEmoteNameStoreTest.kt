package com.github.andreyasadchy.xtra.model.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteEmoteNameStoreTest {

    @Test
    fun storageKeysHandleColonsInEmoteIds() {
        val key = FavoriteEmoteKey(EmoteProvider.SEVENTV, "abc:123")
        val encoded = serializeFavoriteEmoteStorageKey(key)

        assertEquals("SEVENTV:abc:123", encoded)
        assertEquals(key, parseFavoriteEmoteStorageKey(encoded))
    }

    @Test
    fun storageKeyRejectsMalformedEntries() {
        assertNull(parseFavoriteEmoteStorageKey("NO_SEPARATOR"))
        assertNull(parseFavoriteEmoteStorageKey("UNKNOWN:123"))
        assertNull(parseFavoriteEmoteStorageKey("SEVENTV:   "))
    }
}
