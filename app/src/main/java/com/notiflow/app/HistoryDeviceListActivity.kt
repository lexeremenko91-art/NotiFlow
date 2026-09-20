package com.lexlebeau.notiflow

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Промежуточный экран выбора устройства перед показом истории уведомлений (при >1 спаренных сендерах). */
class HistoryDeviceListActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history_devices)

        val prefs = getSharedPreferences("notiflow", MODE_PRIVATE)
        val devicesContainer = findViewById<LinearLayout>(R.id.devicesContainer)
        val devices = PairedDevices.getAll(prefs)

        devices.forEach { device ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(24, 24, 24, 24)
                setBackgroundColor(ContextCompat.getColor(this@HistoryDeviceListActivity, R.color.color_surface))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16 }
                isClickable = true
                isFocusable = true
            }

            val label = TextView(this).apply {
                text = device.label
                setTextColor(ContextCompat.getColor(this@HistoryDeviceListActivity, R.color.color_text_primary))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)

            val chevron = TextView(this).apply {
                text = "›"
                setTextColor(ContextCompat.getColor(this@HistoryDeviceListActivity, R.color.color_text_tertiary))
                textSize = 20f
            }
            row.addView(chevron)

            row.setOnClickListener {
                startActivity(Intent(this, HistoryActivity::class.java).apply {
                    putExtra("pairCode", device.pairCode)
                    putExtra("deviceLabel", device.label)
                })
            }

            devicesContainer.addView(row)
        }
    }
}
