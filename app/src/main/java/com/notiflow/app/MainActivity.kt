package com.lexlebeau.notiflow

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("notiflow", MODE_PRIVATE)

        val mode = prefs.getString("mode", null)

        if (mode == "sender") {
            startActivity(Intent(this, SenderActivity::class.java))
            finish()
            return
        } else if (mode == "receiver") {
            startActivity(Intent(this, ReceiverActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        if (!isNotificationServiceEnabled()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.senderButton).setOnClickListener {
            prefs.edit().putString("mode", "sender").apply()
            startActivity(Intent(this, SenderActivity::class.java))
            finish()
        }

        findViewById<Button>(R.id.receiverButton).setOnClickListener {
            prefs.edit().putString("mode", "receiver").apply()
            startActivity(Intent(this, ReceiverActivity::class.java))
            finish()
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }
}