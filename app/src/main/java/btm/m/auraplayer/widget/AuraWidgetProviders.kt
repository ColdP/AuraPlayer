// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

internal const val ACTION_MIUI_APPWIDGET_UPDATE = "miui.appwidget.action.APPWIDGET_UPDATE"

open class BaseAuraWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MIUI_APPWIDGET_UPDATE) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            if (ids != null && ids.isNotEmpty()) {
                onUpdate(context, appWidgetManager, ids)
            } else {
                WidgetUpdateCoordinator.requestUpdate(context)
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetUpdateCoordinator.requestUpdate(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        if (newOptions.getBoolean("miuiIdChanged", false) &&
            !newOptions.getBoolean("miuiIdChangedComplete", false)
        ) {
            newOptions.putBoolean("miuiIdChangedComplete", true)
            appWidgetManager.updateAppWidgetOptions(appWidgetId, newOptions)
        }
        WidgetUpdateCoordinator.requestUpdate(context)
    }
}

class AuraPlayerWidget2x2Provider : BaseAuraWidgetProvider()

class AuraPlayerWidget4x1Provider : BaseAuraWidgetProvider()

class AuraPlayerWidgetProvider : BaseAuraWidgetProvider()

class AuraPlayerWidget4x3Provider : BaseAuraWidgetProvider()

class AuraPlayerWidget4x4Provider : BaseAuraWidgetProvider()

class AuraCoverWidgetProvider : BaseAuraWidgetProvider()
