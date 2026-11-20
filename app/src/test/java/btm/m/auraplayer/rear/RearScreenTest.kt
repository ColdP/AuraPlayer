package btm.m.auraplayer.rear

import btm.m.auraplayer.lyrics.LyricSize
import btm.m.auraplayer.lyrics.LyricTapSeekGuard
import btm.m.auraplayer.lyrics.TapSeekAction
import btm.m.auraplayer.playback.LyricTheme
import btm.m.auraplayer.playback.NowPlayingConfig
import btm.m.auraplayer.playback.PlayerTheme
import btm.m.auraplayer.playback.PlayerWeather
import btm.m.auraplayer.playback.TokyoSceneLandmark
import btm.m.auraplayer.playback.TokyoSceneTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RearScreenTest {
    @Test
    fun deviceSupportMatchesOnlyXiaomiAndFourSupportedCodenames() {
        val expected = mapOf(
            "pandora" to Pair(904, 572),
            "popsicle" to Pair(976, 596),
            "hongkong" to Pair(912, 596),
            "madrid" to Pair(976, 596),
        )
        for ((code, dims) in expected) {
            assertTrue(RearScreenDevice.isSupported("xiaomi", code))
            assertTrue(RearScreenDevice.isSupported("Xiaomi", code.uppercase()))
            val profile = RearScreenDevice.findProfile("XIAOMI", code)
            assertNotNull(profile)
            assertEquals(dims.first, profile!!.widthPx)
            assertEquals(dims.second, profile.heightPx)
        }

        assertFalse(RearScreenDevice.isSupported("redmi", "pandora"))
        assertFalse(RearScreenDevice.isSupported("samsung", "popsicle"))
        assertFalse(RearScreenDevice.isSupported("xiaomi", "houji"))
        assertFalse(RearScreenDevice.isSupported("xiaomi", ""))
        assertFalse(RearScreenDevice.isSupported("", "madrid"))
    }

    @Test
    fun safeInsetsRespectCutoutSafeEdgesAndBoundingRectFallbacks() {
        val withSafeLeft = RearScreenDevice.computeSafeInsetsPx(
            screenWidthPx = 904,
            screenHeightPx = 572,
            safeLeft = 296,
            safeTop = 0,
            safeRight = 0,
            safeBottom = 0,
            boundingRects = emptyList(),
            extraMarginPx = 12,
        )
        assertEquals(RearSafeInsetsPx(308, 12, 12, 12), withSafeLeft)

        val withLeftRectFallback = RearScreenDevice.computeSafeInsetsPx(
            screenWidthPx = 976,
            screenHeightPx = 596,
            safeLeft = 0,
            safeTop = 0,
            safeRight = 0,
            safeBottom = 0,
            boundingRects = listOf(RearCutoutRect(0, 0, 280, 520)),
            extraMarginPx = 10,
        )
        assertEquals(RearSafeInsetsPx(290, 10, 10, 10), withLeftRectFallback)

        val withRightRectFallback = RearScreenDevice.computeSafeInsetsPx(
            screenWidthPx = 976,
            screenHeightPx = 596,
            safeLeft = 0,
            safeTop = 0,
            safeRight = 0,
            safeBottom = 0,
            boundingRects = listOf(RearCutoutRect(696, 0, 976, 520)),
            extraMarginPx = 10,
        )
        assertEquals(RearSafeInsetsPx(10, 10, 290, 10), withRightRectFallback)
    }

    @Test
    fun rearScreenCodecRoundtripsAndHandlesInvalidJsonGracefully() {
        assertEquals(RearScreenConfig(), RearScreenCodec.decode(null))
        assertEquals(RearScreenConfig(), RearScreenCodec.decode("not json"))

        for (theme in PlayerTheme.entries) {
            for (weather in PlayerWeather.entries) {
                val cfg = RearScreenConfig(
                    followMainTheme = theme.ordinal % 2 == 0,
                    theme = theme,
                    colorMode = RearColorMode.entries[(theme.ordinal + weather.ordinal) % RearColorMode.entries.size],
                    amoledBlack = (theme.ordinal + weather.ordinal) % 2 == 1,
                    restartSubscreenBeforeCast = theme.ordinal % 2 == 1,
                    showVolume = theme != PlayerTheme.ONE,
                    swipeTracks = weather != PlayerWeather.RAIN,
                    keepLyricsAwake = theme == PlayerTheme.AURORA,
                    lyricSize = LyricSize.entries[theme.ordinal % LyricSize.entries.size],
                    lyricSpacing = RearLyricSpacing.entries[weather.ordinal % RearLyricSpacing.entries.size],
                    showTranslation = weather != PlayerWeather.SNOW,
                    showPronunciation = theme == PlayerTheme.MATERIAL,
                    lyricTheme = LyricTheme.entries[(theme.ordinal + weather.ordinal) % LyricTheme.entries.size],
                    tokyoSceneTime = TokyoSceneTime.entries[(theme.ordinal + weather.ordinal) % TokyoSceneTime.entries.size],
                    tokyoSceneLandmark = TokyoSceneLandmark.entries[(theme.ordinal + weather.ordinal) % TokyoSceneLandmark.entries.size],
                    weather = weather,
                )
                assertEquals(cfg, RearScreenCodec.decode(RearScreenCodec.encode(cfg)))
            }
        }

        assertEquals("Xiaomi 17 Pro (pandora)", RearScreenDevice.formatDeviceLabel("xiaomi", "pandora", "2509"))
        assertEquals("Xiaomi 18 Pro Max (madrid)", RearScreenDevice.formatDeviceLabel("Xiaomi", "madrid", "2609"))

        // Legacy JSON containing removed coverInfoLyricsOnly should decode cleanly without failing
        val legacyDecoded = RearScreenCodec.decode(
            """{"followMainTheme":false,"theme":"ONE","coverInfoLyricsOnly":true,"showVolume":false}""",
        )
        assertEquals(PlayerTheme.ONE, legacyDecoded.theme)
        assertFalse(legacyDecoded.showVolume)
    }

    @Test
    fun followMainThemeInheritsMainThemeWeatherAndLyricTheme() {
        val main = NowPlayingConfig(
            theme = PlayerTheme.MATERIAL,
            blur = 32,
            lyricTheme = LyricTheme.TOKYO_CITYSCAPE,
            tokyoSceneTime = TokyoSceneTime.DUSK,
            tokyoSceneLandmark = TokyoSceneLandmark.SHINJUKU_NEON,
            weather = PlayerWeather.RAIN,
        )
        val independent = RearScreenConfig(
            followMainTheme = false,
            theme = PlayerTheme.BLUR,
            lyricTheme = LyricTheme.LIME_SODA,
            tokyoSceneTime = TokyoSceneTime.MORNING,
            tokyoSceneLandmark = TokyoSceneLandmark.ODAIBA_BAY,
            weather = PlayerWeather.SNOW,
        ).resolveAppearance(main)
        assertEquals(PlayerTheme.BLUR, independent.theme)
        assertEquals(LyricTheme.LIME_SODA, independent.lyricTheme)
        assertEquals(TokyoSceneTime.MORNING, independent.tokyoSceneTime)
        assertEquals(TokyoSceneLandmark.ODAIBA_BAY, independent.tokyoSceneLandmark)
        assertEquals(PlayerWeather.SNOW, independent.weather)

        val following = RearScreenConfig(
            followMainTheme = true,
            theme = PlayerTheme.BLUR,
            lyricTheme = LyricTheme.LIME_SODA,
            tokyoSceneTime = TokyoSceneTime.MORNING,
            tokyoSceneLandmark = TokyoSceneLandmark.ODAIBA_BAY,
            weather = PlayerWeather.SNOW,
        ).resolveAppearance(main)
        assertEquals(PlayerTheme.MATERIAL, following.theme)
        assertEquals(LyricTheme.TOKYO_CITYSCAPE, following.lyricTheme)
        assertEquals(TokyoSceneTime.DUSK, following.tokyoSceneTime)
        assertEquals(TokyoSceneLandmark.SHINJUKU_NEON, following.tokyoSceneLandmark)
        assertEquals(PlayerWeather.RAIN, following.weather)
        assertEquals(TokyoSceneTime.DUSK, following.toNowPlayingConfig(main, RearScreenConfig(followMainTheme = true)).tokyoSceneTime)
        assertEquals(TokyoSceneLandmark.SHINJUKU_NEON, following.toNowPlayingConfig(main, RearScreenConfig(followMainTheme = true)).tokyoSceneLandmark)
    }

    @Test
    fun privilegeResolutionPrefersRootThenShizukuThenUnauthorized() {
        assertEquals(RearPrivilegeState.ROOT, resolvePrivilegeState(hasRoot = true, shizukuAuthorized = true))
        assertEquals(RearPrivilegeState.ROOT, resolvePrivilegeState(hasRoot = true, shizukuAuthorized = false))
        assertEquals(RearPrivilegeState.SHIZUKU, resolvePrivilegeState(hasRoot = false, shizukuAuthorized = true))
        assertEquals(RearPrivilegeState.UNAUTHORIZED, resolvePrivilegeState(hasRoot = false, shizukuAuthorized = false))
    }

    @Test
    fun rearCastCommandAndTaskParserTargetDedicatedRearActivity() {
        val cmd = RearScreenLauncher.buildRearCastCommand(
            packageName = "btm.m.auraplayer",
            activityClassName = "btm.m.auraplayer.RearPlayerActivity",
            targetDisplayId = 1,
            restartSubscreenBeforeCast = true,
        )
        assertTrue(cmd.contains("am force-stop"))
        assertTrue(cmd.contains("com.xiaomi.subscreencenter"))
        assertFalse(cmd.contains(RearScreenLauncher.SUBSCREEN_LAUNCHER_COMPONENT))
        assertTrue(cmd.indexOf("am force-stop") < cmd.indexOf("0x10000000"))
        assertTrue(cmd.contains("--display"))
        assertTrue(cmd.contains("service call activity_task 50"))
        assertTrue(cmd.contains("am display move-stack"))
        assertTrue(cmd.contains("RootTask"))
        assertTrue(cmd.contains("awk"))
        assertTrue(cmd.contains("taskId="))
        assertFalse(cmd.contains("visible=true"))
        assertFalse(cmd.contains("OK_MOVED_CALL"))

        val directCmd = RearScreenLauncher.buildRearCastCommand(
            packageName = "btm.m.auraplayer",
            activityClassName = "btm.m.auraplayer.RearPlayerActivity",
            targetDisplayId = 1,
            restartSubscreenBeforeCast = false,
        )
        assertFalse(directCmd.contains("am force-stop"))
        assertFalse(directCmd.contains(RearScreenLauncher.SUBSCREEN_LAUNCHER_COMPONENT))
        assertTrue(directCmd.contains("0x10000000"))
        assertTrue(directCmd.contains("--display"))
        assertTrue(directCmd.contains("service call activity_task 50"))

        val stackList = """
            RootTask id=42 bounds=[0,0][1200,2670] displayId=0
              taskId=101: btm.m.auraplayer/.MainActivity visible=true
            RootTask id=58 bounds=[0,0][904,572] displayId=0
              taskId=202: btm.m.auraplayer/.RearPlayerActivity visible=false
        """.trimIndent()
        assertEquals(
            202,
            RearScreenLauncher.parseTaskIdForRearMove(
                stackListOutput = stackList,
                packageName = "btm.m.auraplayer",
                activityClassName = "btm.m.auraplayer.RearPlayerActivity",
            ),
        )
        assertNull(
            RearScreenLauncher.parseTaskIdForRearMove(
                stackListOutput = "RootTask id=1 displayId=0",
                packageName = "btm.m.auraplayer",
                activityClassName = "btm.m.auraplayer.RearPlayerActivity",
            ),
        )
    }

    @Test
    fun delayedTapSeekCancelsOnDoubleTapWithin600msAndAllowsDragThenSingleTap() {
        val mainScreenGuard = LyricTapSeekGuard()
        assertFalse(mainScreenGuard.onTouchDown(100L))
        assertEquals(TapSeekAction.SeekNow(42_000L), mainScreenGuard.onTouchTapUp(42_000L, 140L))

        val rearGuard = LyricTapSeekGuard().apply { delayMs = 600L }

        // First drag then tap once -> schedules 600ms delayed seek and executes when elapsed
        assertFalse(rearGuard.onTouchDown(1_000L))
        rearGuard.onTouchDragOrCancel()
        assertFalse(rearGuard.onTouchDown(1_400L))
        val scheduled = rearGuard.onTouchTapUp(15_000L, 1_450L) as TapSeekAction.ScheduleSeek
        assertEquals(15_000L, scheduled.targetMs)
        assertEquals(600L, scheduled.delayMs)
        assertEquals(15_000L, rearGuard.consumeScheduled(scheduled.generation))

        // Double tap within 0.6s -> second tap cancels first seek and does not schedule another
        assertFalse(rearGuard.onTouchDown(5_000L))
        val firstTap = rearGuard.onTouchTapUp(20_000L, 5_040L) as TapSeekAction.ScheduleSeek
        assertTrue(rearGuard.onTouchDown(5_280L))
        assertEquals(TapSeekAction.CancelledDoubleTap, rearGuard.onTouchTapUp(20_000L, 5_320L))
        assertNull(rearGuard.consumeScheduled(firstTap.generation))

        // First tap misses row, second tap hits row within 0.6s -> still suppressed as double tap
        assertFalse(rearGuard.onTouchDown(10_000L))
        assertEquals(TapSeekAction.None, rearGuard.onTouchTapUp(null, 10_040L))
        assertFalse(rearGuard.onTouchDown(10_250L))
        assertEquals(TapSeekAction.CancelledDoubleTap, rearGuard.onTouchTapUp(30_000L, 10_290L))

        // External cancellation (e.g. entering AOD) aborts pending seek
        assertFalse(rearGuard.onTouchDown(15_000L))
        val pendingAod = rearGuard.onTouchTapUp(50_000L, 15_040L) as TapSeekAction.ScheduleSeek
        assertTrue(rearGuard.cancelPending())
        assertNull(rearGuard.consumeScheduled(pendingAod.generation))
    }
}
