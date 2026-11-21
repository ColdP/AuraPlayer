package btm.m.auraplayer.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import btm.m.auraplayer.ui.player.findCachedArtworkMemoryEntry
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private const val COVER_RATIO_CACHE_LIMIT = 512
private const val MIN_COVER_ASPECT_RATIO = 0.35f
private const val MAX_COVER_ASPECT_RATIO = 2.85f
private val coverAspectRatioCache = LinkedHashMap<String, Float>(64, 0.75f, true)

internal fun normalizeCoverAspectRatio(width: Float, height: Float): Float? {
    if (width <= 0f || height <= 0f) return null
    val ratio = (width / height).coerceIn(MIN_COVER_ASPECT_RATIO, MAX_COVER_ASPECT_RATIO)
    return if (abs(ratio - 1f) < 0.02f) 1f else ratio
}

internal fun recordCoverAspectRatio(uri: String, width: Float, height: Float): Float? {
    if (uri.isBlank()) return null
    val normalized = normalizeCoverAspectRatio(width, height) ?: return null
    synchronized(coverAspectRatioCache) {
        coverAspectRatioCache[uri] = normalized
        while (coverAspectRatioCache.size > COVER_RATIO_CACHE_LIMIT) {
            val oldest = coverAspectRatioCache.entries.firstOrNull()?.key ?: break
            coverAspectRatioCache.remove(oldest)
        }
    }
    return normalized
}

private fun peekCoverAspectRatio(context: Context, uri: String): Float? {
    if (uri.isBlank()) return null
    synchronized(coverAspectRatioCache) {
        coverAspectRatioCache[uri]?.let { return it }
    }
    val cachedImage = findCachedArtworkMemoryEntry(SingletonImageLoader.get(context), uri)?.image
    if (cachedImage != null) {
        return recordCoverAspectRatio(uri, cachedImage.width.toFloat(), cachedImage.height.toFloat())
    }
    return null
}

private suspend fun resolveCoverAspectRatio(context: Context, uri: String): Float? {
    if (uri.isBlank()) return null
    peekCoverAspectRatio(context, uri)?.let { return it }
    val localRatio = decodeLocalBoundsRatio(context, uri)
    if (localRatio != null) {
        return recordCoverAspectRatio(uri, localRatio, 1f)
    }
    val image = runCatching {
        val result = SingletonImageLoader.get(context).execute(
            ImageRequest.Builder(context)
                .data(uri)
                .size(256)
                .allowHardware(false)
                .build(),
        )
        (result as? SuccessResult)?.image
    }.getOrNull() ?: return null
    return recordCoverAspectRatio(uri, image.width.toFloat(), image.height.toFloat())
}

private fun decodeLocalBoundsRatio(context: Context, uri: String): Float? = runCatching {
    val parsed = Uri.parse(uri)
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    when {
        parsed.scheme == "content" -> {
            context.contentResolver.openInputStream(parsed)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
        }
        parsed.scheme == "file" -> {
            parsed.path?.let { BitmapFactory.decodeFile(it, opts) }
        }
        parsed.scheme == null && File(uri).exists() -> {
            BitmapFactory.decodeFile(uri, opts)
        }
        else -> return@runCatching null
    }
    normalizeCoverAspectRatio(opts.outWidth.toFloat(), opts.outHeight.toFloat())
}.getOrNull()

@Composable
internal fun rememberCoverAspectRatio(
    uri: String,
    enabled: Boolean = LocalAdaptIrregularCovers.current,
): Float {
    if (!enabled || uri.isBlank()) return 1f
    val context = LocalContext.current
    val initial = remember(uri, enabled, context) {
        peekCoverAspectRatio(context, uri) ?: 1f
    }
    val resolved by produceState(initialValue = initial, uri, enabled, context) {
        if (!enabled || uri.isBlank()) {
            value = 1f
            return@produceState
        }
        val ratio = withContext(Dispatchers.IO) {
            resolveCoverAspectRatio(context, uri)
        }
        if (ratio != null) {
            value = ratio
        }
    }
    return resolved
}

internal fun Modifier.fitCoverAspectRatio(ratio: Float): Modifier = layout { measurable, constraints ->
    val safeRatio = ratio.coerceIn(MIN_COVER_ASPECT_RATIO, MAX_COVER_ASPECT_RATIO)
    val maxW = constraints.maxWidth
    val maxH = constraints.maxHeight
    val (targetW, targetH) = when {
        maxW == Constraints.Infinity && maxH == Constraints.Infinity -> {
            constraints.minWidth.coerceAtLeast(1) to constraints.minHeight.coerceAtLeast(1)
        }
        maxH == Constraints.Infinity -> {
            val w = maxW.coerceAtLeast(1)
            w to (w / safeRatio).roundToInt().coerceAtLeast(1)
        }
        maxW == Constraints.Infinity -> {
            val h = maxH.coerceAtLeast(1)
            (h * safeRatio).roundToInt().coerceAtLeast(1) to h
        }
        else -> {
            val wByH = (maxH * safeRatio).roundToInt()
            if (wByH <= maxW) {
                wByH.coerceAtLeast(1) to maxH.coerceAtLeast(1)
            } else {
                maxW.coerceAtLeast(1) to (maxW / safeRatio).roundToInt().coerceAtLeast(1)
            }
        }
    }
    val placeable = measurable.measure(Constraints.fixed(targetW, targetH))
    layout(targetW, targetH) {
        placeable.placeRelative(0, 0)
    }
}
