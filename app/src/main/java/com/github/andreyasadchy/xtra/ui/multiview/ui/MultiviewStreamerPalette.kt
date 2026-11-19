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
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal data class StreamerPalette(
    @ColorInt val primary: Int,
    @ColorInt val secondary: Int,
    @ColorInt val highlight: Int,
)

internal object StreamerPaletteCache {
    private val cache = LruCache<String, StreamerPalette>(64)

    fun getCached(url: String?): StreamerPalette? =
        url?.takeIf { it.isNotBlank() }?.let(cache::get)

    fun fallback(seedKey: String): StreamerPalette {
        val baseHue = ((seedKey.hashCode() and 0x7FFFFFFF) % 360).toFloat()
        val primary = ColorUtils.HSLToColor(floatArrayOf(baseHue, 0.72f, 0.58f))
        val secondary = ColorUtils.HSLToColor(floatArrayOf((baseHue + 38f) % 360f, 0.68f, 0.52f))
        val highlight = ColorUtils.HSLToColor(floatArrayOf(baseHue, 0.82f, 0.78f))
        return StreamerPalette(primary, secondary, highlight)
    }

    suspend fun loadOrExtract(
        context: Context,
        url: String?,
        seedKey: String,
    ): StreamerPalette {
        val cleanUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback(seedKey)
        cache.get(cleanUrl)?.let { return it }
        val appContext = context.applicationContext
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(appContext)
                    .data(cleanUrl)
                    .size(32, 32)
                    .allowHardware(false)
                    .build()
                val result = appContext.imageLoader.execute(request)
                val image = (result as? SuccessResult)?.image ?: return@runCatching null
                when (image) {
                    is BitmapImage -> image.bitmap
                    else -> drawableToSmallBitmap(image.asDrawable(appContext.resources))
                }
            }.getOrNull()
        } ?: return fallback(seedKey)
        val extracted = withContext(Dispatchers.Default) {
            extractFromBitmap(bitmap, seedKey)
        }
        cache.put(cleanUrl, extracted)
        return extracted
    }

    private fun drawableToSmallBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap.config != Bitmap.Config.HARDWARE) {
            return drawable.bitmap
        }
        val w = drawable.intrinsicWidth.coerceIn(1, 32)
        val h = drawable.intrinsicHeight.coerceIn(1, 32)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }

    private fun extractFromBitmap(bitmap: Bitmap, seedKey: String): StreamerPalette {
        val scaled = if (bitmap.width > 24 || bitmap.height > 24) {
            Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        } else {
            bitmap
        }
        val bucketWeight = FloatArray(24)
        val bucketHueSum = FloatArray(24)
        val bucketSatSum = FloatArray(24)
        val hsl = FloatArray(3)
        var totalColorfulWeight = 0f
        for (y in 0 until scaled.height) {
            for (x in 0 until scaled.width) {
                val px = scaled.getPixel(x, y)
                if (Color.alpha(px) < 140) continue
                ColorUtils.colorToHSL(px, hsl)
                val hue = hsl[0]
                val sat = hsl[1]
                val light = hsl[2]
                if (sat < 0.14f || light < 0.12f || light > 0.92f) continue
                val weight = sat * (1.25f - abs(light - 0.52f) * 1.4f).coerceAtLeast(0.15f)
                val bucket = ((hue / 15f).toInt()).coerceIn(0, 23)
                bucketWeight[bucket] += weight
                bucketHueSum[bucket] += hue * weight
                bucketSatSum[bucket] += sat * weight
                totalColorfulWeight += weight
            }
        }
        if (scaled !== bitmap) scaled.recycle()
        if (totalColorfulWeight <= 0.05f) {
            return fallback(seedKey)
        }
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

        val primary = ColorUtils.HSLToColor(floatArrayOf(primaryHue, primarySat, 0.58f))
        val secondary = ColorUtils.HSLToColor(floatArrayOf(secondaryHue, secondarySat, 0.52f))
        val highlight = ColorUtils.HSLToColor(floatArrayOf(primaryHue, (primarySat + 0.1f).coerceAtMost(0.95f), 0.78f))
        return StreamerPalette(primary, secondary, highlight)
    }
}
