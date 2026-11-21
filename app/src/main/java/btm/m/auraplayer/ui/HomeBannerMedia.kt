// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.TextureView
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import btm.m.auraplayer.data.BannerMotionPlayMode
import btm.m.auraplayer.data.HomeBackgroundStyle
import btm.m.auraplayer.data.LocalBannerStyleConfig
import btm.m.auraplayer.data.MotionMediaKind
import btm.m.auraplayer.data.MotionPhotoInfo
import btm.m.auraplayer.data.MotionPhotoSupport
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.math.max

@Composable
internal fun HomeBannerBackdrop(
    bannerUri: String,
    motionPlayMode: BannerMotionPlayMode = BannerMotionPlayMode.LOOP,
    replayNonce: Int = 0,
    modifier: Modifier = Modifier,
) {
    if (bannerUri.isBlank()) {
        Box(
            modifier.background(
                Brush.linearGradient(
                    listOf(
                        MiuixTheme.colorScheme.primaryContainer,
                        MiuixTheme.colorScheme.tertiaryContainer,
                        MiuixTheme.colorScheme.surfaceContainerHigh,
                    ),
                ),
            ),
        )
        return
    }

    val context = LocalContext.current
    val motionInfo by produceState(MotionPhotoInfo(), bannerUri) {
        value = MotionPhotoSupport.inspectAsync(bannerUri)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val stillRequest = remember(bannerUri, context) {
        ImageRequest.Builder(context)
            .data(bannerUri)
            .size(1600)
            .build()
    }

    Box(modifier) {
        AsyncImage(
            model = stillRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        if (motionPlayMode != BannerMotionPlayMode.OFF && motionInfo.isDynamic) {
            when (motionInfo.kind) {
                MotionMediaKind.EMBEDDED_VIDEO -> {
                    val videoFile by produceState<File?>(null, bannerUri, motionInfo.videoStartOffset, motionInfo.videoLength) {
                        value = MotionPhotoSupport.resolveOrExtractVideoFile(context, bannerUri)
                    }
                    val resolvedVideo = videoFile
                    if (resolvedVideo != null) {
                        if (motionPlayMode == BannerMotionPlayMode.REVERSE) {
                            BannerReverseFramePlayer(
                                videoFile = resolvedVideo,
                                isResumed = isResumed,
                                replayNonce = replayNonce,
                                modifier = Modifier.matchParentSize(),
                            )
                        } else {
                            BannerExoMotionVideo(
                                videoPath = resolvedVideo.absolutePath,
                                playMode = motionPlayMode,
                                isResumed = isResumed,
                                replayNonce = replayNonce,
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                    }
                }
                MotionMediaKind.ANIMATED_RASTER -> {
                    BannerAnimatedRasterLayer(
                        bannerUri = bannerUri,
                        playMode = motionPlayMode,
                        isResumed = isResumed,
                        replayNonce = replayNonce,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                MotionMediaKind.STATIC -> Unit
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun BannerExoMotionVideo(
    videoPath: String,
    playMode: BannerMotionPlayMode,
    isResumed: Boolean,
    replayNonce: Int = 0,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var firstFrameRendered by remember(videoPath, playMode) { mutableStateOf(false) }
    var finishedOnce by remember(videoPath, playMode) { mutableStateOf(false) }
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
    }

    fun updateCenterCropTransform(view: TextureView?, vw: Int, vh: Int) {
        if (view == null || vw <= 0 || vh <= 0 || view.width <= 0 || view.height <= 0) return
        val viewW = view.width.toFloat()
        val viewH = view.height.toFloat()
        val scale = max(viewW / vw.toFloat(), viewH / vh.toFloat())
        val matrix = Matrix().apply {
            setScale((vw * scale) / viewW, (vh * scale) / viewH, viewW * 0.5f, viewH * 0.5f)
        }
        view.setTransform(matrix)
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    finishedOnce = true
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoWidth = videoSize.width
                videoHeight = videoSize.height
                updateCenterCropTransform(textureViewRef, videoSize.width, videoSize.height)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.setVideoTextureView(null)
            exoPlayer.release()
        }
    }

    LaunchedEffect(videoPath, playMode) {
        firstFrameRendered = false
        finishedOnce = false
        exoPlayer.repeatMode = if (playMode == BannerMotionPlayMode.ONCE) {
            Player.REPEAT_MODE_OFF
        } else {
            Player.REPEAT_MODE_ONE
        }
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(videoPath))))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = isResumed
    }

    LaunchedEffect(isResumed, finishedOnce, playMode) {
        exoPlayer.playWhenReady = isResumed && !(playMode == BannerMotionPlayMode.ONCE && finishedOnce)
    }

    LaunchedEffect(replayNonce) {
        if (replayNonce > 0) {
            finishedOnce = false
            exoPlayer.seekTo(0L)
            exoPlayer.playWhenReady = isResumed
            if (isResumed) exoPlayer.play()
        }
    }

    val visibleAlpha by animateFloatAsState(
        targetValue = if (firstFrameRendered && !(playMode == BannerMotionPlayMode.ONCE && finishedOnce)) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "bannerMotionVideoAlpha",
    )

    Box(modifier.graphicsLayer { alpha = visibleAlpha }) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    alpha = visibleAlpha
                    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        updateCenterCropTransform(v as TextureView, videoWidth, videoHeight)
                    }
                    textureViewRef = this
                    exoPlayer.setVideoTextureView(this)
                }
            },
            update = { view ->
                textureViewRef = view
                view.alpha = visibleAlpha
                updateCenterCropTransform(view, videoWidth, videoHeight)
            },
        )
    }
}

