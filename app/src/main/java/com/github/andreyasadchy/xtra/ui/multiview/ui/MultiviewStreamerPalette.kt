package com.github.andreyasadchy.xtra.ui.multiview.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import coil3.BitmapImage
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal data class StreamerPalette(
    @ColorInt val primary: Int,
    @ColorInt val secondary: Int,
    @ColorInt val highlight: Int,
)

private data class BitmapColorStats(
    val primaryHue: Float,
    val primarySat: Float,
    val secondaryHue: Float,
    val secondarySat: Float,
)

internal object StreamerPaletteCache {
    private val cache = LruCache<String, StreamerPalette>(64)

    private fun cacheKey(url: String?, bannerUrl: String?): String? {
        val cleanAvatar = url?.trim()?.takeIf { it.isNotEmpty() }
        val cleanBanner = bannerUrl?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            cleanAvatar != null && cleanBanner != null -> "$cleanAvatar|$cleanBanner"
            cleanAvatar != null -> cleanAvatar
            cleanBanner != null -> "|$cleanBanner"
            else -> null
        }
    }

    fun getCached(url: String?, bannerUrl: String? = null): StreamerPalette? {
        cacheKey(url, bannerUrl)?.let(cache::get)?.let { return it }
        if (!bannerUrl.isNullOrBlank()) {
            cacheKey(url, null)?.let(cache::get)?.let { return it }
        }
        return null
    }

    fun fallback(seedKey: String): StreamerPalette {
        val baseHue = ((seedKey.hashCode() and 0x7FFFFFFF) % 360).toFloat()
        val primary = ColorUtils.HSLToColor(floatArrayOf(baseHue, 0.72f, 0.58f))
        val secondary = ColorUtils.HSLToColor(floatArrayOf((baseHue + 38f) % 360f, 0.68f, 0.52f))
        val highlight = ColorUtils.HSLToColor(floatArrayOf(baseHue, 0.82f, 0.78f))
        return StreamerPalette(primary, secondary, highlight)
    }

    private fun monochromeSteelIndigoFallback(): StreamerPalette {
        val primary = ColorUtils.HSLToColor(floatArrayOf(228f, 0.44f, 0.56f))
        val secondary = ColorUtils.HSLToColor(floatArrayOf(262f, 0.50f, 0.50f))
        val highlight = ColorUtils.HSLToColor(floatArrayOf(220f, 0.74f, 0.78f))
        return StreamerPalette(primary, secondary, highlight)
    }

    suspend fun loadOrExtract(
        context: Context,
        url: String?,
        seedKey: String,
    ): StreamerPalette = loadOrExtract(
        context = context,
        avatarUrl = url,
        bannerUrl = null,
        seedKey = seedKey,
    )

    suspend fun loadOrExtract(
        context: Context,
        avatarUrl: String?,
        bannerUrl: String?,
        seedKey: String,
    ): StreamerPalette {
        val cleanAvatar = avatarUrl?.trim()?.takeIf { it.isNotEmpty() }
        val cleanBanner = bannerUrl?.trim()?.takeIf { it.isNotEmpty() }
        val key = cacheKey(cleanAvatar, cleanBanner) ?: return fallback(seedKey)
        cache.get(key)?.let { return it }

        val appContext = context.applicationContext
        val (avatarBitmap, bannerBitmap) = coroutineScope {
            val avatarDeferred = cleanAvatar?.let { url ->
                async(Dispatchers.IO) { loadSmallBitmap(appContext, url, maxSide = 32) }
            }
            val bannerDeferred = cleanBanner?.let { url ->
                async(Dispatchers.IO) { loadSmallBitmap(appContext, url, maxSide = 48) }
            }
            avatarDeferred?.await() to bannerDeferred?.await()
        }

        if (avatarBitmap == null && bannerBitmap == null) {
            return fallback(seedKey)
        }

        val extracted = withContext(Dispatchers.Default) {
            val avatarStats = avatarBitmap?.let(::extractColorStats)
            val bannerStats = bannerBitmap?.let(::extractColorStats)
            when {
                avatarStats != null && bannerStats != null -> mixAvatarAndBanner(avatarStats, bannerStats)
                avatarStats != null -> paletteFromStats(avatarStats)
                bannerStats != null -> paletteFromStats(bannerStats)
                else -> monochromeSteelIndigoFallback()
            }
        }
        cache.put(key, extracted)
        return extracted
    }

    private suspend fun loadSmallBitmap(
        appContext: Context,
        url: String,
        maxSide: Int,
    ): Bitmap? = runCatching {
        val request = ImageRequest.Builder(appContext)
            .data(url)
            .size(maxSide, maxSide)
            .allowHardware(false)
            .build()
        val result = appContext.imageLoader.execute(request)
        val image = (result as? SuccessResult)?.image ?: return@runCatching null
        when (image) {
            is BitmapImage -> image.bitmap
            else -> drawableToSmallBitmap(image.asDrawable(appContext.resources), maxSide)
        }
    }.getOrNull()

    private fun drawableToSmallBitmap(drawable: Drawable, maxSide: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap.config != Bitmap.Config.HARDWARE) {
            return drawable.bitmap
        }
        val w = drawable.intrinsicWidth.coerceIn(1, maxSide)
        val h = drawable.intrinsicHeight.coerceIn(1, maxSide)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val diff = abs(a - b) % 360f
        return minOf(diff, 360f - diff)
    }

    private fun mixAvatarAndBanner(
        avatar: BitmapColorStats,
        banner: BitmapColorStats,
    ): StreamerPalette {
        val secondaryHue: Float
        val secondarySat: Float
        when {
            hueDistance(avatar.primaryHue, banner.primaryHue) >= 20f -> {
                secondaryHue = banner.primaryHue
                secondarySat = banner.primarySat.coerceIn(0.58f, 0.88f)
            }
            hueDistance(avatar.primaryHue, banner.secondaryHue) >= 20f -> {
                secondaryHue = banner.secondaryHue
                secondarySat = banner.secondarySat.coerceIn(0.58f, 0.88f)
            }
            else -> {
                secondaryHue = avatar.secondaryHue
                secondarySat = avatar.secondarySat
            }
        }
        val primary = ColorUtils.HSLToColor(floatArrayOf(avatar.primaryHue, avatar.primarySat, 0.58f))
        val secondary = ColorUtils.HSLToColor(floatArrayOf(secondaryHue, secondarySat, 0.52f))
        val highlight = ColorUtils.HSLToColor(
            floatArrayOf(avatar.primaryHue, (avatar.primarySat + 0.1f).coerceAtMost(0.95f), 0.78f),
        )
        return StreamerPalette(primary, secondary, highlight)
    }

    private fun paletteFromStats(stats: BitmapColorStats): StreamerPalette {
        val primary = ColorUtils.HSLToColor(floatArrayOf(stats.primaryHue, stats.primarySat, 0.58f))
        val secondary = ColorUtils.HSLToColor(floatArrayOf(stats.secondaryHue, stats.secondarySat, 0.52f))
        val highlight = ColorUtils.HSLToColor(
            floatArrayOf(stats.primaryHue, (stats.primarySat + 0.1f).coerceAtMost(0.95f), 0.78f),
        )
        return StreamerPalette(primary, secondary, highlight)
    }

    private fun extractColorStats(bitmap: Bitmap): BitmapColorStats? {
        val maxOrig = maxOf(bitmap.width, bitmap.height)
        val scaled = if (maxOrig > 28) {
            val scale = 28f / maxOrig.toFloat()
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }
        val bucketWeight = FloatArray(24)
        val bucketHueSum = FloatArray(24)
        val bucketSatSum = FloatArray(24)
        val hsl = FloatArray(3)
        var totalColorfulWeight = 0f
        var opaqueCount = 0
        var colorfulCount = 0
        for (y in 0 until scaled.height) {
            for (x in 0 until scaled.width) {
                val px = scaled.getPixel(x, y)
                if (Color.alpha(px) < 140) continue
                opaqueCount++
                ColorUtils.colorToHSL(px, hsl)
                val hue = hsl[0]
                val sat = hsl[1]
                val light = hsl[2]
                if (sat < 0.16f || light < 0.12f || light > 0.90f) continue
                colorfulCount++
                val weight = sat * (1.25f - abs(light - 0.52f) * 1.4f).coerceAtLeast(0.15f)
                val bucket = ((hue / 15f).toInt()).coerceIn(0, 23)
                bucketWeight[bucket] += weight
                bucketHueSum[bucket] += hue * weight
                bucketSatSum[bucket] += sat * weight
                totalColorfulWeight += weight
            }
        }
        if (scaled !== bitmap) scaled.recycle()
        if (opaqueCount == 0 || totalColorfulWeight <= 0.25f) return null
        if (colorfulCount.toFloat() / opaqueCount.toFloat() < 0.04f) return null

        var bestBucket = 0
        for (i in 1 until 24) {
            if (bucketWeight[i] > bucketWeight[bestBucket]) bestBucket = i
        }
        val primaryHue = if (bucketWeight[bestBucket] > 0f) {
            (bucketHueSum[bestBucket] / bucketWeight[bestBucket]).coerceIn(0f, 360f)
        } else {
            bestBucket * 15f + 7.5f
        }
        val primarySat = if (bucketWeight[bestBucket] > 0f) {
            (bucketSatSum[bestBucket] / bucketWeight[bestBucket]).coerceIn(0.62f, 0.90f)
        } else {
            0.76f
        }
        var secondBucket = -1
        for (i in 0 until 24) {
            val hueDiff = abs(i - bestBucket).let { d -> minOf(d, 24 - d) }
            if (hueDiff >= 2 && bucketWeight[i] > 0f) {
                if (secondBucket == -1 || bucketWeight[i] > bucketWeight[secondBucket]) {
                    secondBucket = i
                }
            }
        }
        val secondaryHue = if (secondBucket >= 0 && bucketWeight[secondBucket] > bucketWeight[bestBucket] * 0.22f) {
            (bucketHueSum[secondBucket] / bucketWeight[secondBucket]).coerceIn(0f, 360f)
        } else {
            (primaryHue + 38f) % 360f
        }
        val secondarySat = if (secondBucket >= 0 && bucketWeight[secondBucket] > bucketWeight[bestBucket] * 0.22f) {
            (bucketSatSum[secondBucket] / bucketWeight[secondBucket]).coerceIn(0.58f, 0.88f)
        } else {
            (primarySat * 0.92f).coerceIn(0.58f, 0.86f)
        }
        return BitmapColorStats(primaryHue, primarySat, secondaryHue, secondarySat)
    }
}
