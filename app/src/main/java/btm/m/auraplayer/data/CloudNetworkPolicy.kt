package btm.m.auraplayer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.size.Dimension
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Network classification and shared HTTP clients for cloud traffic optimization. */
object NetworkTrafficPolicy {
    fun isMetered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return cm.isActiveNetworkMetered
        val caps = cm.getNetworkCapabilities(network) ?: return cm.isActiveNetworkMetered
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return true
        cm.isActiveNetworkMetered
    }.getOrDefault(false)
}

object CloudHttpClient {
    private val pool = ConnectionPool(8, 5, TimeUnit.MINUTES)

    /** Shared client for NetEase / QQ JSON endpoints (preserves followRedirects = false). */
    val api: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(pool)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    /** Shared client for CDN media streams, artwork, and downloads. */
    val media: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(pool)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

/** Normalizes NetEase / QQ cover URLs into two canonical size tiers to eliminate raw 2-8 MB cover downloads. */
object CloudImageUrl {
    const val THUMBNAIL_TIER_PX = 300
    const val DETAIL_TIER_PX = 800
    private const val THUMBNAIL_THRESHOLD_PX = 360

    fun tierForPixels(maxDimensionPx: Int): Int =
        if (maxDimensionPx in 1..THUMBNAIL_THRESHOLD_PX) THUMBNAIL_TIER_PX else DETAIL_TIER_PX

    fun resizeForTarget(rawUrl: String, maxDimensionPx: Int): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return rawUrl
        val lower = trimmed.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return rawUrl
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return rawUrl
        val host = uri.host?.lowercase().orEmpty()
        val tier = tierForPixels(maxDimensionPx)

        if (host.endsWith(".music.126.net") || host == "music.126.net") {
            val base = if (lower.startsWith("http://")) "https://" + trimmed.removePrefix("http://") else trimmed
            val parsed = runCatching { Uri.parse(base) }.getOrNull() ?: return base
            if (parsed.getQueryParameter("param") != null) return base
            // Skip if URL already carries explicit image processing query or path directives.
            if (parsed.getQueryParameter("imageView") != null) return base
            return parsed.buildUpon()
                .appendQueryParameter("param", "${tier}y${tier}")
                .build()
                .toString()
        }

        if (host == "y.qq.com" && trimmed.contains("/music/photo_new/T002R500x500M000") && tier == THUMBNAIL_TIER_PX) {
            return trimmed.replaceFirst("http://", "https://")
                .replace("/music/photo_new/T002R500x500M000", "/music/photo_new/T002R300x300M000")
        }

        return rawUrl
    }
}

/** Coil pipeline interceptor that downsizes cloud cover URLs to match the requested view dimensions. */
internal class CloudImageResizeInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data
        val raw = when (data) {
            is String -> data
            is Uri -> data.toString()
            else -> null
        } ?: return chain.proceed()

        val maxDim = maxOf(
            (chain.size.width as? Dimension.Pixels)?.px ?: 0,
            (chain.size.height as? Dimension.Pixels)?.px ?: 0,
        )
        val resized = CloudImageUrl.resizeForTarget(raw, maxDim)
        if (resized == raw) return chain.proceed()

        val nextData: Any = if (data is Uri) Uri.parse(resized) else resized
        val updated = request.newBuilder().data(nextData).build()
        return chain.withRequest(updated).proceed()
    }
}
