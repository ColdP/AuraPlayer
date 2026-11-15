// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.math.max

/**
 * Renders a cached Apple Music motion cover video inside Compose with TextureView + CENTER_CROP
 * matrix scaling so parent rounded clipping, blur effects, and immersive feather masks apply.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun AnimatedMotionCoverVideo(
    videoPath: String,
    playing: Boolean,
    visibleAlpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var firstFrameRendered by remember(videoPath) { mutableStateOf(false) }
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
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
        val scaleX = viewW / vw.toFloat()
        val scaleY = viewH / vh.toFloat()
        val scale = max(scaleX, scaleY)
        val postScaleX = (vw * scale) / viewW
        val postScaleY = (vh * scale) / viewH
        val matrix = Matrix().apply {
            setScale(postScaleX, postScaleY, viewW * 0.5f, viewH * 0.5f)
        }
        view.setTransform(matrix)
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
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

    LaunchedEffect(videoPath) {
        firstFrameRendered = false
        val uri = Uri.fromFile(File(videoPath))
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = playing
    }

    LaunchedEffect(playing) {
        exoPlayer.playWhenReady = playing
    }

    val videoAlpha by animateFloatAsState(
        targetValue = if (firstFrameRendered) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "motionCoverVideoAlpha",
    )
    val effectiveAlpha = (videoAlpha * visibleAlpha).coerceIn(0f, 1f)

    Box(modifier.graphicsLayer { alpha = effectiveAlpha }) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    alpha = effectiveAlpha
                    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        updateCenterCropTransform(v as TextureView, videoWidth, videoHeight)
                    }
                    textureViewRef = this
                    exoPlayer.setVideoTextureView(this)
                }
            },
            update = { view ->
                textureViewRef = view
                view.alpha = effectiveAlpha
                updateCenterCropTransform(view, videoWidth, videoHeight)
            },
        )
    }
}
