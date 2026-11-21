// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.MainActivity
import btm.m.auraplayer.R
import btm.m.auraplayer.data.Appearance
import btm.m.auraplayer.playback.NowPlayingCodec
import btm.m.auraplayer.playback.NowPlayingConfig
import btm.m.auraplayer.playback.PlayerTheme
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object WidgetUpdateCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updateSignals = Channel<Context>(Channel.CONFLATED)

    private val artworkBitmapCache = object : LruCache<String, Bitmap>(6) {}
    private val artworkSeedCache = object : LruCache<String, Int>(24) {}

    private data class PlayerProviderSpec(
        val cls: Class<*>,
        val defaultTier: WidgetLayoutTier,
    )

    private val playerProviderSpecs = listOf(
        PlayerProviderSpec(AuraPlayerWidget2x2Provider::class.java, WidgetLayoutTier.SIZE_2X2),
        PlayerProviderSpec(AuraPlayerWidget4x1Provider::class.java, WidgetLayoutTier.SIZE_4X1),
        PlayerProviderSpec(AuraPlayerWidgetProvider::class.java, WidgetLayoutTier.SIZE_4X2),
        PlayerProviderSpec(AuraPlayerWidget4x3Provider::class.java, WidgetLayoutTier.SIZE_4X3),
        PlayerProviderSpec(AuraPlayerWidget4x4Provider::class.java, WidgetLayoutTier.SIZE_4X4),
    )

    init {
        scope.launch {
            for (ctx in updateSignals) {
                runCatching { performUpdate(ctx.applicationContext) }
            }
        }
    }

    fun hasActiveWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        if (manager.getAppWidgetIds(ComponentName(context, AuraCoverWidgetProvider::class.java)).isNotEmpty()) {
            return true
        }
        return playerProviderSpecs.any { spec ->
            manager.getAppWidgetIds(ComponentName(context, spec.cls)).isNotEmpty()
        }
    }

    fun requestUpdate(context: Context) {
        updateSignals.trySend(context.applicationContext)
    }

    private suspend fun performUpdate(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val activePlayerGroups = playerProviderSpecs.mapNotNull { spec ->
            val ids = manager.getAppWidgetIds(ComponentName(context, spec.cls))
            if (ids.isEmpty()) null else spec to ids
        }
        val coverIds = manager.getAppWidgetIds(ComponentName(context, AuraCoverWidgetProvider::class.java))
        if (activePlayerGroups.isEmpty() && coverIds.isEmpty()) return

        val snapshot = WidgetPlaybackState.readSnapshot(context)
        val nowPlayingConfig = readNowPlayingConfig(context)
        val appearance = readAppearance(context)
        val isSystemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val appDark = when (appearance.theme) {
            "dark" -> true
            "light" -> false
            else -> isSystemDark
        }
        val advancedMaterial = !appearance.disableAdvancedMaterial
        val lang = appearance.language
        val useOklab = appearance.isOklabColor
        val paletteIndex = appearance.palette

        val currentUri = snapshot.current?.artworkUri.orEmpty()
        val currentArtwork = loadArtworkBitmap(context, currentUri, targetSizePx = 400)
        val seed = resolveSeed(currentUri, currentArtwork, useOklab)
        val themeColors = WidgetThemeEngine.resolveThemeColors(
            config = nowPlayingConfig,
            appDark = appDark,
            seedArgb = seed,
            isPlaying = snapshot.isPlaying,
            useOklab = useOklab,
            paletteIndex = paletteIndex,
        )

        val needsUpcomingArt = activePlayerGroups.any { (spec, ids) ->
            ids.any { id ->
                val opts = manager.getAppWidgetOptions(id)
                val sizes = getWidgetSizes(opts)
                if (!sizes.isNullOrEmpty()) {
                    sizes.any {
                        val t = selectPlayerWidgetLayoutTier(it.width, it.height)
                        t == WidgetLayoutTier.SIZE_4X3 || t == WidgetLayoutTier.SIZE_4X4
                    }
                } else {
                    val t = resolveTierFromOptions(opts, spec.defaultTier)
                    t == WidgetLayoutTier.SIZE_4X3 || t == WidgetLayoutTier.SIZE_4X4
                }
            }
        }

        val upcomingArtworks = if (needsUpcomingArt) {
            snapshot.upcoming.map { track ->
                loadArtworkBitmap(context, track.artworkUri, targetSizePx = 128)
            }
        } else {
            emptyList()
        }

        val brandDrawable = ContextCompat.getDrawable(context, R.drawable.ic_aura)

        if (activePlayerGroups.isNotEmpty()) {
            val tierViewsCache = HashMap<WidgetLayoutTier, RemoteViews>()
            fun viewsForTier(tier: WidgetLayoutTier): RemoteViews = tierViewsCache.getOrPut(tier) {
                buildPlayerRemoteViews(
                    context = context,
                    tier = tier,
                    snapshot = snapshot,
                    currentArtwork = currentArtwork,
                    upcomingArtworks = upcomingArtworks,
                    config = nowPlayingConfig,
                    colors = themeColors,
                    advancedMaterial = advancedMaterial,
                    appDark = appDark,
                    seedArgb = seed,
                    useOklab = useOklab,
                    paletteIndex = paletteIndex,
                    brandDrawable = brandDrawable,
                    lang = lang,
                )
            }

            for ((spec, ids) in activePlayerGroups) {
                for (appWidgetId in ids) {
                    val options = manager.getAppWidgetOptions(appWidgetId)
                    val sizes = getWidgetSizes(options)
                    if (!sizes.isNullOrEmpty()) {
                        if (sizes.size == 1) {
                            val tier = selectPlayerWidgetLayoutTier(sizes[0].width, sizes[0].height)
                            manager.updateAppWidget(appWidgetId, viewsForTier(tier))
                        } else {
                            val sizeMap = LinkedHashMap<SizeF, RemoteViews>(sizes.size)
                            for (size in sizes) {
                                val tier = selectPlayerWidgetLayoutTier(size.width, size.height)
                                sizeMap[size] = viewsForTier(tier)
                            }
                            manager.updateAppWidget(appWidgetId, RemoteViews(sizeMap))
                        }
                    } else {
                        val tier = resolveTierFromOptions(options, spec.defaultTier)
                        manager.updateAppWidget(appWidgetId, viewsForTier(tier))
                    }
                }
            }
        }

        if (coverIds.isNotEmpty()) {
            val coverViews = buildCoverWidgetRemoteViews(
                context = context,
                snapshot = snapshot,
                currentArtwork = currentArtwork,
                config = nowPlayingConfig,
                colors = themeColors,
                advancedMaterial = advancedMaterial,
                appDark = appDark,
                seedArgb = seed,
                useOklab = useOklab,
                paletteIndex = paletteIndex,
                brandDrawable = brandDrawable,
                lang = lang,
            )
            for (appWidgetId in coverIds) {
                manager.updateAppWidget(appWidgetId, coverViews)
            }
        }
    }

    private fun resolveTierFromOptions(options: Bundle, fallbackTier: WidgetLayoutTier): WidgetLayoutTier {
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        if (minW <= 0 || (minH <= 0 && maxH <= 0)) return fallbackTier
        val heightDp = (if (maxH > 0) maxH else minH).toFloat()
        return selectPlayerWidgetLayoutTier(minW.toFloat(), heightDp)
    }

    private fun buildPlayerRemoteViews(
        context: Context,
        tier: WidgetLayoutTier,
        snapshot: WidgetPlaybackSnapshot,
        currentArtwork: Bitmap?,
        upcomingArtworks: List<Bitmap?>,
        config: NowPlayingConfig,
        colors: WidgetThemeColors,
        advancedMaterial: Boolean,
        appDark: Boolean,
        seedArgb: Int,
        useOklab: Boolean,
        paletteIndex: Int,
        brandDrawable: android.graphics.drawable.Drawable?,
        lang: String,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutResForTier(tier))
        val (bgW, bgH) = when (tier) {
            WidgetLayoutTier.SIZE_4X1 -> 260 to 84
            WidgetLayoutTier.SIZE_2X2 -> 180 to 180
            WidgetLayoutTier.SIZE_4X2 -> 240 to 132
            WidgetLayoutTier.SIZE_4X3 -> 220 to 176
            WidgetLayoutTier.SIZE_4X4 -> 220 to 220
        }
        val bgBitmap = WidgetThemeEngine.renderThemeBackgroundBitmap(
            widthPx = bgW,
            heightPx = bgH,
            artwork = currentArtwork,
            config = config,
            advancedMaterial = advancedMaterial,
            appDark = appDark,
            seedArgb = seedArgb,
            useOklab = useOklab,
            paletteIndex = paletteIndex,
        )
        views.setImageViewBitmap(R.id.widget_bg_image, bgBitmap)

        val coverBitmap = when (tier) {
            WidgetLayoutTier.SIZE_2X2 -> WidgetThemeEngine.renderRoundedCoverBitmap(
                artwork = currentArtwork,
                widthPx = 220,
                heightPx = 152,
                cornerRadiusPx = colors.coverCornerDp * (220f / 140f),
                placeholderBg = colors.placeholderBgColor,
                placeholderIconColor = colors.placeholderIconColor,
                brandDrawable = brandDrawable,
            )
            WidgetLayoutTier.SIZE_4X1 -> WidgetThemeEngine.renderRoundedCoverBitmap(
                artwork = currentArtwork,
                widthPx = 132,
                heightPx = 132,
                cornerRadiusPx = colors.coverCornerDp * (132f / 52f),
                placeholderBg = colors.placeholderBgColor,
                placeholderIconColor = colors.placeholderIconColor,
                brandDrawable = brandDrawable,
            )
            WidgetLayoutTier.SIZE_4X2 -> WidgetThemeEngine.renderRoundedCoverBitmap(
                artwork = currentArtwork,
                widthPx = 224,
                heightPx = 224,
                cornerRadiusPx = colors.coverCornerDp * (224f / 112f),
                placeholderBg = colors.placeholderBgColor,
                placeholderIconColor = colors.placeholderIconColor,
                brandDrawable = brandDrawable,
            )
            WidgetLayoutTier.SIZE_4X3 -> WidgetThemeEngine.renderRoundedCoverBitmap(
                artwork = currentArtwork,
                widthPx = 176,
                heightPx = 176,
                cornerRadiusPx = colors.coverCornerDp * (176f / 80f),
                placeholderBg = colors.placeholderBgColor,
                placeholderIconColor = colors.placeholderIconColor,
                brandDrawable = brandDrawable,
            )
            WidgetLayoutTier.SIZE_4X4 -> WidgetThemeEngine.renderRoundedCoverBitmap(
                artwork = currentArtwork,
                widthPx = 192,
                heightPx = 192,
                cornerRadiusPx = colors.coverCornerDp * (192f / 88f),
                placeholderBg = colors.placeholderBgColor,
                placeholderIconColor = colors.placeholderIconColor,
                brandDrawable = brandDrawable,
            )
        }
        views.setImageViewBitmap(R.id.widget_cover, coverBitmap)

        val title = snapshot.current?.title?.takeIf { it.isNotBlank() }
            ?: widgetText(lang, "暂无播放歌曲", "Nothing Playing", "再生中の曲はありません")
        val artist = snapshot.current?.artist?.takeIf { it.isNotBlank() }
            ?: widgetText(lang, "轻触打开 Aura Player", "Tap to open Aura Player", "タップして Aura Player を開く")

        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_artist, artist)
        views.setTextColor(R.id.widget_title, colors.titleColor)
        views.setTextColor(R.id.widget_artist, colors.artistColor)

        val openPlayerIntent = openPlayerPendingIntent(context)
        views.setOnClickPendingIntent(R.id.widget_content, openPlayerIntent)
        views.setOnClickPendingIntent(R.id.widget_meta_click, openPlayerIntent)
        views.setOnClickPendingIntent(R.id.widget_cover, openPlayerIntent)

        if (tier == WidgetLayoutTier.SIZE_2X2) {
            views.setContentDescription(R.id.widget_content, "$title - $artist")
            return views
        }

        bindTransportControls(
            context = context,
            views = views,
            hasCurrent = snapshot.current != null,
            isPlaying = snapshot.isPlaying,
            colors = colors,
            config = config,
            openPlayerIntent = openPlayerIntent,
            lang = lang,
        )

        if (tier == WidgetLayoutTier.SIZE_4X3 || tier == WidgetLayoutTier.SIZE_4X4) {
            views.setTextViewText(
                R.id.widget_up_next_label,
                widgetText(lang, "接下来播放", "Up Next", "次に再生"),
            )
            views.setTextColor(R.id.widget_up_next_label, colors.mutedColor)

            val maxSlots = if (tier == WidgetLayoutTier.SIZE_4X4) 2 else 1
            val upcoming = snapshot.upcoming.take(maxSlots)

            if (upcoming.isEmpty()) {
                views.setViewVisibility(R.id.widget_next_row_1, View.GONE)
                if (tier == WidgetLayoutTier.SIZE_4X4) {
                    views.setViewVisibility(R.id.widget_next_row_2, View.GONE)
                }
                views.setViewVisibility(R.id.widget_empty_queue, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_empty_queue,
                    widgetText(lang, "队列中暂无下一首", "No upcoming tracks", "次の曲はありません"),
                )
                views.setTextColor(R.id.widget_empty_queue, colors.mutedColor)
            } else {
                views.setViewVisibility(R.id.widget_empty_queue, View.GONE)
                bindUpcomingSlot(
                    context = context,
                    views = views,
                    rowId = R.id.widget_next_row_1,
                    bgId = R.id.widget_next_bg_1,
                    clickId = R.id.widget_next_click_1,
                    coverId = R.id.widget_next_cover_1,
                    titleId = R.id.widget_next_title_1,
                    artistId = R.id.widget_next_artist_1,
                    track = upcoming[0],
                    artwork = upcomingArtworks.getOrNull(0),
                    colors = colors,
                    brandDrawable = brandDrawable,
                    requestCode = 201,
                    lang = lang,
                )
                if (tier == WidgetLayoutTier.SIZE_4X4) {
                    if (upcoming.size >= 2) {
                        bindUpcomingSlot(
                            context = context,
                            views = views,
                            rowId = R.id.widget_next_row_2,
                            bgId = R.id.widget_next_bg_2,
                            clickId = R.id.widget_next_click_2,
                            coverId = R.id.widget_next_cover_2,
                            titleId = R.id.widget_next_title_2,
                            artistId = R.id.widget_next_artist_2,
                            track = upcoming[1],
                            artwork = upcomingArtworks.getOrNull(1),
                            colors = colors,
                            brandDrawable = brandDrawable,
                            requestCode = 202,
                            lang = lang,
                        )
                    } else {
                        views.setViewVisibility(R.id.widget_next_row_2, View.GONE)
                    }
                }
            }
        }
        return views
    }

    private fun buildCoverWidgetRemoteViews(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        currentArtwork: Bitmap?,
        config: NowPlayingConfig,
        colors: WidgetThemeColors,
        advancedMaterial: Boolean,
        appDark: Boolean,
        seedArgb: Int,
        useOklab: Boolean,
        paletteIndex: Int,
        brandDrawable: android.graphics.drawable.Drawable?,
        lang: String,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_cover_4x4)
        val bgBitmap = WidgetThemeEngine.renderThemeBackgroundBitmap(
            widthPx = 220,
            heightPx = 220,
            artwork = currentArtwork,
            config = config,
            advancedMaterial = advancedMaterial,
            appDark = appDark,
            seedArgb = seedArgb,
            useOklab = useOklab,
            paletteIndex = paletteIndex,
        )
        views.setImageViewBitmap(R.id.widget_bg_image, bgBitmap)

        val coverBitmap = WidgetThemeEngine.renderRoundedCoverBitmap(
            artwork = currentArtwork,
            widthPx = 360,
            heightPx = 252,
            cornerRadiusPx = colors.coverCornerDp * (360f / 312f),
            placeholderBg = colors.placeholderBgColor,
            placeholderIconColor = colors.placeholderIconColor,
            brandDrawable = brandDrawable,
        )
        views.setImageViewBitmap(R.id.widget_cover, coverBitmap)

        val title = snapshot.current?.title?.takeIf { it.isNotBlank() }
            ?: widgetText(lang, "暂无播放歌曲", "Nothing Playing", "再生中の曲はありません")
        val artist = snapshot.current?.artist?.takeIf { it.isNotBlank() }
            ?: widgetText(lang, "轻触打开 Aura Player", "Tap to open Aura Player", "タップして Aura Player を開く")

        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_artist, artist)
        views.setTextColor(R.id.widget_title, colors.titleColor)
        views.setTextColor(R.id.widget_artist, colors.artistColor)

        val openPlayerIntent = openPlayerPendingIntent(context)
        views.setOnClickPendingIntent(R.id.widget_content, openPlayerIntent)
        views.setOnClickPendingIntent(R.id.widget_meta_click, openPlayerIntent)
        views.setOnClickPendingIntent(R.id.widget_cover, openPlayerIntent)

        bindTransportControls(
            context = context,
            views = views,
            hasCurrent = snapshot.current != null,
            isPlaying = snapshot.isPlaying,
            colors = colors,
            config = config,
            openPlayerIntent = openPlayerIntent,
            lang = lang,
        )
        return views
    }

    private fun bindTransportControls(
        context: Context,
        views: RemoteViews,
        hasCurrent: Boolean,
        isPlaying: Boolean,
        colors: WidgetThemeColors,
        config: NowPlayingConfig,
        openPlayerIntent: PendingIntent,
        lang: String,
    ) {
        if (colors.usesButtonContainers) {
            val secondaryShapeRes = if (config.theme == PlayerTheme.MATERIAL) {
                R.drawable.widget_btn_squircle
            } else {
                R.drawable.widget_btn_circle
            }
            val playShapeRes = when {
                (config.theme == PlayerTheme.MATERIAL || config.theme == PlayerTheme.ONE) && !isPlaying -> {
                    R.drawable.widget_btn_squircle
                }
                else -> R.drawable.widget_btn_pill
            }

            views.setViewVisibility(R.id.widget_btn_prev_bg, View.VISIBLE)
            views.setViewVisibility(R.id.widget_btn_next_bg, View.VISIBLE)
            views.setViewVisibility(R.id.widget_btn_play_bg, View.VISIBLE)

            views.setImageViewResource(R.id.widget_btn_prev_bg, secondaryShapeRes)
            views.setImageViewResource(R.id.widget_btn_next_bg, secondaryShapeRes)
            views.setImageViewResource(R.id.widget_btn_play_bg, playShapeRes)

            views.setColorStateList(R.id.widget_btn_prev_bg, "setImageTintList", ColorStateList.valueOf(colors.secondaryBgColor))
            views.setColorStateList(R.id.widget_btn_next_bg, "setImageTintList", ColorStateList.valueOf(colors.secondaryBgColor))
            views.setColorStateList(R.id.widget_btn_play_bg, "setImageTintList", ColorStateList.valueOf(colors.playBgColor))
        } else {
            views.setViewVisibility(R.id.widget_btn_prev_bg, View.GONE)
            views.setViewVisibility(R.id.widget_btn_play_bg, View.GONE)
            views.setViewVisibility(R.id.widget_btn_next_bg, View.GONE)
        }

        views.setColorStateList(R.id.widget_btn_prev_icon, "setImageTintList", ColorStateList.valueOf(colors.secondaryIconColor))
        views.setColorStateList(R.id.widget_btn_next_icon, "setImageTintList", ColorStateList.valueOf(colors.secondaryIconColor))
        views.setColorStateList(R.id.widget_btn_play_icon, "setImageTintList", ColorStateList.valueOf(colors.playIconColor))

        views.setImageViewResource(
            R.id.widget_btn_play_icon,
            if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play,
        )

        views.setContentDescription(R.id.widget_btn_prev, widgetText(lang, "上一首", "Previous track", "前の曲"))
        views.setContentDescription(R.id.widget_btn_next, widgetText(lang, "下一首", "Next track", "次の曲"))
        views.setContentDescription(
            R.id.widget_btn_play,
            if (isPlaying) widgetText(lang, "暂停", "Pause", "一時停止") else widgetText(lang, "播放", "Play", "再生"),
        )

        if (hasCurrent) {
            views.setOnClickPendingIntent(
                R.id.widget_btn_prev,
                actionPendingIntent(context, WidgetActionReceiver.ACTION_PREV, 101),
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_play,
                actionPendingIntent(context, WidgetActionReceiver.ACTION_PLAY_PAUSE, 102),
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_next,
                actionPendingIntent(context, WidgetActionReceiver.ACTION_NEXT, 103),
            )
        } else {
            views.setOnClickPendingIntent(R.id.widget_btn_prev, openPlayerIntent)
            views.setOnClickPendingIntent(R.id.widget_btn_play, openPlayerIntent)
            views.setOnClickPendingIntent(R.id.widget_btn_next, openPlayerIntent)
        }
    }

    private fun bindUpcomingSlot(
        context: Context,
        views: RemoteViews,
        rowId: Int,
        bgId: Int,
        clickId: Int,
        coverId: Int,
        titleId: Int,
        artistId: Int,
        track: WidgetTrackInfo,
        artwork: Bitmap?,
        colors: WidgetThemeColors,
        brandDrawable: android.graphics.drawable.Drawable?,
        requestCode: Int,
        lang: String,
    ) {
        views.setViewVisibility(rowId, View.VISIBLE)
        views.setColorStateList(bgId, "setImageTintList", ColorStateList.valueOf(colors.queueCardBgColor))
        val coverBitmap = WidgetThemeEngine.renderRoundedCoverBitmap(
            artwork = artwork,
            widthPx = 96,
            heightPx = 96,
            cornerRadiusPx = 18f,
            placeholderBg = colors.placeholderBgColor,
            placeholderIconColor = colors.placeholderIconColor,
            brandDrawable = brandDrawable,
        )
        views.setImageViewBitmap(coverId, coverBitmap)
        val title = track.title.ifBlank { widgetText(lang, "未知歌曲", "Unknown Track", "不明な曲") }
        val artist = track.artist.ifBlank { widgetText(lang, "未知艺人", "Unknown Artist", "不明なアーティスト") }
        views.setTextViewText(titleId, title)
        views.setTextViewText(artistId, artist)
        views.setTextColor(titleId, colors.titleColor)
        views.setTextColor(artistId, colors.artistColor)
        views.setContentDescription(clickId, "$title - $artist")
        val intent = Intent(context, WidgetActionReceiver::class.java)
            .setAction(WidgetActionReceiver.ACTION_PLAY_QUEUE_INDEX)
            .putExtra(WidgetActionReceiver.EXTRA_QUEUE_INDEX, track.queueIndex)
        views.setOnClickPendingIntent(
            clickId,
            PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    private fun openPlayerPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_MEDIA_PLAYER)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun getWidgetSizes(options: Bundle): List<SizeF>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
        } else {
            @Suppress("DEPRECATION")
            options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        }
    }

    private suspend fun loadArtworkBitmap(context: Context, uri: String, targetSizePx: Int): Bitmap? {
        if (uri.isBlank()) return null
        val cacheKey = "$uri#$targetSizePx"
        synchronized(artworkBitmapCache) {
            artworkBitmapCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return it }
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val result = SingletonImageLoader.get(context).execute(
                    ImageRequest.Builder(context)
                        .data(uri)
                        .size(targetSizePx)
                        .allowHardware(false)
                        .build(),
                ) as? SuccessResult
                result?.image?.toBitmap()
            }.getOrNull()
        } ?: return null
        synchronized(artworkBitmapCache) {
            artworkBitmapCache.put(cacheKey, loaded)
        }
        return loaded
    }

    private fun resolveSeed(uri: String, bitmap: Bitmap?, useOklab: Boolean): Int {
        if (uri.isBlank() || bitmap == null) return DEFAULT_WIDGET_SEED
        val cacheKey = if (useOklab) "oklab:$uri" else uri
        synchronized(artworkSeedCache) {
            artworkSeedCache.get(cacheKey)?.let { return it }
        }
        val computed = if (useOklab) {
            WidgetThemeEngine.extractOklabSeed(bitmap)
        } else {
            WidgetThemeEngine.extractMonetSeed(bitmap)
        }
        synchronized(artworkSeedCache) {
            artworkSeedCache.put(cacheKey, computed)
        }
        return computed
    }

    private fun readNowPlayingConfig(context: Context): NowPlayingConfig {
        val app = context.applicationContext as? AuraApplication
        if (app != null) {
            return runCatching { app.nowPlaying.state.value }.getOrElse {
                readNowPlayingFromPrefs(context)
            }
        }
        return readNowPlayingFromPrefs(context)
    }

    private fun readNowPlayingFromPrefs(context: Context): NowPlayingConfig {
        val raw = context.getSharedPreferences("aura.now-playing.v1", Context.MODE_PRIVATE)
            .getString("config", "{}") ?: "{}"
        return NowPlayingCodec.decode(raw)
    }

    private fun readAppearance(context: Context): Appearance {
        val app = context.applicationContext as? AuraApplication
        if (app != null) {
            return runCatching { app.settings.value }.getOrElse {
                readAppearanceFromPrefs(context)
            }
        }
        return readAppearanceFromPrefs(context)
    }

    private fun readAppearanceFromPrefs(context: Context): Appearance {
        val prefs = context.getSharedPreferences("aura.settings", Context.MODE_PRIVATE)
        return Appearance(
            theme = prefs.getString("theme", "system") ?: "system",
            colorExtractionMode = btm.m.auraplayer.data.ColorExtractionMode.fromPref(
                raw = prefs.getString("colorExtractionMode", null),
                legacyMonet = prefs.getBoolean("monet", false),
            ),
            palette = prefs.getInt("palette", 0),
            language = prefs.getString("language", "zh") ?: "zh",
            disableAdvancedMaterial = prefs.getBoolean("disableAdvancedMaterial", false),
        )
    }

    private fun widgetText(lang: String, zh: String, en: String, ja: String): String = when (lang) {
        "en" -> en
        "ja" -> ja
        else -> zh
    }
}
