package com.lexlebeau.notiflow

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.LocationServices

class GeoSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var pairCode: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("notiflow", MODE_PRIVATE)

        val extraPairCode = intent.getStringExtra("pairCode")
        if (extraPairCode == null) {
            finish()
            return
        }
        pairCode = extraPairCode
        val device = PairedDevices.get(prefs, pairCode)

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(32, 32, 32, 32)
        layout.setBackgroundColor(ContextCompat.getColor(this, R.color.color_background))
        scrollView.setBackgroundColor(ContextCompat.getColor(this, R.color.color_background))
        scrollView.fitsSystemWindows = true

        val title = TextView(this)
        title.text = getString(R.string.geo_title)
        title.textSize = 24f
        title.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary))
        title.setPadding(0, 0, 0, 32)
        layout.addView(title)

        if (device != null) {
            val deviceLabel = TextView(this)
            deviceLabel.text = device.label
            deviceLabel.textSize = 15f
            deviceLabel.setTextColor(ContextCompat.getColor(this, R.color.color_accent_primary))
            deviceLabel.setPadding(0, 0, 0, 16)
            layout.addView(deviceLabel)
        }

        val geoDesc = TextView(this)
        geoDesc.text = getString(R.string.geo_desc)
        geoDesc.textSize = 13f
        geoDesc.setTextColor(ContextCompat.getColor(this, R.color.color_text_tertiary))
        geoDesc.setPadding(0, 0, 0, 16)
        layout.addView(geoDesc)

        val geoSwitch = Switch(this)
        geoSwitch.text = getString(R.string.geo_switch)
        geoSwitch.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary))
        geoSwitch.isChecked = prefs.getBoolean("geo_enabled_$pairCode", false)
        styleSwitch(this, geoSwitch)
        geoSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("geo_enabled_$pairCode", isChecked).apply()
        }
        geoSwitch.setPadding(0, 0, 0, 16)
        layout.addView(geoSwitch)

        val geoStatus = TextView(this)
        val lat = prefs.getFloat("home_lat_$pairCode", 0f)
        val lon = prefs.getFloat("home_lon_$pairCode", 0f)
        geoStatus.text = if (lat == 0f) getString(R.string.geo_no_location)
        else getString(R.string.geo_location, "%.4f".format(lat), "%.4f".format(lon))
        geoStatus.textSize = 13f
        geoStatus.setTextColor(ContextCompat.getColor(this, R.color.color_text_tertiary))
        geoStatus.setPadding(0, 8, 0, 8)
        layout.addView(geoStatus)

        val radiusLabel = TextView(this)
        val savedRadius = prefs.getInt("home_radius_$pairCode", 200)
        radiusLabel.text = getString(R.string.geo_radius, savedRadius)
        radiusLabel.textSize = 13f
        radiusLabel.setTextColor(ContextCompat.getColor(this, R.color.color_text_secondary))
        layout.addView(radiusLabel)

        val radiusSeek = SeekBar(this)
        radiusSeek.max = 900
        radiusSeek.progress = savedRadius - 100
        layout.addView(radiusSeek)

        val setHomeButton = Button(this)
        setHomeButton.text = getString(R.string.btn_set_home)
        setHomeButton.setBackgroundColor(ContextCompat.getColor(this, R.color.color_accent_primary))
        setHomeButton.setTextColor(ContextCompat.getColor(this, R.color.white))
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        btnParams.setMargins(0, 16, 0, 0)
        setHomeButton.layoutParams = btnParams
        layout.addView(setHomeButton)

        radiusSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val radius = progress + 100
                radiusLabel.text = getString(R.string.geo_radius, radius)
                prefs.edit().putInt("home_radius_$pairCode", radius).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        setHomeButton.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
                return@setOnClickListener
            }
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    prefs.edit()
                        .putFloat("home_lat_$pairCode", location.latitude.toFloat())
                        .putFloat("home_lon_$pairCode", location.longitude.toFloat())
                        .apply()
                    geoStatus.text = getString(R.string.geo_location,
                        "%.4f".format(location.latitude),
                        "%.4f".format(location.longitude))
                } else {
                    geoStatus.text = getString(R.string.geo_location_error)
                }
            }
        }

        val divider = View(this)
        divider.setBackgroundColor(ContextCompat.getColor(this, R.color.color_divider))
        val dividerParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        dividerParams.setMargins(0, 24, 0, 24)
        divider.layoutParams = dividerParams
        layout.addView(divider)

        // Исключения — приложения, уведомления от которых показываются даже дома
        val exemptTitle = TextView(this)
        exemptTitle.text = getString(R.string.geo_exempt_title)
        exemptTitle.textSize = 18f
        exemptTitle.setTextColor(ContextCompat.getColor(this, R.color.color_accent_primary))
        exemptTitle.setPadding(0, 0, 0, 8)
        layout.addView(exemptTitle)

        val exemptDesc = TextView(this)
        exemptDesc.text = getString(R.string.geo_exempt_desc)
        exemptDesc.textSize = 13f
        exemptDesc.setTextColor(ContextCompat.getColor(this, R.color.color_text_tertiary))
        exemptDesc.setPadding(0, 0, 0, 16)
        layout.addView(exemptDesc)

        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .filter { it.packageName != "com.lexlebeau.notiflow" }
            .sortedBy { pm.getApplicationLabel(it).toString() }

        val exemptSet = prefs.getStringSet("geo_exempt_apps_$pairCode", emptySet()) ?: emptySet()

        apps.forEach { appInfo ->
            val appName = pm.getApplicationLabel(appInfo).toString()
            val packageName = appInfo.packageName

            val exemptSwitch = Switch(this)
            exemptSwitch.text = appName
            exemptSwitch.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary))
            styleSwitch(this, exemptSwitch)
            exemptSwitch.isChecked = exemptSet.contains(packageName)
            exemptSwitch.setOnCheckedChangeListener { _, isChecked ->
                val current = prefs.getStringSet("geo_exempt_apps_$pairCode", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                if (isChecked) current.add(packageName) else current.remove(packageName)
                prefs.edit().putStringSet("geo_exempt_apps_$pairCode", current).apply()
            }
            layout.addView(exemptSwitch)
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