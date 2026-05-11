package com.lexlebeau.notiflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.util.Log
import com.google.firebase.ktx.Firebase
import com.google.firebase.database.ktx.database

class DismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra("notifId", -1)
        val packageName = intent.getStringExtra("packageName") ?: return
        val pairCode = intent.getStringExtra("pairCode") ?: return
        val aesKey = intent.getStringExtra("aesKey")

        // Закрываем уведомление на ресивере
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notifId)

        // Отправляем команду dismiss на сендер через Firebase
        val encryptedPackageName = if (aesKey != null) CryptoUtils.encrypt(packageName, aesKey) else packageName

        val data = mapOf(
            "packageName" to encryptedPackageName,
            "time" to System.currentTimeMillis()
        )

        Firebase.database.reference
            .child("pairs").child(pairCode).child("commands").child("dismiss")
            .push()
            .setValue(data)
            .addOnSuccessListener { Log.d("NotiFlow", "Dismiss отправлен для $packageName") }
            .addOnFailureListener { Log.e("NotiFlow", "Dismiss ошибка: ${it.message}") }
    }
}