private class ReverseFramePack(
    val ptsMs: LongArray,
    val jpegs: Array<ByteArray>,
)

@Composable
private fun BannerReverseFramePlayer(
    videoFile: File,
    isResumed: Boolean,
    replayNonce: Int = 0,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pack by produceState<ReverseFramePack?>(null, videoFile.absolutePath, videoFile.length()) {
        value = withContext(Dispatchers.IO) {
            val packFile = MotionPhotoSupport.resolveOrBuildReversePack(context, videoFile) ?: return@withContext null
            readReversePack(packFile)
        }
    }

    var currentFrame by remember(videoFile.absolutePath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(pack, isResumed, replayNonce) {
        val loaded = pack ?: return@LaunchedEffect
        if (loaded.jpegs.isEmpty()) return@LaunchedEffect
        val slots = arrayOfNulls<Bitmap>(2)
        var slotIdx = 0
        var idx = loaded.jpegs.lastIndex
        while (isActive && isResumed) {
            val bytes = loaded.jpegs[idx]
            val targetReusable = slots[slotIdx]
            val decoded = withContext(Dispatchers.Default) {
                decodeFrameReusable(bytes, targetReusable)
            }
            if (decoded != null) {
                slots[slotIdx] = decoded
                currentFrame = decoded.asImageBitmap()
                slotIdx = 1 - slotIdx
            }
            val nextIdx = if (idx > 0) idx - 1 else loaded.jpegs.lastIndex
            val frameDelay = if (idx > 0) {
                (loaded.ptsMs[idx] - loaded.ptsMs[idx - 1]).coerceIn(20L, 120L)
            } else {
                42L
            }
            idx = nextIdx
            delay(frameDelay)
        }
    }

    val frameAlpha by animateFloatAsState(
        targetValue = if (currentFrame != null) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "bannerReverseFrameAlpha",
    )

    val bitmap = currentFrame
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.graphicsLayer { alpha = frameAlpha },
        )
    }
}

