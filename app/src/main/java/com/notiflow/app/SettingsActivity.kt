package com.lexlebeau.notiflow

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.LocationServices
import android.util.Log
import android.view.View
import com.google.firebase.ktx.Firebase
import com.google.firebase.database.ktx.database

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("notiflow", MODE_PRIVATE)
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(32, 32, 32, 32)
        layout.setBackgroundColor(ContextCompat.getColor(this, R.color.color_background))
        scrollView.setBackgroundColor(ContextCompat.getColor(this, R.color.color_background))

        val title = TextView(this)
        title.text = getString(R.string.settings_title)
        title.textSize = 24f
        title.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary))
        title.setPadding(0, 0, 0, 32)
        layout.addView(title)

        // Переключатель "Не отправлять когда экран разблокирован"
        val screenTitle = TextView(this)
        screenTitle.text = "📱 " + getString(R.string.screen_filter_title)
        screenTitle.textSize = 18f
        screenTitle.setTextColor(ContextCompat.getColor(this, R.color.color_accent_primary))
        screenTitle.setPadding(0, 0, 0, 8)
        layout.addView(screenTitle)

        val screenDesc = TextView(this)
        screenDesc.text = getString(R.string.screen_filter_desc)
        screenDesc.textSize = 13f
        screenDesc.setTextColor(ContextCompat.getColor(this, R.color.color_text_tertiary))
        screenDesc.setPadding(0, 0, 0, 16)
        layout.addView(screenDesc)

        val screenSwitch = Switch(this)
        screenSwitch.text = getString(R.string.screen_filter_switch)
        screenSwitch.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary))
        screenSwitch.isChecked = prefs.getBoolean("block_when_unlocked", false)
        styleSwitch(this, screenSwitch)
        screenSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("block_when_unlocked", isChecked).apply()
        }
        layout.addView(screenSwitch)

        val divider = View(this)
        divider.setBackgroundColor(ContextCompat.getColor(this, R.color.color_divider))
        val dividerParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        dividerParams.setMargins(0, 24, 0, 24)
        divider.layoutParams = dividerParams
        layout.addView(divider)

        val messengersTitle = TextView(this)
        messengersTitle.text = "💬 " + getString(R.string.messengers_title)
        messengersTitle.textSize = 18f
        messengersTitle.setTextColor(ContextCompat.getColor(this, R.color.color_accent_primary))
        messengersTitle.setPadding(0, 0, 0, 8)
        layout.addView(messengersTitle)

        val messengersDesc = TextView(this)
        messengersDesc.text = getString(R.string.messengers_desc)
        messengersDesc.textSize = 13f
        messengersDesc.setTextColor(ContextCompat.getColor(this, R.color.color_text_tertiary))
        messengersDesc.setPadding(0, 0, 0, 16)
        layout.addView(messengersDesc)

        val messengersSwitch = Switch(this)
        messengersSwitch.text = getString(R.string.messengers_switch)
        messengersSwitch.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary))
        messengersSwitch.isChecked = prefs.getBoolean("only_messengers", false)
        styleSwitch(this, messengersSwitch)
        messengersSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("only_messengers", isChecked).apply()
            val pairCode = prefs.getString("pairCode", null)
            if (pairCode != null) {
                com.google.firebase.ktx.Firebase.database.reference
                    .child("pairs").child(pairCode).child("commands").child("onlyMessengers")
                    .setValue(isChecked)
            }
        }

        val pairCode = prefs.getString("pairCode", null)
        if (pairCode != null) {
            com.google.firebase.ktx.Firebase.database.reference
                .child("pairs").child(pairCode).child("commands").child("onlyMessengers")
                .addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                    override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                        val value = snapshot.getValue(Boolean::class.java) ?: false
                        messengersSwitch.setOnCheckedChangeListener(null) // снимаем listener чтобы не зациклиться
                        messengersSwitch.isChecked = value
                        prefs.edit().putBoolean("only_messengers", value).apply()
                        messengersSwitch.setOnCheckedChangeListener { _, isChecked ->
                            prefs.edit().putBoolean("only_messengers", isChecked).apply()
                            if (pairCode != null) {
                                com.google.firebase.ktx.Firebase.database.reference
                                    .child("pairs").child(pairCode).child("commands").child("onlyMessengers")
                                    .setValue(isChecked)
                            }
                        }
                    }
                    override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
                })
        }
        layout.addView(messengersSwitch)

        val divider2 = View(this)
        divider2.setBackgroundColor(ContextCompat.getColor(this, R.color.color_divider))
        val dividerParams2 = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        dividerParams2.setMargins(0, 24, 0, 24)
        divider2.layoutParams = dividerParams2
        layout.addView(divider2)

        // Фильтр приложений
        val filterTitle = TextView(this)
        filterTitle.text = getString(R.string.filter_title)
        filterTitle.textSize = 18f
        filterTitle.setTextColor(ContextCompat.getColor(this, R.color.color_accent_primary))
        filterTitle.setPadding(0, 0, 0, 8)
        layout.addView(filterTitle)

        val filterDesc = TextView(this)
        filterDesc.text = getString(R.string.filter_desc)
        filterDesc.textSize = 13f
        filterDesc.setTextColor(ContextCompat.getColor(this, R.color.color_text_tertiary))
        filterDesc.setPadding(0, 0, 0, 16)
        layout.addView(filterDesc)

        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .filter { it.packageName != "com.lexlebeau.notiflow" }
            .sortedBy { pm.getApplicationLabel(it).toString() }
        Log.d("NotiFlow", "Всего приложений в списке: ${apps.size}")

        val blockedSet = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()
        val sensitiveSet = prefs.getStringSet("sensitive_apps", emptySet()) ?: emptySet()

        apps.forEach { appInfo ->
            val appName = pm.getApplicationLabel(appInfo).toString()
            val packageName = appInfo.packageName

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, 8, 0, 8)

            val enableSwitch = Switch(this)
            enableSwitch.text = appName
            enableSwitch.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary))
            styleSwitch(this, enableSwitch)
            enableSwitch.isChecked = !blockedSet.contains(packageName)
            val switchParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            enableSwitch.layoutParams = switchParams

            val sensitiveBtn = Button(this)
            sensitiveBtn.text = if (sensitiveSet.contains(packageName)) getString(R.string.btn_hidden)
            else getString(R.string.btn_hide)
            sensitiveBtn.textSize = 11f
            sensitiveBtn.setPadding(16, 4, 16, 4)
            sensitiveBtn.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (sensitiveSet.contains(packageName)) R.color.white else R.color.color_accent_primary
                )
            )
            sensitiveBtn.setBackgroundColor(
                ContextCompat.getColor(
                    this,
                    if (sensitiveSet.contains(packageName)) R.color.color_accent_primary else R.color.color_surface
                )
            )

            enableSwitch.setOnCheckedChangeListener { _, isChecked ->
                val current = prefs.getStringSet("blocked_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                if (!isChecked) current.add(packageName) else current.remove(packageName)
                prefs.edit().putStringSet("blocked_apps", current).apply()
            }

            sensitiveBtn.setOnClickListener {
                val current = prefs.getStringSet("sensitive_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                if (current.contains(packageName)) {
                    current.remove(packageName)
                    sensitiveBtn.text = getString(R.string.btn_hide)
                    sensitiveBtn.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.color_accent_primary))
                    sensitiveBtn.setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.color_surface))
                } else {
                    current.add(packageName)
                    sensitiveBtn.text = getString(R.string.btn_hidden)
                    sensitiveBtn.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.white))
                    sensitiveBtn.setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.color_accent_primary))
                }
                prefs.edit().putStringSet("sensitive_apps", current).apply()
            }

            row.addView(enableSwitch)
            row.addView(sensitiveBtn)
            layout.addView(row)
        }

        scrollView.addView(layout)
        setContentView(scrollView)

        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}