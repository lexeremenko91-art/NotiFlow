package com.lexlebeau.notiflow

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReceiverService : Service() {

    private val channelId = "notiflow_status"
    private val notifChannelId = "notiflow_notifications"
    private var notificationId = 1000
    private lateinit var prefs: SharedPreferences
    private var isListening = false

    private var statusHandler: Handler? = null
    private var statusChecker: Runnable? = null
    private val receivedNotifKeys = mutableSetOf<String>()

    private var lastSenderTimestamp = 0L
    private var lastSenderBattery = -1

    private var receiverHeartbeatHandler: Handler? = null
    private var receiverHeartbeatRunnable: Runnable? = null

    private val clearAllReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.cancelAll()
            manager.notify(1, buildForegroundNotification(getString(R.string.status_connecting)))
        }
    }

    private fun startReceiverHeartbeat(pairCode: String) {
        receiverHeartbeatRunnable?.let { receiverHeartbeatHandler?.removeCallbacks(it) }
        receiverHeartbeatHandler = Handler(Looper.getMainLooper())
        receiverHeartbeatRunnable = object : Runnable {
            override fun run() {
                Firebase.database.reference
                    .child("pairs").child(pairCode).child("receiverOnline")
                    .setValue(System.currentTimeMillis())
                receiverHeartbeatHandler?.postDelayed(this, 60000)
            }
        }
        receiverHeartbeatHandler?.post(receiverHeartbeatRunnable!!)
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(
            clearAllReceiver,
            android.content.IntentFilter("com.lexlebeau.notiflow.CLEAR_ALL"),
            android.content.Context.RECEIVER_NOT_EXPORTED
        )
        prefs = getSharedPreferences("notiflow", MODE_PRIVATE)
        createNotificationChannel()
        startForeground(1, buildForegroundNotification("⏳ Подключение..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pairCode = prefs.getString("pairCode", null)
        if (pairCode != null && !isListening) {
            isListening = true
            listenForNotifications(pairCode)
            listenForSenderStatus(pairCode)
            startReceiverHeartbeat(pairCode)
            listenForOnlyMessengers(pairCode)
        }
        ReceiverWatchdogWorker.schedule(applicationContext)
        return START_REDELIVER_INTENT
    }

    private fun listenForOnlyMessengers(pairCode: String) {
        Firebase.database.reference
            .child("pairs").child(pairCode).child("commands").child("onlyMessengers")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val value = snapshot.getValue(Boolean::class.java) ?: false
                    prefs.edit().putBoolean("only_messengers", value).apply()
                    NotiFlowWidget.updateWidget(applicationContext)
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun listenForSenderStatus(pairCode: String) {
        val ref = Firebase.database.reference.child("pairs").child(pairCode).child("senderOnline")

        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                lastSenderTimestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                lastSenderBattery = snapshot.child("battery").getValue(Int::class.java) ?: -1
                updateStatus()
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        statusChecker?.let { statusHandler?.removeCallbacks(it) }
        statusHandler = Handler(Looper.getMainLooper())
        statusChecker = object : Runnable {
            override fun run() {
                updateStatus()
                statusHandler?.postDelayed(this, 65000)
            }
        }
        statusHandler?.postDelayed(statusChecker!!, 65000)
    }

    private fun updateStatus() {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putInt("sender_battery", lastSenderBattery)
            .putLong("sender_timestamp", lastSenderTimestamp)
            .apply()
        NotiFlowWidget.updateWidget(this)
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val status = if (lastSenderTimestamp > 0 && now - lastSenderTimestamp < 90000) {
            val timeStr = sdf.format(Date(lastSenderTimestamp))
            val batteryStr = if (lastSenderBattery >= 0) " 🔋 $lastSenderBattery%" else ""
            getString(R.string.status_online, timeStr) + batteryStr
        } else {
            getString(R.string.status_offline)
        }
        updateForegroundNotification(status)
    }

    private fun updateForegroundNotification(status: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, buildForegroundNotification(status))
    }

    private fun listenForNotifications(pairCode: String) {
        Firebase.database.reference
            .child("pairs")
            .child(pairCode)
            .child("notifications")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val snapKey = snapshot.key ?: return
                    if (!receivedNotifKeys.add(snapKey)) return

                    val aesKey = prefs.getString("aesKey", null)

                    val rawTitle = snapshot.child("title").getValue(String::class.java) ?: return
                    val rawText = snapshot.child("text").getValue(String::class.java) ?: ""
                    val rawAppName = snapshot.child("appName").getValue(String::class.java) ?: ""
                    val rawPackageName = snapshot.child("packageName").getValue(String::class.java) ?: ""
                    val packageName = if (aesKey != null) CryptoUtils.decrypt(rawPackageName, aesKey) else rawPackageName
                    val time = snapshot.child("time").getValue(Long::class.java) ?: 0L
                    val hasReply = snapshot.child("hasReply").getValue(Boolean::class.java) ?: false

                    val title = if (aesKey != null) CryptoUtils.decrypt(rawTitle, aesKey) else rawTitle
                    val text = if (aesKey != null) CryptoUtils.decrypt(rawText, aesKey) else rawText
                    val appName = if (aesKey != null) CryptoUtils.decrypt(rawAppName, aesKey) else rawAppName

                    // Баг 1 — игнорируем старые уведомления (старше 1 минуты)
                    if (time > 0 && System.currentTimeMillis() - time > 60 * 1000) {
                        Log.d("NotiFlow", "Старое уведомление — пропускаем: $title")
                        snapshot.ref.removeValue()
                        return
                    }

                    val geoEnabled = prefs.getBoolean("geo_enabled", false)
                    Log.d("NotiFlow", "Уведомление получено, geoEnabled=$geoEnabled")
                    if (geoEnabled) {
                        checkLocationAndShow(packageName, appName, title, text, hasReply, time)
                    } else {
                        showNotification(packageName, appName, title, text, hasReply)
                        saveToHistory(packageName, appName, title, text, time)
                    }
                    snapshot.ref.removeValue()
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun checkLocationAndShow(
        packageName: String,
        appName: String,
        title: String,
        text: String,
        hasReply: Boolean,
        time: Long
    ) {
        Log.d("NotiFlow", "checkLocationAndShow вызван")

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("NotiFlow", "Нет разрешения на локацию — показываем")
            showNotification(packageName, appName, title, text, hasReply)
            saveToHistory(packageName, appName, title, text, time)
            return
        }

        val homeLat = prefs.getFloat("home_lat", 0f).toDouble()
        val homeLon = prefs.getFloat("home_lon", 0f).toDouble()
        val radius = prefs.getInt("home_radius", 200).toFloat()
        Log.d("NotiFlow", "home: $homeLat,$homeLon radius=$radius")

        if (homeLat == 0.0 && homeLon == 0.0) {
            Log.d("NotiFlow", "Домашняя точка не установлена — показываем")
            showNotification(packageName, appName, title, text, hasReply)
            saveToHistory(packageName, appName, title, text, time)
            return
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location == null) {
                Log.d("NotiFlow", "lastLocation null — запрашиваем свежую")
                requestFreshLocation(fusedLocationClient, packageName, appName, title, text, hasReply, time, homeLat, homeLon, radius)
                return@addOnSuccessListener
            }

            val locationAge = System.currentTimeMillis() - location.time
            Log.d("NotiFlow", "locationAge=$locationAge ms")

            // Баг 2 — если локация устарела, запрашиваем свежую
            if (locationAge > 5 * 60 * 1000) {
                Log.d("NotiFlow", "Локация устарела — запрашиваем свежую")
                requestFreshLocation(fusedLocationClient, packageName, appName, title, text, hasReply, time, homeLat, homeLon, radius)
                return@addOnSuccessListener
            }

            val results = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, homeLat, homeLon, results)
            val distance = results[0]
            Log.d("NotiFlow", "distance=$distance м, radius=$radius м")

            if (distance > radius || isGeoExempt(packageName)) {
                Log.d("NotiFlow", "Вне дома или в исключениях — показываем")
                showNotification(packageName, appName, title, text, hasReply)
                saveToHistory(packageName, appName, title, text, time)
            } else {
                Log.d("NotiFlow", "Дома — скрываем")
                saveToHistory(packageName, appName, title, text, time)
            }
        }
    }

    private fun isGeoExempt(packageName: String): Boolean {
        val exemptSet = prefs.getStringSet("geo_exempt_apps", emptySet()) ?: emptySet()
        return exemptSet.contains(packageName)
    }

    private fun requestFreshLocation(
        fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient,
        packageName: String,
        appName: String,
        title: String,
        text: String,
        hasReply: Boolean,
        time: Long,
        homeLat: Double,
        homeLon: Double,
        radius: Float
    ) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10000
        ).setMaxUpdates(1).build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                    fusedLocationClient.removeLocationUpdates(this)
                    val freshLocation = result.lastLocation ?: run {
                        Log.d("NotiFlow", "Свежая локация null — показываем")
                        showNotification(packageName, appName, title, text, hasReply)
                        saveToHistory(packageName, appName, title, text, time)
                        return
                    }
                    val results = FloatArray(1)
                    Location.distanceBetween(freshLocation.latitude, freshLocation.longitude, homeLat, homeLon, results)
                    val distance = results[0]
                    Log.d("NotiFlow", "distance=$distance м, radius=$radius м")
                    if (distance > radius || isGeoExempt(packageName)) {
                        Log.d("NotiFlow", "Вне дома или в исключениях — показываем")
                        showNotification(packageName, appName, title, text, hasReply)
                        saveToHistory(packageName, appName, title, text, time)
                    } else {
                        Log.d("NotiFlow", "Дома — скрываем")
                        saveToHistory(packageName, appName, title, text, time)
                    }
                }
            },
            Looper.getMainLooper()
        )
    }

    private fun getAppIconBitmap(packageName: String): Bitmap? {
        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                val bitmap = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }
        } catch (e: PackageManager.NameNotFoundException) {
            try {
                val drawable = ContextCompat.getDrawable(this, R.mipmap.ic_launcher)!!
                val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun showNotification(
        packageName: String,
        appName: String,
        title: String,
        text: String,
        hasReply: Boolean
    ) {
        val icon = getAppIconBitmap(packageName)
        val aesKey = prefs.getString("aesKey", null)
        val pairCode = prefs.getString("pairCode", null)

        val builder = NotificationCompat.Builder(this, notifChannelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$appName: $title")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        if (icon != null) builder.setLargeIcon(icon)

        if (pairCode != null && hasReply) {
            val remoteInput = androidx.core.app.RemoteInput.Builder("reply_key")
                .setLabel("Ответить...")
                .build()

            val replyIntent = android.content.Intent(this, ReplyReceiver::class.java).apply {
                putExtra("notifId", notificationId)
                putExtra("packageName", packageName)
                putExtra("pairCode", pairCode)
                putExtra("aesKey", aesKey)
            }

            val replyPendingIntent = android.app.PendingIntent.getBroadcast(
                this, notificationId, replyIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )

            val replyAction = androidx.core.app.NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send, getString(R.string.btn_reply), replyPendingIntent
            ).addRemoteInput(remoteInput).build()

            builder.addAction(replyAction)
        }

        if (pairCode != null) {
            val dismissIntent = android.content.Intent(this, DismissReceiver::class.java).apply {
                putExtra("notifId", notificationId)
                putExtra("packageName", packageName)
                putExtra("pairCode", pairCode)
                putExtra("aesKey", aesKey)
            }

            val dismissPendingIntent = android.app.PendingIntent.getBroadcast(
                this, notificationId + 10000, dismissIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val dismissAction = androidx.core.app.NotificationCompat.Action.Builder(
                android.R.drawable.checkbox_on_background, "✓", dismissPendingIntent
            ).build()

            builder.addAction(dismissAction)
        }

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId++, builder.build())
    }

    private fun saveToHistory(
        packageName: String, appName: String, title: String, text: String, time: Long
    ) {
        val historyPrefs = getSharedPreferences("notiflow_history", MODE_PRIVATE)
        val key = "notif_${System.currentTimeMillis()}"
        val value = "$packageName|$title|$text|$time|$appName"
        historyPrefs.edit().putString(key, value).apply()

        val cutoff = System.currentTimeMillis() - 72 * 60 * 60 * 1000
        historyPrefs.all.forEach { (k, v) ->
            if (v is String) {
                val parts = v.split("|")
                if (parts.size >= 4) {
                    val t = parts[3].toLongOrNull() ?: 0L
                    if (t < cutoff) historyPrefs.edit().remove(k).apply()
                }
            }
        }
    }

    private fun buildForegroundNotification(status: String = getString(R.string.status_connecting)): android.app.Notification {
        val clearIntent = android.app.PendingIntent.getBroadcast(
            this, 0,
            android.content.Intent("com.lexlebeau.notiflow.CLEAR_ALL").apply {
                setPackage(packageName)
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NotiFlow")
            .setContentText(status)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_clear_all), clearIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val statusChannel = NotificationChannel(
            channelId, getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        statusChannel.setShowBadge(false)
        manager.createNotificationChannel(statusChannel)

        val notifChannel = NotificationChannel(
            notifChannelId, "NotiFlow уведомления",
            NotificationManager.IMPORTANCE_HIGH
        )
        notifChannel.enableVibration(true)
        notifChannel.enableLights(true)
        manager.createNotificationChannel(notifChannel)
    }

    override fun onDestroy() {
        super.onDestroy()
        statusChecker?.let { statusHandler?.removeCallbacks(it) }
        receiverHeartbeatRunnable?.let { receiverHeartbeatHandler?.removeCallbacks(it) }
        unregisterReceiver(clearAllReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Проверяет, запущен ли ReceiverService прямо сейчас (используется вотчдогом). */
        fun isServiceRunning(context: android.content.Context): Boolean {
            val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            return manager.getRunningServices(Integer.MAX_VALUE).any {
                it.service.className == ReceiverService::class.java.name
            }
        }
    }
}