@Composable
private fun BannerAnimatedRasterLayer(
    bannerUri: String,
    playMode: BannerMotionPlayMode,
    isResumed: Boolean,
    replayNonce: Int = 0,
    modifier: Modifier = Modifier,
) {
    val drawable by produceState<Drawable?>(null, bannerUri, playMode) {
        value = withContext(Dispatchers.IO) {
            val file = MotionPhotoSupport.resolveLocalFile(bannerUri) ?: return@withContext null
            runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) }.getOrNull()
        }
    }
    var finishedOnce by remember(bannerUri, playMode) { mutableStateOf(false) }

    LaunchedEffect(replayNonce, drawable) {
        if (replayNonce > 0) {
            finishedOnce = false
            val anim = drawable as? AnimatedImageDrawable ?: return@LaunchedEffect
            anim.stop()
            if (isResumed) anim.start()
        }
    }

    val visibleAlpha by animateFloatAsState(
        targetValue = if (drawable != null && !(playMode == BannerMotionPlayMode.ONCE && finishedOnce)) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "bannerAnimatedRasterAlpha",
    )

    val loaded = drawable ?: return
    DisposableEffect(loaded, playMode) {
        val anim = loaded as? AnimatedImageDrawable
        var cb: Animatable2.AnimationCallback? = null
        if (anim != null) {
            anim.repeatCount = if (playMode == BannerMotionPlayMode.ONCE) {
                0
            } else {
                AnimatedImageDrawable.REPEAT_INFINITE
            }
            if (playMode == BannerMotionPlayMode.ONCE) {
                cb = object : Animatable2.AnimationCallback() {
                    override fun onAnimationEnd(drawable: Drawable?) {
                        finishedOnce = true
                    }
                }
                anim.registerAnimationCallback(cb)
            }
        }
        onDispose {
            if (anim != null) {
                cb?.let { anim.unregisterAnimationCallback(it) }
                anim.stop()
            }
        }
    }

    AndroidView(
        modifier = modifier.graphicsLayer { alpha = visibleAlpha },
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(loaded)
            }
        },
        update = { view ->
            if (view.drawable !== loaded) view.setImageDrawable(loaded)
            val anim = loaded as? AnimatedImageDrawable
            if (anim != null) {
                if (isResumed && !(playMode == BannerMotionPlayMode.ONCE && finishedOnce)) {
                    if (!anim.isRunning) anim.start()
                } else if (anim.isRunning) {
                    anim.stop()
                }
            }
        },
    )
}

@Composable
internal fun HomeBannerPageBackground(
    bannerUri: String,
    styleConfig: LocalBannerStyleConfig,
    theme: HomeBannerTheme,
    modifier: Modifier = Modifier,
) {
    val surface = MiuixTheme.colorScheme.surface
    val dark = surface.luminance() < 0.5f
    val context = LocalContext.current

    when (styleConfig.backgroundStyle) {
        HomeBackgroundStyle.DEFAULT -> {
            Box(modifier.background(surface))
        }
        HomeBackgroundStyle.COLOR -> {
            Box(modifier.background(theme.surfaceColor))
        }
        HomeBackgroundStyle.COLOR_BLUR -> {
            Box(modifier.clipToBounds().background(theme.surfaceColor)) {
                if (bannerUri.isNotBlank() && LocalAdvancedMaterialEnabled.current) {
                    val blurRequest = remember(bannerUri, context) {
                        ImageRequest.Builder(context)
                            .data(bannerUri)
                            .size(384)
                            .build()
                    }
                    AsyncImage(
                        model = blurRequest,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                scaleX = 1.16f
                                scaleY = 1.16f
                            }
                            .blur(styleConfig.backgroundBlurDp.dp),
                    )
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(theme.surfaceColor.copy(alpha = if (dark) 0.62f else 0.70f)),
                    )
                }
            }
        }
    }
}

private fun readReversePack(file: File): ReverseFramePack? = runCatching {
    DataInputStream(FileInputStream(file).buffered()).use { input ->
        val magic = ByteArray(4)
        input.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "AREV") return null
        val count = input.readInt()
        if (count <= 0 || count > 240) return null
        val pts = LongArray(count)
        val jpegs = Array(count) { ByteArray(0) }
        for (i in 0 until count) {
            pts[i] = input.readLong()
            val len = input.readInt()
            if (len <= 0 || len > 4 * 1024 * 1024) return null
            val buf = ByteArray(len)
            input.readFully(buf)
            jpegs[i] = buf
        }
        ReverseFramePack(pts, jpegs)
    }
}.getOrNull()

private fun decodeFrameReusable(bytes: ByteArray, previous: Bitmap?): Bitmap? {
    val opts = BitmapFactory.Options().apply {
        inMutable = true
        inPreferredConfig = Bitmap.Config.ARGB_8888
        if (previous != null && !previous.isRecycled) {
            inBitmap = previous
        }
    }
    return runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull() ?: runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
