package com.lexlebeau.notiflow

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SenderActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sender)

        statusText = findViewById(R.id.statusText)
        val pairButton = findViewById<Button>(R.id.pairButton)
        val settingsButton = findViewById<Button>(R.id.settingsButton)
        val switchModeButton = findViewById<Button>(R.id.switchModeButton)

        pairButton.setOnClickListener {
            startActivity(Intent(this, PairActivity::class.java))
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        switchModeButton.setOnClickListener {
            val prefs = getSharedPreferences("notiflow", MODE_PRIVATE)
            prefs.edit().clear().apply()
            getSharedPreferences("notiflow_history", MODE_PRIVATE).edit().clear().apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        val privacyButton = findViewById<Button>(R.id.privacyButton)
        privacyButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = android.net.Uri.parse("https://telegra.ph/Privacy-Policy-for-NotiFlow-05-08")
            startActivity(intent)
        }

        val donateButton = findViewById<Button>(R.id.donateButton)
        donateButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = android.net.Uri.parse("https://buymeacoffee.com/lexlebeau")
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        statusText.text = if (isNotificationServiceEnabled())
            getString(R.string.sender_status_ok)
        else
            getString(R.string.sender_status_err)
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }
}