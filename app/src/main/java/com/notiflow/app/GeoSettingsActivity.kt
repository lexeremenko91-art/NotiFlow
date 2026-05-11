package com.lexlebeau.notiflow

import android.Manifest
import android.content.SharedPreferences
import android.location.Location
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.LocationServices

class GeoSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("notiflow", MODE_PRIVATE)

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(32, 32, 32, 32)
        layout.setBackgroundColor(0xFF0A0A0A.toInt())
        scrollView.setBackgroundColor(0xFF0A0A0A.toInt())
        scrollView.fitsSystemWindows = true

        val title = TextView(this)
        title.text = getString(R.string.geo_title)
        title.textSize = 24f
        title.setTextColor(0xFFFFFFFF.toInt())
        title.setPadding(0, 0, 0, 32)
        layout.addView(title)

        val geoDesc = TextView(this)
        geoDesc.text = getString(R.string.geo_desc)
        geoDesc.textSize = 13f
        geoDesc.setTextColor(0xFF888888.toInt())
        geoDesc.setPadding(0, 0, 0, 16)
        layout.addView(geoDesc)

        val geoStatus = TextView(this)
        val lat = prefs.getFloat("home_lat", 0f)
        val lon = prefs.getFloat("home_lon", 0f)
        geoStatus.text = if (lat == 0f) getString(R.string.geo_no_location)
        else getString(R.string.geo_location, "%.4f".format(lat), "%.4f".format(lon))
        geoStatus.textSize = 13f
        geoStatus.setTextColor(0xFF888888.toInt())
        geoStatus.setPadding(0, 8, 0, 8)
        layout.addView(geoStatus)

        val radiusLabel = TextView(this)
        val savedRadius = prefs.getInt("home_radius", 200)
        radiusLabel.text = getString(R.string.geo_radius, savedRadius)
        radiusLabel.textSize = 13f
        radiusLabel.setTextColor(0xFFCCCCCC.toInt())
        layout.addView(radiusLabel)

        val radiusSeek = SeekBar(this)
        radiusSeek.max = 900
        radiusSeek.progress = savedRadius - 100
        layout.addView(radiusSeek)

        val setHomeButton = Button(this)
        setHomeButton.text = getString(R.string.btn_set_home)
        setHomeButton.setBackgroundColor(0xFF1E88E5.toInt())
        setHomeButton.setTextColor(0xFFFFFFFF.toInt())
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
                prefs.edit().putInt("home_radius", radius).apply()
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
                        .putFloat("home_lat", location.latitude.toFloat())
                        .putFloat("home_lon", location.longitude.toFloat())
                        .apply()
                    geoStatus.text = getString(R.string.geo_location,
                        "%.4f".format(location.latitude),
                        "%.4f".format(location.longitude))
                } else {
                    geoStatus.text = getString(R.string.geo_location_error)
                }
            }
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