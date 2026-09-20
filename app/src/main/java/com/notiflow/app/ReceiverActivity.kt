package com.lexlebeau.notiflow

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class ReceiverActivity : AppCompatActivity() {

    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private lateinit var devicesContainer: LinearLayout
    private lateinit var addSenderButton: Button
    private lateinit var prefs: android.content.SharedPreferences

    private val onlineListeners = mutableMapOf<String, ValueEventListener>()

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    private fun updateStartButton() {
        val isRunning = isServiceRunning(ReceiverService::class.java)
        startButton.text = if (isRunning) "⏹ Stop" else getString(R.string.btn_start)
        startButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (isRunning) R.color.color_accent_danger else R.color.color_accent_success)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)

        prefs = getSharedPreferences("notiflow", MODE_PRIVATE)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        devicesContainer = findViewById(R.id.devicesContainer)
        addSenderButton = findViewById(R.id.addSenderButton)
        val historyButton = findViewById<Button>(R.id.historyButton)
        val switchModeButton = findViewById<Button>(R.id.switchModeButton)

        addSenderButton.setOnClickListener {
            if (PairedDevices.getAll(prefs).size >= PairedDevices.MAX_DEVICES) {
                Toast.makeText(
                    this,
                    getString(R.string.device_limit_reached, PairedDevices.MAX_DEVICES),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                startActivity(Intent(this, PairActivity::class.java))
            }
        }

        historyButton.setOnClickListener {
            val devices = PairedDevices.getAll(prefs)
            if (devices.size > 1) {
                startActivity(Intent(this, HistoryDeviceListActivity::class.java))
            } else {
                val intent = Intent(this, HistoryActivity::class.java)
                devices.firstOrNull()?.let { intent.putExtra("pairCode", it.pairCode) }
                startActivity(intent)
            }
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

        renderDeviceList()
    }

    override fun onResume() {
        super.onResume()
        updateStartButton()
        if (isServiceRunning(ReceiverService::class.java)) {
            statusText.text = getString(R.string.receiver_status_ok)
        }
        renderDeviceList()
    }

    override fun onDestroy() {
        super.onDestroy()
        detachAllListeners()
    }

    private fun detachAllListeners() {
        onlineListeners.forEach { (pairCode, listener) ->
            Firebase.database.reference.child("pairs").child(pairCode).child("senderOnline")
                .removeEventListener(listener)
        }
        onlineListeners.clear()
    }

    private fun renderDeviceList() {
        detachAllListeners()
        devicesContainer.removeAllViews()

        val devices = PairedDevices.getAll(prefs)
        val primary = PairedDevices.getPrimary(prefs)

        if (devices.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = getString(R.string.no_devices_paired)
                setTextColor(ContextCompat.getColor(this@ReceiverActivity, R.color.color_text_disabled))
                textSize = 13f
            }
            devicesContainer.addView(emptyText)
            return
        }

        devices.forEach { device ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 16, 0, 16)
            }

            val primaryRadio = RadioButton(this).apply {
                isChecked = device.pairCode == primary
                setOnClickListener {
                    PairedDevices.setPrimary(prefs, device.pairCode)
                    NotiFlowWidget.updateWidget(this@ReceiverActivity)
                    renderDeviceList()
                }
            }
            row.addView(primaryRadio)

            val infoColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameText = TextView(this).apply {
                text = device.label
                setTextColor(ContextCompat.getColor(this@ReceiverActivity, R.color.color_text_primary))
                textSize = 15f
                setOnClickListener {
                    showDeviceLabelDialog(this@ReceiverActivity, device.label) { newLabel ->
                        PairedDevices.add(prefs, device.pairCode, device.aesKey, newLabel)
                        renderDeviceList()
                    }
                }
            }
            val statusTextView = TextView(this).apply {
                text = "…"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@ReceiverActivity, R.color.color_text_tertiary))
            }
            infoColumn.addView(nameText)
            infoColumn.addView(statusTextView)
            row.addView(infoColumn)

            val gearButton = Button(this).apply {
                text = "⚙️"
                textSize = 16f
                setPadding(0, 0, 0, 0)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@ReceiverActivity, R.color.color_surface)
                )
                layoutParams = LinearLayout.LayoutParams(120, 120)
                setOnClickListener {
                    val intent = Intent(this@ReceiverActivity, GeoSettingsActivity::class.java)
                    intent.putExtra("pairCode", device.pairCode)
                    startActivity(intent)
                }
            }
            row.addView(gearButton)

            val renameButton = Button(this).apply {
                text = "✏️"
                textSize = 16f
                setPadding(0, 0, 0, 0)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@ReceiverActivity, R.color.color_surface)
                )
                layoutParams = LinearLayout.LayoutParams(120, 120).apply { marginStart = 12 }
                setOnClickListener {
                    showDeviceLabelDialog(this@ReceiverActivity, device.label) { newLabel ->
                        PairedDevices.add(prefs, device.pairCode, device.aesKey, newLabel)
                        renderDeviceList()
                    }
                }
            }
            row.addView(renameButton)

            val unpairBtn = Button(this).apply {
                text = "✕"
                textSize = 16f
                setPadding(0, 0, 0, 0)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@ReceiverActivity, R.color.color_accent_danger_strong)
                )
                layoutParams = LinearLayout.LayoutParams(120, 120).apply { marginStart = 12 }
                setOnClickListener {
                    AlertDialog.Builder(this@ReceiverActivity)
                        .setTitle(getString(R.string.btn_unpair))
                        .setMessage(device.label)
                        .setPositiveButton(getString(R.string.btn_unpair)) { _, _ ->
                            PairedDevices.remove(prefs, device.pairCode)
                            val serviceIntent = Intent(this@ReceiverActivity, ReceiverService::class.java).apply {
                                action = ReceiverService.ACTION_UNPAIR_DEVICE
                                putExtra(ReceiverService.EXTRA_PAIR_CODE, device.pairCode)
                            }
                            ContextCompat.startForegroundService(this@ReceiverActivity, serviceIntent)
                            renderDeviceList()
                            NotiFlowWidget.updateWidget(this@ReceiverActivity)
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                }
            }
            row.addView(unpairBtn)

            devicesContainer.addView(row)

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val ts = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                    // Флаг online ведёт сам сендер через onDisconnect; для старых сендеров — по свежести пульса
                    val online = snapshot.child("online").getValue(Boolean::class.java)
                        ?: (System.currentTimeMillis() - ts < 90000)
                    statusTextView.text = if (online) "🟢 online" else "🔴 offline"
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            Firebase.database.reference
                .child("pairs").child(device.pairCode).child("senderOnline")
                .addValueEventListener(listener)
            onlineListeners[device.pairCode] = listener
        }
    }
}
