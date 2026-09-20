package com.lexlebeau.notiflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Автозапускает ReceiverService после перезагрузки телефона, если устройство спарено как ресивер. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("notiflow", Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", null)

        if (mode == "receiver" && PairedDevices.getAll(prefs).isNotEmpty()) {
            val serviceIntent = Intent(context, ReceiverService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        ReceiverWatchdogWorker.schedule(context)
    }
}
