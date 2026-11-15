// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Color as AndroidColor
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import btm.m.auraplayer.rear.RearColorMode
import btm.m.auraplayer.rear.RearScreenLauncher
import btm.m.auraplayer.ui.AppUiStyle
import btm.m.auraplayer.ui.LocalAppUiStyle
import btm.m.auraplayer.ui.player.RearPlayerScreen
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

private const val ACTION_SUB_SCREEN_ON = "miui.intent.action.SUB_SCREEN_ON"
private const val ACTION_SUB_SCREEN_OFF = "miui.intent.action.SUB_SCREEN_OFF"
private const val SETTING_REAR_SLEEP_TOKEN = "has_acquire_sleep_token"
private const val MAIN_DISPLAY_DREAM_GUARD_MS = 1_200L

class RearPlayerActivity : ComponentActivity() {
    private val model: AuraViewModel by viewModels()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isAodMode by mutableStateOf(false)
    private var displayManager: DisplayManager? = null

    private var lastDisplayAod = false
    private var subScreenSignalAod = false
    private var dreamSignalAod = false
    private var sleepTokenAod = false
    private var mainDisplayLastOffAtMs = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                val mainDisplay = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
                if (mainDisplay != null && mainDisplay.state != Display.STATE_ON) {
                    mainDisplayLastOffAtMs = SystemClock.elapsedRealtime()
                }
                return
            }
            val currentId = display?.displayId ?: RearScreenLauncher.DEFAULT_REAR_DISPLAY_ID
            if (displayId == currentId) updateAodState()
        }
    }

    private val aodReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SUB_SCREEN_OFF -> {
                    subScreenSignalAod = true
                    updateAodState()
                }
                ACTION_SUB_SCREEN_ON -> {
                    subScreenSignalAod = false
                    dreamSignalAod = false
                    sleepTokenAod = false
                    updateAodState()
                }
                Intent.ACTION_DREAMING_STARTED -> {
                    if (SystemClock.elapsedRealtime() - mainDisplayLastOffAtMs > MAIN_DISPLAY_DREAM_GUARD_MS) {
                        dreamSignalAod = true
                        updateAodState()
                    }
                }
                Intent.ACTION_DREAMING_STOPPED -> {
                    dreamSignalAod = false
                    subScreenSignalAod = false
                    updateAodState()
                }
            }
        }
    }

    private val sleepTokenObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            sleepTokenAod = readSleepTokenActive()
            updateAodState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRearWindow()
        displayManager = getSystemService(DisplayManager::class.java)?.also { manager ->
            manager.registerDisplayListener(displayListener, mainHandler)
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                aodReceiver,
                IntentFilter().apply {
                    addAction(ACTION_SUB_SCREEN_OFF)
                    addAction(ACTION_SUB_SCREEN_ON)
                    addAction(Intent.ACTION_DREAMING_STARTED)
                    addAction(Intent.ACTION_DREAMING_STOPPED)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_REAR_SLEEP_TOKEN),
                false,
                sleepTokenObserver,
            )
        }
        sleepTokenAod = readSleepTokenActive()
        updateAodState()

        setContent {
            val app = applicationContext as AuraApplication
            val settings = app.settings.value
            val rearConfig by app.rearScreen.state.collectAsState()
            val baseDark = when (rearConfig.colorMode) {
                RearColorMode.DARK -> true
                RearColorMode.LIGHT -> false
                RearColorMode.SYSTEM -> isSystemInDarkTheme()
            }
            val dark = baseDark || rearConfig.amoledBlack
            val uiStyle = when (settings.uiStyle) {
                "material" -> AppUiStyle.MATERIAL
                "one" -> AppUiStyle.ONE
                else -> AppUiStyle.HYPER
            }
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalAppUiStyle provides uiStyle,
                    btm.m.auraplayer.ui.LocalAdvancedMaterialEnabled provides !settings.disableAdvancedMaterial,
                ) {
                    RearPlayerScreen(
                        model = model,
                        isAodMode = isAodMode,
                        onKeepScreenOn = ::setKeepScreenOn,
                        onExit = ::finishAndRemoveTask,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAodState()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.actionMasked == MotionEvent.ACTION_DOWN && isAodMode) {
            subScreenSignalAod = false
            dreamSignalAod = false
            sleepTokenAod = false
            updateAodState()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        displayManager?.unregisterDisplayListener(displayListener)
        runCatching { unregisterReceiver(aodReceiver) }
        runCatching { contentResolver.unregisterContentObserver(sleepTokenObserver) }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            updateAodState()
        }
    }

    private fun updateAodState() {
        val currentDisplay = display
            ?: displayManager?.getDisplay(RearScreenLauncher.DEFAULT_REAR_DISPLAY_ID)
        val state = currentDisplay?.state ?: Display.STATE_ON
        val committedState = readCommittedState(currentDisplay) ?: state
        val displayAod = state != Display.STATE_ON || committedState != Display.STATE_ON
        if (lastDisplayAod && !displayAod) {
            // Rear display genuinely returned from hardware off/doze back to ON.
            subScreenSignalAod = false
            dreamSignalAod = false
            sleepTokenAod = false
        }
        lastDisplayAod = displayAod
        isAodMode = displayAod || subScreenSignalAod || dreamSignalAod || sleepTokenAod
    }

    private fun readCommittedState(targetDisplay: Display?): Int? = runCatching {
        if (targetDisplay == null) return@runCatching null
        Display::class.java.getMethod("getCommittedState").invoke(targetDisplay) as? Int
    }.getOrNull()

    private fun readSleepTokenActive(): Boolean = runCatching {
        Settings.Secure.getInt(contentResolver, SETTING_REAR_SLEEP_TOKEN, 0) == 1
    }.getOrDefault(false)

    private fun configureRearWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        window.decorView.keepScreenOn = enabled
    }
}
