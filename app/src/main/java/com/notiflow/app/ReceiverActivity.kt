package com.lexlebeau.notiflow

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ReceiverActivity : AppCompatActivity() {

    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private lateinit var geoSwitch: Switch
    private lateinit var prefs: android.content.SharedPreferences

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    private fun updateStartButton() {
        val isRunning = isServiceRunning(ReceiverService::class.java)
        startButton.text = if (isRunning) "⏹ Stop" else getString(R.string.btn_start)
        startButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (isRunning) 0xFFE53935.toInt() else 0xFF43A047.toInt()
        )
    }

    private fun updateSwitchColors(isChecked: Boolean) {
        geoSwitch.thumbTintList = android.content.res.ColorStateList.valueOf(
            if (isChecked) 0xFF4CAF50.toInt() else 0xFF888888.toInt()
        )
        geoSwitch.trackTintList = android.content.res.ColorStateList.valueOf(
            if (isChecked) 0x884CAF50.toInt() else 0x88888888.toInt()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)

        prefs = getSharedPreferences("notiflow", MODE_PRIVATE)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        geoSwitch = findViewById(R.id.geoSwitch)
        val pairButton = findViewById<Button>(R.id.pairButton)
        val historyButton = findViewById<Button>(R.id.historyButton)
        val switchModeButton = findViewById<Button>(R.id.switchModeButton)
        val geoSettingsButton = findViewById<Button>(R.id.geoSettingsButton)

        geoSwitch.isChecked = prefs.getBoolean("geo_enabled", false)
        updateSwitchColors(geoSwitch.isChecked)

        geoSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("geo_enabled", isChecked).apply()
            updateSwitchColors(isChecked)
            NotiFlowWidget.updateWidget(this)
        }

        geoSettingsButton.setOnClickListener {
            startActivity(Intent(this, GeoSettingsActivity::class.java))
        }

        pairButton.setOnClickListener {
            startActivity(Intent(this, PairActivity::class.java))
        }

        historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        updateStartButton()

        startButton.setOnClickListener {
            if (isServiceRunning(ReceiverService::class.java)) {
                stopService(Intent(this, ReceiverService::class.java))
                statusText.text = getString(R.string.receiver_status_idle)
            } else {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                startForegroundService(Intent(this, ReceiverService::class.java))
                statusText.text = getString(R.string.receiver_status_ok)
            }
            updateStartButton()
        }

        switchModeButton.setOnClickListener {
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
        updateStartButton()
        geoSwitch.isChecked = prefs.getBoolean("geo_enabled", false)
        updateSwitchColors(geoSwitch.isChecked)
        if (isServiceRunning(ReceiverService::class.java)) {
            statusText.text = getString(R.string.receiver_status_ok)
        }
    }
}