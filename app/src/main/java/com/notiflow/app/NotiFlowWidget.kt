package com.lexlebeau.notiflow

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.google.firebase.ktx.Firebase
import com.google.firebase.database.ktx.database

class NotiFlowWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_PLAY = "com.lexlebeau.notiflow.TOGGLE_PLAY"
        const val ACTION_TOGGLE_MESSENGERS = "com.lexlebeau.notiflow.TOGGLE_MESSENGERS"
        const val ACTION_TOGGLE_GEO = "com.lexlebeau.notiflow.TOGGLE_GEO"

        fun updateWidget(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NotiFlowWidget::class.java))
            ids.forEach { updateAppWidget(context, manager, it) }
        }

        fun updateAppWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences("notiflow", Context.MODE_PRIVATE)
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            val primaryPairCode = PairedDevices.getPrimary(prefs)

            // Пауза/Плей
            val isRunning = isServiceRunning(context, ReceiverService::class.java)
            views.setImageViewResource(
                R.id.btnPlay,
                if (isRunning) R.drawable.ic_widget_play else R.drawable.ic_widget_pause
            )

            // Только мессенджеры (для основного устройства)
            val onlyMessengers = primaryPairCode != null &&
                    prefs.getBoolean("only_messengers_$primaryPairCode", false)
            views.setInt(R.id.btnMessengers, "setColorFilter",
                if (onlyMessengers) 0xFF2196F3.toInt() else 0xFF888888.toInt())
            Log.d("NotiFlow", "Widget updateAppWidget: only_messengers=$onlyMessengers")

            // Геофенсинг (для основного устройства)
            val geoEnabled = primaryPairCode != null &&
                    prefs.getBoolean("geo_enabled_$primaryPairCode", false)
            views.setInt(R.id.btnGeo, "setColorFilter",
                if (geoEnabled) 0xFF2196F3.toInt() else 0xFF888888.toInt())

            // Батарея
            val battery = prefs.getInt("sender_battery", -1)
            // sender_online пишет ReceiverService; если он остановлен — данные не обновляются, считаем офлайн
            val isOnline = isRunning && prefs.getBoolean("sender_online", false)
            views.setInt(R.id.batteryIcon, "setColorFilter",
                when {
                    !isOnline -> 0xFF888888.toInt()
                    battery < 20 -> 0xFFE53935.toInt()
                    else -> 0xFF4CAF50.toInt()
                })
            views.setTextViewText(R.id.batteryText,
                if (isOnline && battery >= 0) "$battery%" else "--")

            // Тап на батарейку открывает приложение
            val openAppIntent = Intent(context, ReceiverActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnBattery, openAppPendingIntent)

            // PendingIntents
            views.setOnClickPendingIntent(R.id.btnPlay, getPendingIntent(context, ACTION_TOGGLE_PLAY))
            views.setOnClickPendingIntent(R.id.btnMessengers, getPendingIntent(context, ACTION_TOGGLE_MESSENGERS))
            views.setOnClickPendingIntent(R.id.btnGeo, getPendingIntent(context, ACTION_TOGGLE_GEO))

            manager.updateAppWidget(widgetId, views)
        }

        private fun getPendingIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, NotiFlowWidget::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            return manager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == serviceClass.name }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateAppWidget(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val prefs = context.getSharedPreferences("notiflow", Context.MODE_PRIVATE)

        when (intent.action) {
            ACTION_TOGGLE_PLAY -> {
                val isRunning = isServiceRunning(context, ReceiverService::class.java)
                if (isRunning) {
                    context.stopService(Intent(context, ReceiverService::class.java))
                } else {
                    context.startForegroundService(Intent(context, ReceiverService::class.java))
                }
            }
            ACTION_TOGGLE_MESSENGERS -> {
                val pairCode = PairedDevices.getPrimary(prefs) ?: return
                val current = prefs.getBoolean("only_messengers_$pairCode", false)
                val newValue = !current
                prefs.edit().putBoolean("only_messengers_$pairCode", newValue).apply()
                com.google.firebase.ktx.Firebase.database.reference
                    .child("pairs").child(pairCode).child("commands").child("onlyMessengers")
                    .setValue(newValue)
            }
            ACTION_TOGGLE_GEO -> {
                val pairCode = PairedDevices.getPrimary(prefs) ?: return
                val current = prefs.getBoolean("geo_enabled_$pairCode", false)
                prefs.edit().putBoolean("geo_enabled_$pairCode", !current).apply()
            }
        }
        updateWidget(context)
    }
}