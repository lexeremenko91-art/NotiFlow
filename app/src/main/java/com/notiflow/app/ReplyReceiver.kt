package com.lexlebeau.notiflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence("reply_key")?.toString() ?: return

        val packageName = intent.getStringExtra("packageName") ?: return
        val pairCode = intent.getStringExtra("pairCode") ?: return
        val aesKey = intent.getStringExtra("aesKey")

        val encryptedReply = if (aesKey != null) CryptoUtils.encrypt(replyText, aesKey) else replyText
        val encryptedPackage = if (aesKey != null) CryptoUtils.encrypt(packageName, aesKey) else packageName

        val data = mapOf(
            "packageName" to encryptedPackage,
            "replyText" to encryptedReply,
            "time" to System.currentTimeMillis()
        )

        Firebase.database.reference
            .child("pairs")
            .child(pairCode)
            .child("replies")
            .push()
            .setValue(data)

        val notifId = intent.getIntExtra("notifId", -1)
        if (notifId != -1) {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager.cancel(notifId)
        }
    }
}