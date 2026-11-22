package btm.m.auraplayer

import android.os.Bundle
import android.content.Intent
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import btm.m.auraplayer.ui.AuraRoot

class MainActivity : ComponentActivity() {
    companion object { const val ACTION_MEDIA_PLAYER = "btm.m.auraplayer.OPEN_PLAYER" }
    var mediaPlayerRequest by mutableIntStateOf(0); private set
    private val model by viewModels<AuraViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        mediaPlayerRequest = savedInstanceState?.getInt("mediaPlayerRequest", 0) ?: 0
        if (savedInstanceState == null) acceptMediaIntent(intent)
        setContent { AuraRoot(model) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptMediaIntent(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("mediaPlayerRequest", mediaPlayerRequest)
        super.onSaveInstanceState(outState)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val effects = (application as? AuraApplication)?.effects
        if (effects != null && effects.isUsbExclusiveActive) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    effects.stepUsbVolume(increase = true, showHud = true)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    effects.stepUsbVolume(increase = false, showHud = true)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val effects = (application as? AuraApplication)?.effects
        if (effects != null && effects.isUsbExclusiveActive &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
    private fun acceptMediaIntent(intent: Intent?) {
        if (intent?.action == ACTION_MEDIA_PLAYER) mediaPlayerRequest++
    }
}
