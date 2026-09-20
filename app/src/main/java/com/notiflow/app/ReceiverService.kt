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

    // Circular buffer: держит не больше notificationIdRange одновременно активных
    // уведомлений от приложения независимо от числа сендеров — старые ID переиспользуются,
    // notify() на уже занятый ID просто заменяет прежнее уведомление.
    //
    // Важно: диапазон разбивается на непересекающиеся под-диапазоны ПО pairCode, а не общий
    // на всех. Если один и тот же ID переиспользовать между РАЗНЫМИ группами (setGroup),
    // notify() на этот ID молча переносит уведомление из одной группы в другую "на лету" —
    // это ломает группировку у Android/Wear OS (уведомление у пользователя как будто исчезает,
    // а часы вместо реального контента показывают только summary). Поэтому у каждого сендера —
    // свой изолированный кусок общего бюджета ID.
    private val notificationIdBase = 1000
    private val notificationIdRange = 50
    private val perDeviceNotificationCounter = mutableMapOf<String, Int>()
    private lateinit var prefs: SharedPreferences

    private fun nextNotificationId(pairCode: String): Int {
        val devicePairCodes = PairedDevices.getAll(prefs).map { it.pairCode }.sorted()
        val deviceIndex = devicePairCodes.indexOf(pairCode).coerceAtLeast(0)
        val deviceCount = devicePairCodes.size.coerceAtLeast(1)
        val slotsPerDevice = (notificationIdRange / deviceCount).coerceAtLeast(1)
        val rangeStart = notificationIdBase + deviceIndex * slotsPerDevice

        val counter = perDeviceNotificationCounter.getOrDefault(pairCode, 0)
        perDeviceNotificationCounter[pairCode] = counter + 1
        return rangeStart + (counter % slotsPerDevice)
    }

    private var statusHandler: Handler? = null
    private var statusChecker: Runnable? = null
    private val receivedNotifKeys = mutableSetOf<String>()

    private val registeredPairCodes = mutableSetOf<String>()
    private val senderTimestamps = mutableMapOf<String, Long>()
    private val senderBatteries = mutableMapOf<String, Int>()
    // Флаг online, который сендер ведёт через Firebase onDisconnect (нет ключа — старый сендер с пульсом)
    private val senderOnlineFlags = mutableMapOf<String, Boolean>()
    // Собственная связь ресивера с Firebase: без неё данные о сендере устарели, показываем offline
    private var firebaseConnected = true
    private var connectedListener: ValueEventListener? = null

    private val notificationListeners = mutableMapOf<String, ChildEventListener>()
    private val statusListeners = mutableMapOf<String, ValueEventListener>()
    private val messengersListeners = mutableMapOf<String, ValueEventListener>()

    private val receiverHeartbeatHandler = Handler(Looper.getMainLooper())
    private val receiverHeartbeatRunnables = mutableMapOf<String, Runnable>()

    private val clearAllReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val pairCode = intent?.getStringExtra(EXTRA_PAIR_CODE)
            val devices = PairedDevices.getAll(prefs)
            if (pairCode != null && devices.size > 1) {
                // Чистим только уведомления этого устройства (по его group), остальные не трогаем
                clearDeviceNotifications(pairCode)
            } else {
                manager.cancelAll()
            }
            updateAllStatusNotifications()
        }
    }

    /** Отменяет все активные уведомления (реальные + статус), принадлежащие группе этого устройства. */
    private fun clearDeviceNotifications(pairCode: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications.forEach { sbn ->
            if (sbn.notification.group == pairCode) manager.cancel(sbn.id)
        }
    }

    private fun startReceiverHeartbeat(pairCode: String) {
        val runnable = object : Runnable {
            override fun run() {
                Firebase.database.reference
                    .child("pairs").child(pairCode).child("receiverOnline")
                    .setValue(System.currentTimeMillis())
                receiverHeartbeatHandler.postDelayed(this, 60000)
            }
        }
        receiverHeartbeatRunnables[pairCode] = runnable
        receiverHeartbeatHandler.post(runnable)
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

        val primary = PairedDevices.getPrimary(prefs)
        val initial = if (primary != null) {
            buildStatusNotification(
                primary,
                multiDevice = PairedDevices.getAll(prefs).size > 1,
                overrideText = getString(R.string.status_connecting)
            )
        } else {
            buildPlaceholderNotification()
        }
        startForeground(1, initial)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UNPAIR_DEVICE) {
            intent.getStringExtra(EXTRA_PAIR_CODE)?.let { detachDevice(it) }
        }

        if (connectedListener == null) {
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) == true
                    if (connected != firebaseConnected) {
                        firebaseConnected = connected
                        updateAllStatusNotifications()
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            connectedListener = listener
            Firebase.database.reference.child(".info").child("connected").addValueEventListener(listener)
        }

        val devices = PairedDevices.getAll(prefs)
        devices.forEach { device ->
            if (registeredPairCodes.add(device.pairCode)) {
                listenForNotifications(device.pairCode)
                listenForSenderStatus(device.pairCode)
                startReceiverHeartbeat(device.pairCode)
                listenForOnlyMessengers(device.pairCode)
            }
        }
        updateAllStatusNotifications()
        ReceiverWatchdogWorker.schedule(applicationContext)
        return START_REDELIVER_INTENT
    }

    private fun detachDevice(pairCode: String) {
        registeredPairCodes.remove(pairCode)
        notificationListeners.remove(pairCode)?.let {
            Firebase.database.reference.child("pairs").child(pairCode).child("notifications")
                .removeEventListener(it)
        }
        statusListeners.remove(pairCode)?.let {
            Firebase.database.reference.child("pairs").child(pairCode).child("senderOnline")
                .removeEventListener(it)
        }
        messengersListeners.remove(pairCode)?.let {
            Firebase.database.reference.child("pairs").child(pairCode).child("commands").child("onlyMessengers")
                .removeEventListener(it)
        }
        receiverHeartbeatRunnables.remove(pairCode)?.let { receiverHeartbeatHandler.removeCallbacks(it) }
        senderTimestamps.remove(pairCode)
        senderBatteries.remove(pairCode)
        senderOnlineFlags.remove(pairCode)
        perDeviceNotificationCounter.remove(pairCode)

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(statusNotifId(pairCode))
        clearDeviceNotifications(pairCode)
        // Если primary сменился (отвязали как раз его), новый primary сразу займёт id=1
        updateAllStatusNotifications()
    }

    private fun listenForOnlyMessengers(pairCode: String) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val value = snapshot.getValue(Boolean::class.java) ?: false
                prefs.edit().putBoolean("only_messengers_$pairCode", value).apply()
                NotiFlowWidget.updateWidget(applicationContext)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        Firebase.database.reference
            .child("pairs").child(pairCode).child("commands").child("onlyMessengers")
            .addValueEventListener(listener)
        messengersListeners[pairCode] = listener
    }

    private fun listenForSenderStatus(pairCode: String) {
        val ref = Firebase.database.reference.child("pairs").child(pairCode).child("senderOnline")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                senderTimestamps[pairCode] = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                senderBatteries[pairCode] = snapshot.child("battery").getValue(Int::class.java) ?: -1
                val flag = snapshot.child("online").getValue(Boolean::class.java)
                if (flag != null) senderOnlineFlags[pairCode] = flag else senderOnlineFlags.remove(pairCode)
                updateAllStatusNotifications()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        statusListeners[pairCode] = listener

        if (statusChecker == null) {
            statusHandler = Handler(Looper.getMainLooper())
            statusChecker = object : Runnable {
                override fun run() {
                    updateAllStatusNotifications()
                    statusHandler?.postDelayed(this, 65000)
                }
            }
            statusHandler?.postDelayed(statusChecker!!, 65000)
        }
    }

    /** Онлайн = есть связь у нас самих И сендер отмечен online (Firebase onDisconnect);
     *  для старых сендеров без флага — по свежести timestamp пульса. */
    private fun isSenderOnline(pairCode: String): Boolean {
        if (!firebaseConnected) return false
        val flag = senderOnlineFlags[pairCode]
        if (flag != null) return flag
        val ts = senderTimestamps[pairCode] ?: 0L
        return ts > 0 && System.currentTimeMillis() - ts < 90000
    }

    private fun statusText(pairCode: String): String {
        val ts = senderTimestamps[pairCode] ?: 0L
        val battery = senderBatteries[pairCode] ?: -1
        return if (isSenderOnline(pairCode)) {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val timeStr = sdf.format(Date(ts))
            val batteryStr = if (battery >= 0) " 🔋 $battery%" else ""
            getString(R.string.status_online, timeStr) + batteryStr
        } else {
            getString(R.string.status_offline)
        }
    }

    /** Обновляет статус-уведомление каждого спаренного устройства независимо:
     *  primary становится foreground-уведомлением (id=1), остальные — обычными,
     *  сгруппированными со своими реальными уведомлениями через setGroup(pairCode). */
    private fun updateAllStatusNotifications() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val devices = PairedDevices.getAll(prefs)
        val multiDevice = devices.size > 1
        val primary = PairedDevices.getPrimary(prefs)

        if (devices.isEmpty()) {
            manager.notify(1, buildPlaceholderNotification())
        } else {
            // На случай если primary сменился — убираем возможный "хвост" от прошлого раза,
            // когда текущий primary ещё был обычным (не foreground) устройством.
            primary?.let { manager.cancel(statusNotifId(it)) }

            devices.forEach { device ->
                val isPrimary = device.pairCode == primary
                val notification = buildStatusNotification(device.pairCode, multiDevice)
                if (isPrimary) {
                    manager.notify(1, notification)
                } else {
                    manager.notify(statusNotifId(device.pairCode), notification)
                }
            }
        }

        prefs.edit()
            .putInt("sender_battery", primary?.let { senderBatteries[it] } ?: -1)
            .putLong("sender_timestamp", primary?.let { senderTimestamps[it] } ?: 0L)
            .putBoolean("sender_online", primary?.let { isSenderOnline(it) } ?: false)
            .apply()
        NotiFlowWidget.updateWidget(this)
    }

    private fun listenForNotifications(pairCode: String) {
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val snapKey = snapshot.key ?: return
                if (!receivedNotifKeys.add("$pairCode:$snapKey")) return

                val device = PairedDevices.get(prefs, pairCode) ?: run {
                    snapshot.ref.removeValue()
                    return
                }
                val aesKey = device.aesKey

                val rawTitle = snapshot.child("title").getValue(String::class.java) ?: return
                val rawText = snapshot.child("text").getValue(String::class.java) ?: ""
                val rawAppName = snapshot.child("appName").getValue(String::class.java) ?: ""
                val rawPackageName = snapshot.child("packageName").getValue(String::class.java) ?: ""
                val packageName = CryptoUtils.decrypt(rawPackageName, aesKey)
                val time = snapshot.child("time").getValue(Long::class.java) ?: 0L
                val hasReply = snapshot.child("hasReply").getValue(Boolean::class.java) ?: false

                val title = CryptoUtils.decrypt(rawTitle, aesKey)
                val text = CryptoUtils.decrypt(rawText, aesKey)
                val appName = CryptoUtils.decrypt(rawAppName, aesKey)

                // Баг 1 — игнорируем старые уведомления (старше 1 минуты)
                if (time > 0 && System.currentTimeMillis() - time > 60 * 1000) {
                    Log.d("NotiFlow", "Старое уведомление — пропускаем: $title")
                    snapshot.ref.removeValue()
                    return
                }

                val geoEnabled = prefs.getBoolean("geo_enabled_$pairCode", false)
                Log.d("NotiFlow", "Уведомление получено, geoEnabled=$geoEnabled")
                if (geoEnabled) {
                    checkLocationAndShow(device, packageName, appName, title, text, hasReply, time)
                } else {
                    showNotification(device, packageName, appName, title, text, hasReply)
                    saveToHistory(device, packageName, appName, title, text, time)
                }
                snapshot.ref.removeValue()
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        Firebase.database.reference
            .child("pairs")
            .child(pairCode)
            .child("notifications")
            .addChildEventListener(listener)
        notificationListeners[pairCode] = listener
    }

    private fun checkLocationAndShow(
        device: PairedDevice,
        packageName: String,
        appName: String,
        title: String,
        text: String,
        hasReply: Boolean,
        time: Long
    ) {
        Log.d("NotiFlow", "checkLocationAndShow вызван")
        val pairCode = device.pairCode

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("NotiFlow", "Нет разрешения на локацию — показываем")
            showNotification(device, packageName, appName, title, text, hasReply)
            saveToHistory(device, packageName, appName, title, text, time)
            return
        }

        val homeLat = prefs.getFloat("home_lat_$pairCode", 0f).toDouble()
        val homeLon = prefs.getFloat("home_lon_$pairCode", 0f).toDouble()
        val radius = prefs.getInt("home_radius_$pairCode", 200).toFloat()
        Log.d("NotiFlow", "home: $homeLat,$homeLon radius=$radius")

        if (homeLat == 0.0 && homeLon == 0.0) {
            Log.d("NotiFlow", "Домашняя точка не установлена — показываем")
            showNotification(device, packageName, appName, title, text, hasReply)
            saveToHistory(device, packageName, appName, title, text, time)
            return
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location == null) {
                Log.d("NotiFlow", "lastLocation null — запрашиваем свежую")
                requestFreshLocation(fusedLocationClient, device, packageName, appName, title, text, hasReply, time, homeLat, homeLon, radius)
                return@addOnSuccessListener
            }

            val locationAge = System.currentTimeMillis() - location.time
            Log.d("NotiFlow", "locationAge=$locationAge ms")

            // Баг 2 — если локация устарела, запрашиваем свежую
            if (locationAge > 5 * 60 * 1000) {
                Log.d("NotiFlow", "Локация устарела — запрашиваем свежую")
                requestFreshLocation(fusedLocationClient, device, packageName, appName, title, text, hasReply, time, homeLat, homeLon, radius)
                return@addOnSuccessListener
            }

            val results = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, homeLat, homeLon, results)
            val distance = results[0]
            Log.d("NotiFlow", "distance=$distance м, radius=$radius м")

            if (distance > radius || isGeoExempt(pairCode, packageName)) {
                Log.d("NotiFlow", "Вне дома или в исключениях — показываем")
                showNotification(device, packageName, appName, title, text, hasReply)
                saveToHistory(device, packageName, appName, title, text, time)
            } else {
                Log.d("NotiFlow", "Дома — скрываем")
                saveToHistory(device, packageName, appName, title, text, time)
            }
        }
    }

    private fun isGeoExempt(pairCode: String, packageName: String): Boolean {
        val exemptSet = prefs.getStringSet("geo_exempt_apps_$pairCode", emptySet()) ?: emptySet()
        return exemptSet.contains(packageName)
    }

    private fun requestFreshLocation(
        fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient,
        device: PairedDevice,
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
                        showNotification(device, packageName, appName, title, text, hasReply)
                        saveToHistory(device, packageName, appName, title, text, time)
                        return
                    }
                    val results = FloatArray(1)
                    Location.distanceBetween(freshLocation.latitude, freshLocation.longitude, homeLat, homeLon, results)
                    val distance = results[0]
                    Log.d("NotiFlow", "distance=$distance м, radius=$radius м")
                    if (distance > radius || isGeoExempt(device.pairCode, packageName)) {
                        Log.d("NotiFlow", "Вне дома или в исключениях — показываем")
                        showNotification(device, packageName, appName, title, text, hasReply)
                        saveToHistory(device, packageName, appName, title, text, time)
                    } else {
                        Log.d("NotiFlow", "Дома — скрываем")
                        saveToHistory(device, packageName, appName, title, text, time)
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

    /** Цвет сендера в шторке: по порядку среди спаренных (отсортированных по pairCode), разные у всех. */
    private fun senderColor(pairCode: String): Int {
        val index = PairedDevices.getAll(prefs).map { it.pairCode }.sorted().indexOf(pairCode).coerceAtLeast(0)
        val res = intArrayOf(
            R.color.color_sender_1, R.color.color_sender_2, R.color.color_sender_3,
            R.color.color_sender_4, R.color.color_sender_5
        )[index % 5]
        return ContextCompat.getColor(this, res)
    }

    /** ID статус-уведомления для НЕ-primary устройства (primary всегда id=1 — foreground). */
    private fun statusNotifId(pairCode: String) = 700000 + (pairCode.hashCode() and 0xFFFF)

    /** ID тихого group summary реальных уведомлений сендера (отдельно от статус-карточки). */
    private fun summaryNotifId(pairCode: String) = 800000 + (pairCode.hashCode() and 0xFFFF)

    /** Тихий summary группы сендера. Статус-карточка в группу НЕ входит: если сделать её summary,
     *  шторка прячет её за детьми и статус перестаёт быть виден. Не ongoing — система сама
     *  убирает summary, когда у группы не осталось детей. */
    private fun postGroupSummary(device: PairedDevice) {
        val summary = NotificationCompat.Builder(this, notifChannelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(device.label)
            .setSubText(device.label)
            .setColor(senderColor(device.pairCode))
            .setGroup(device.pairCode)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .build()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(summaryNotifId(device.pairCode), summary)
    }

    /** Статус-карточка устройства: ongoing-уведомление, при мульти-сендере — обычный (НЕ summary) член
     *  группы своего сендера, поднятый наверх (sortKey "0" + свежий when). Summary группы — отдельное
     *  тихое уведомление: содержимое summary One UI не показывает, статус в нём был бы невидим.
     *  В том же (alerting) канале, что и дети — иначе система разносит их по разным секциям шторки. */
    private fun buildStatusNotification(
        pairCode: String,
        multiDevice: Boolean,
        overrideText: String? = null
    ): android.app.Notification {
        val device = PairedDevices.get(prefs, pairCode)
        val text = overrideText ?: statusText(pairCode)
        val title = if (multiDevice && device != null) "NotiFlow — ${device.label}" else "NotiFlow"

        val clearIntent = android.app.PendingIntent.getBroadcast(
            this, statusNotifId(pairCode),
            android.content.Intent("com.lexlebeau.notiflow.CLEAR_ALL").apply {
                setPackage(packageName)
                putExtra(EXTRA_PAIR_CODE, pairCode)
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, if (multiDevice) notifChannelId else channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_clear_all), clearIntent)

        if (multiDevice) {
            builder.setGroup(pairCode)
            builder.setSortKey("0")
            builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            builder.setColor(senderColor(pairCode))
        }

        return builder.build()
    }

    /** Заглушка для случая, когда ни одного устройства ещё не спарено. */
    private fun buildPlaceholderNotification(): android.app.Notification {
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
            .setContentText(getString(R.string.status_connecting))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_clear_all), clearIntent)
            .build()
    }

    private fun showNotification(
        device: PairedDevice,
        packageName: String,
        appName: String,
        title: String,
        text: String,
        hasReply: Boolean
    ) {
        val multiDevice = PairedDevices.getAll(prefs).size > 1
        val icon = getAppIconBitmap(packageName)
        val aesKey = device.aesKey
        val pairCode = device.pairCode
        val notifId = nextNotificationId(pairCode)

        val displayTitle = if (multiDevice) "[${device.label}] $appName: $title" else "$appName: $title"

        val builder = NotificationCompat.Builder(this, notifChannelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(displayTitle)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        if (multiDevice) {
            builder.setGroup(pairCode)
            builder.setSortKey("1")
            builder.setSubText(device.label)
            builder.setColor(senderColor(pairCode))
        }

        if (icon != null) builder.setLargeIcon(icon)

        if (hasReply) {
            val remoteInput = androidx.core.app.RemoteInput.Builder("reply_key")
                .setLabel("Ответить...")
                .build()

            val replyIntent = android.content.Intent(this, ReplyReceiver::class.java).apply {
                putExtra("notifId", notifId)
                putExtra("packageName", packageName)
                putExtra("pairCode", pairCode)
                putExtra("aesKey", aesKey)
            }

            val replyPendingIntent = android.app.PendingIntent.getBroadcast(
                this, notifId, replyIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )

            val replyAction = androidx.core.app.NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send, getString(R.string.btn_reply), replyPendingIntent
            ).addRemoteInput(remoteInput).build()

            builder.addAction(replyAction)
        }

        val dismissIntent = android.content.Intent(this, DismissReceiver::class.java).apply {
            putExtra("notifId", notifId)
            putExtra("packageName", packageName)
            putExtra("pairCode", pairCode)
            putExtra("aesKey", aesKey)
        }

        val dismissPendingIntent = android.app.PendingIntent.getBroadcast(
            this, notifId + 10000, dismissIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val dismissAction = androidx.core.app.NotificationCompat.Action.Builder(
            android.R.drawable.checkbox_on_background, "✓", dismissPendingIntent
        ).build()

        builder.addAction(dismissAction)

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notifId, builder.build())

        if (multiDevice) {
            postGroupSummary(device)
            // Перепубликуем статусы последними: свежий when держит статус-карточку наверху группы
            updateAllStatusNotifications()
        }
    }

    private fun saveToHistory(
        device: PairedDevice, packageName: String, appName: String, title: String, text: String, time: Long
    ) {
        val historyPrefs = getSharedPreferences("notiflow_history", MODE_PRIVATE)
        val key = "notif_${System.currentTimeMillis()}"
        val value = "$packageName|$title|$text|$time|$appName|${device.pairCode}"
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
        connectedListener?.let { Firebase.database.reference.child(".info").child("connected").removeEventListener(it) }
        connectedListener = null
        receiverHeartbeatRunnables.values.forEach { receiverHeartbeatHandler.removeCallbacks(it) }
        unregisterReceiver(clearAllReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_UNPAIR_DEVICE = "com.lexlebeau.notiflow.UNPAIR_DEVICE"
        const val EXTRA_PAIR_CODE = "pairCode"

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
