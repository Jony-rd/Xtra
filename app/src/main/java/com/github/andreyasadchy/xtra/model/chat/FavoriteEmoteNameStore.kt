package com.github.andreyasadchy.xtra.model.chat

import android.content.SharedPreferences
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.util.C
import org.json.JSONObject

internal fun parseFavoriteEmoteNamesJson(raw: String?): Map<FavoriteEmoteKey, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        val json = JSONObject(raw)
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val serializedKey = keys.next()
                val key = parseFavoriteEmoteStorageKey(serializedKey) ?: continue
                val name = json.optString(serializedKey).trim()
                if (name.isNotEmpty()) {
                    put(key, name)
                }
            }
        }
    }.getOrDefault(emptyMap())
}

internal fun encodeFavoriteEmoteNamesJson(names: Map<FavoriteEmoteKey, String>): String {
    val json = JSONObject()
    names.entries
        .sortedBy { serializeFavoriteEmoteStorageKey(it.key) }
        .forEach { (key, rawName) ->
            val name = rawName.trim()
            if (name.isNotEmpty()) {
                json.put(serializeFavoriteEmoteStorageKey(key), name)
            }
        }
    return json.toString()
}

internal fun serializeFavoriteEmoteStorageKey(key: FavoriteEmoteKey): String =
    "${key.provider.name}:${key.emoteId}"

internal fun parseFavoriteEmoteStorageKey(raw: String): FavoriteEmoteKey? {
    val separator = raw.indexOf(':')
    if (separator <= 0 || separator >= raw.lastIndex) return null
    val provider = runCatching {
        EmoteProvider.valueOf(raw.substring(0, separator))
    }.getOrNull() ?: return null
    val emoteId = raw.substring(separator + 1).trim().takeIf { it.isNotEmpty() } ?: return null
    return FavoriteEmoteKey(provider, emoteId)
}

/**
 * Persists the emote name for each favorited (provider, emoteId) in SharedPreferences so
 * favorites keep resolving across channels and offline emote ID swaps even when the originally
 * favorited ID is absent from the current channel's catalog.
 */
internal class FavoriteEmoteNameStore(
    private val prefs: SharedPreferences,
    private val prefKey: String = C.CHAT_FAVORITE_EMOTE_NAMES,
) {
    private val lock = Any()
    private val names = LinkedHashMap<FavoriteEmoteKey, String>().apply {
        putAll(parseFavoriteEmoteNamesJson(prefs.getString(prefKey, null)))
    }

    fun snapshot(): Map<FavoriteEmoteKey, String> = synchronized(lock) {
        if (names.isEmpty()) emptyMap() else LinkedHashMap(names)
    }

    fun nameFor(key: FavoriteEmoteKey): String? = synchronized(lock) {
        names[key]
    }

    fun remember(key: FavoriteEmoteKey, name: String?): Boolean {
        val normalizedName = name?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return rememberAll(mapOf(key to normalizedName))
    }

    fun rememberAll(entries: Map<FavoriteEmoteKey, String>): Boolean {
        if (entries.isEmpty()) return false
        synchronized(lock) {
            var changed = false
            entries.forEach { (key, rawName) ->
                val normalizedName = rawName.trim()
                if (normalizedName.isEmpty()) return@forEach
                if (names[key] != normalizedName) {
                    names[key] = normalizedName
                    changed = true
                }
            }
            if (!changed) return false
            persistLocked()
            return true
        }
    }

    fun migrate(
        oldKey: FavoriteEmoteKey,
        newKey: FavoriteEmoteKey,
        fallbackName: String? = null,
    ): Boolean = synchronized(lock) {
        val preservedName = names[oldKey]
            ?: fallbackName?.trim()?.takeIf { it.isNotEmpty() }
        var changed = names.remove(oldKey) != null
        if (preservedName != null && names[newKey] != preservedName) {
            names[newKey] = preservedName
            changed = true
        }
        if (!changed) return false
        persistLocked()
        return true
    }

    fun removeAll(keys: Collection<FavoriteEmoteKey>): Boolean {
        if (keys.isEmpty()) return false
        synchronized(lock) {
            var changed = false
            keys.forEach { key ->
                if (names.remove(key) != null) changed = true
            }
            if (!changed) return false
            persistLocked()
            return true
        }
    }

    fun pruneTo(validKeys: Set<FavoriteEmoteKey>): Boolean = synchronized(lock) {
        if (names.isEmpty()) return false
        val iterator = names.keys.iterator()
        var changed = false
        while (iterator.hasNext()) {
            if (iterator.next() !in validKeys) {
                iterator.remove()
                changed = true
            }
        }
        if (!changed) return false
        persistLocked()
        return true
    }

    private fun persistLocked() {
        val encoded = encodeFavoriteEmoteNamesJson(names)
        prefs.edit {
            if (names.isEmpty()) {
                remove(prefKey)
            } else {
                putString(prefKey, encoded)
            }
        }
    }
}
