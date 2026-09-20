package com.lexlebeau.notiflow

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Периодически проверяет, что ReceiverService жив, и перезапускает его если нет. */
class ReceiverWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("notiflow", Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", null)

        if (mode == "receiver" && PairedDevices.getAll(prefs).isNotEmpty() &&
            !ReceiverService.isServiceRunning(applicationContext)
        ) {
            val serviceIntent = Intent(applicationContext, ReceiverService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(applicationContext, serviceIntent)
        }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "receiver_watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReceiverWatchdogWorker>(15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
