// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import btm.m.auraplayer.playback.PlaybackService

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return
        val liveService = PlaybackService.activeInstance
        if (liveService != null) {
            liveService.handleWidgetAction(intent)
            return
        }
        val queuePrefs = context.getSharedPreferences("aura.playback.queue", Context.MODE_PRIVATE)
        if (queuePrefs.getInt("count", 0) <= 0) {
            WidgetUpdateCoordinator.requestUpdate(context)
            return
        }
        val serviceIntent = Intent(context, PlaybackService::class.java)
            .setAction(action)
            .apply {
                if (intent.hasExtra(EXTRA_QUEUE_INDEX)) {
                    putExtra(EXTRA_QUEUE_INDEX, intent.getIntExtra(EXTRA_QUEUE_INDEX, -1))
                }
            }
        runCatching {
            ContextCompat.startForegroundService(context, serviceIntent)
        }.onFailure {
            WidgetUpdateCoordinator.requestUpdate(context)
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "btm.m.auraplayer.widget.ACTION_PLAY_PAUSE"
        const val ACTION_PREV = "btm.m.auraplayer.widget.ACTION_PREV"
        const val ACTION_NEXT = "btm.m.auraplayer.widget.ACTION_NEXT"
        const val ACTION_PLAY_QUEUE_INDEX = "btm.m.auraplayer.widget.ACTION_PLAY_QUEUE_INDEX"
        const val EXTRA_QUEUE_INDEX = "queue_index"

        val SUPPORTED_ACTIONS = setOf(
            ACTION_PLAY_PAUSE,
            ACTION_PREV,
            ACTION_NEXT,
            ACTION_PLAY_QUEUE_INDEX,
        )
    }
}
