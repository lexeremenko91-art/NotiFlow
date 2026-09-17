package com.lexlebeau.notiflow

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class SenderService : NotificationListenerService() {

    private val database = Firebase.database.reference
    private lateinit var prefs: SharedPreferences
    private val recentNotifications = mutableMapOf<String, Long>()
    private var heartbeatHandler: Handler? = null
    private var heartbeatRunnable: Runnable? = null
    private val currentActions = mutableMapOf<String, Array<android.app.Notification.Action>>()

    private val systemBlockedPackages = setOf(
        "com.lexlebeau.notiflow",
        "android",
        "com.android.systemui",
        "com.android.server.telecom",
        "com.samsung.android.app.aodservice",
        "com.sec.android.app.launcher"
    )

    private var prefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** pairCode, на который уже оформлены Firebase-подписки — защищает от повторной регистрации листенеров. */
    private var subscribedPairCode: String? = null

    /** Подписывается на Firebase для данного pairCode один раз; повторный вызов с тем же кодом — no-op. */
    private fun subscribeToPair(pairCode: String) {
        if (subscribedPairCode == pairCode) return
        subscribedPairCode = pairCode
        startHeartbeat(pairCode)
        startReplyListener(pairCode)
        startDismissListener(pairCode)
        startOnlyMessengersListener(pairCode)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("notiflow", MODE_PRIVATE)
        Log.d("NotiFlow", "SenderService onCreate")
        val mode = prefs.getString("mode", "sender")
        if (mode != "sender") return

        val pairCode = prefs.getString("pairCode", null)
        if (pairCode != null) {
            subscribeToPair(pairCode)
        }
        prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "pairCode") {
                val newPairCode = prefs.getString("pairCode", null)
                if (newPairCode != null) {
                    subscribeToPair(newPairCode)
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("NotiFlow", "SenderService connected")
        val mode = prefs.getString("mode", "sender")
        if (mode != "sender") return

        val pairCode = prefs.getString("pairCode", null)
        if (pairCode != null) {
            subscribeToPair(pairCode)
        }
    }
    private fun getBatteryLevel(): Int {
        val intent = registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    private fun startDismissListener(pairCode: String) {
        Log.d("NotiFlow", "startDismissListener запущен для $pairCode")
        database.child("pairs").child(pairCode).child("commands").child("dismiss")
            .addChildEventListener(object : com.google.firebase.database.ChildEventListener {
                override fun onChildAdded(
                    snapshot: com.google.firebase.database.DataSnapshot,
                    previousChildName: String?
                ) {
                    val aesKey = prefs.getString("aesKey", null)
                    val rawPackageName = snapshot.child("packageName").getValue(String::class.java) ?: return
                    val packageName = if (aesKey != null) CryptoUtils.decrypt(rawPackageName, aesKey) else rawPackageName

                    Log.d("NotiFlow", "Dismiss получен для $packageName")
                    dismissNotification(packageName)
                    snapshot.ref.removeValue()
                }

                override fun onChildChanged(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: com.google.firebase.database.DataSnapshot) {}
                override fun onChildMoved(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })
    }
    private fun startOnlyMessengersListener(pairCode: String) {
        Log.d("NotiFlow", "startOnlyMessengersListener запущен для $pairCode")
        database.child("pairs").child(pairCode).child("commands").child("onlyMessengers")
            .addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    val value = snapshot.getValue(Boolean::class.java) ?: false
                    prefs.edit().putBoolean("only_messengers", value).apply()
                    Log.d("NotiFlow", "onlyMessengers обновлён: $value")
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })
    }

    private fun dismissNotification(packageName: String) {
        val activeNotifs = activeNotifications ?: return
        activeNotifs.filter { it.packageName == packageName }.forEach {
            cancelNotification(it.key)
            Log.d("NotiFlow", "Уведомление отменено для $packageName")
        }
    }

    private fun startHeartbeat(pairCode: String?) {
        if (pairCode == null) return
        heartbeatRunnable?.let { heartbeatHandler?.removeCallbacks(it) }
        heartbeatHandler = Handler(Looper.getMainLooper())
        heartbeatRunnable = object : Runnable {
            override fun run() {
                val battery = getBatteryLevel()
                val data = mapOf(
                    "timestamp" to System.currentTimeMillis(),
                    "battery" to battery
                )
                database.child("pairs").child(pairCode).child("senderOnline")
                    .setValue(data)
                    .addOnSuccessListener { Log.d("NotiFlow", "Heartbeat отправлен, battery: $battery%") }
                    .addOnFailureListener { Log.e("NotiFlow", "Heartbeat ошибка: ${it.message}") }
                heartbeatHandler?.postDelayed(this, 60000)
            }
        }
        heartbeatHandler?.post(heartbeatRunnable!!)
    }

    private fun startReplyListener(pairCode: String) {
        Log.d("NotiFlow", "startReplyListener запущен для $pairCode")
        database.child("pairs").child(pairCode).child("replies")
            .addChildEventListener(object : com.google.firebase.database.ChildEventListener {
                override fun onChildAdded(
                    snapshot: com.google.firebase.database.DataSnapshot,
                    previousChildName: String?
                ) {
                    Log.d("NotiFlow", "onChildAdded сработал: ${snapshot.key}")
                    val aesKey = prefs.getString("aesKey", null)
                    val rawPackageName = snapshot.child("packageName").getValue(String::class.java) ?: return
                    val packageName = if (aesKey != null) CryptoUtils.decrypt(rawPackageName, aesKey) else rawPackageName
                    val rawReplyText = snapshot.child("replyText").getValue(String::class.java) ?: return
                    val replyText = if (aesKey != null) CryptoUtils.decrypt(rawReplyText, aesKey) else rawReplyText

                    Log.d("NotiFlow", "Получен reply для $packageName: $replyText")
                    sendReply(packageName, replyText)
                    snapshot.ref.removeValue()
                }

                override fun onChildChanged(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: com.google.firebase.database.DataSnapshot) {}
                override fun onChildMoved(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })
    }

    private fun sendReply(packageName: String, replyText: String) {
        val activeNotifications = activeNotifications ?: run {
            Log.e("NotiFlow", "activeNotifications недоступен")
            return
        }

        val sbn = activeNotifications.filter { it.packageName == packageName }
            .firstOrNull { it.notification.actions?.any { a -> a.remoteInputs?.isNotEmpty() == true } == true }
            ?: run {
                Log.e("NotiFlow", "Нет активного уведомления для $packageName")
                return
            }

        val replyAction = sbn.notification.actions
            .firstOrNull { it.remoteInputs?.isNotEmpty() == true } ?: return

        val remoteInput = replyAction.remoteInputs.first()
        val intent = android.content.Intent()
        val bundle = android.os.Bundle()
        bundle.putCharSequence(remoteInput.resultKey, replyText)
        android.app.RemoteInput.addResultsToIntent(replyAction.remoteInputs, intent, bundle)

        try {
            replyAction.actionIntent.send(this, 0, intent)
            Log.d("NotiFlow", "Ответ отправлен: $replyText")
        } catch (e: Exception) {
            Log.e("NotiFlow", "Ошибка отправки ответа: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val mode = prefs.getString("mode", "sender")
        if (mode != "sender") return

        sbn.notification.actions?.let { currentActions[sbn.packageName] = it }
        val pairCode = prefs.getString("pairCode", null) ?: return
        val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val screenOn = powerManager.isInteractive
        val blockWhenUnlocked = prefs.getBoolean("block_when_unlocked", false)
        if (blockWhenUnlocked && screenOn) return
        val onlyMessengers = prefs.getBoolean("only_messengers", false)
        if (onlyMessengers) {
            val category = sbn.notification.category
            if (category != android.app.Notification.CATEGORY_MESSAGE &&
                category != android.app.Notification.CATEGORY_SOCIAL) return
        }
        val packageName = sbn.packageName
        val extras = sbn.notification.extras

        // Пропускаем ongoing уведомления (прогресс, звонки и т.д.)
        if (sbn.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) return

        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        if (title.isEmpty() && text.isEmpty()) return

        if (packageName in systemBlockedPackages) {
            val isLowBattery = text.contains("1%") || text.contains("2%") ||
                    text.contains("3%") || text.contains("4%") || text.contains("5%") ||
                    text.contains("10%") || text.contains("15%") || text.contains("20%") ||
                    title.lowercase().contains("low battery") ||
                    title.lowercase().contains("низкий заряд")
            if (!isLowBattery) return
        }

        if (title.lowercase().contains("charging") ||
            text.lowercase().contains("until full") ||
            text.lowercase().contains("до полного")
        ) return

        val blockedApps = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()
        if (blockedApps.contains(packageName)) return

        // Дедупликация групповых уведомлений — берём только последнее из группы
        val groupKey = sbn.notification.group
        val key = if (groupKey != null) "$packageName|$groupKey" else "$packageName|$title|$text"
        val now = System.currentTimeMillis()
        val lastSent = recentNotifications[key] ?: 0L
        if (now - lastSent < 3000) {
            Log.d("NotiFlow", "Дубль, пропускаем: $title")
            return
        }
        recentNotifications[key] = now
        recentNotifications.entries.removeAll { now - it.value > 10000 }

        val hasReply = sbn.notification.actions?.any { action ->
            action.remoteInputs?.isNotEmpty() == true ||
                    action.title?.toString()?.lowercase()?.let {
                        it.contains("reply") ||
                                it.contains("ответ") ||
                                it.contains("отвечать") ||
                                it.contains("ответить")
                    } == true
        } == true

        sendNotification(packageName, title, text, pairCode, hasReply)
    }

    private fun sendNotification(
        packageName: String,
        title: String,
        text: String,
        pairCode: String,
        hasReply: Boolean
    ) {
        val sensitiveApps = prefs.getStringSet("sensitive_apps", emptySet()) ?: emptySet()
        val isSensitive = sensitiveApps.contains(packageName)

        val appName = try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }

        val aesKey = prefs.getString("aesKey", null)

        val encryptedTitle = if (aesKey != null) CryptoUtils.encrypt(
            if (isSensitive) "Новое уведомление" else title, aesKey
        ) else title

        val encryptedText = if (aesKey != null) CryptoUtils.encrypt(
            if (isSensitive) "Содержимое скрыто" else text, aesKey
        ) else text

        val encryptedPackageName = if (aesKey != null) CryptoUtils.encrypt(packageName, aesKey) else packageName
        val encryptedAppName = if (aesKey != null) CryptoUtils.encrypt(appName, aesKey) else appName

        val data = mapOf(
            "packageName" to encryptedPackageName,
            "appName" to encryptedAppName,
            "title" to encryptedTitle,
            "text" to encryptedText,
            "time" to System.currentTimeMillis(),
            "hasReply" to hasReply
        )

        database
            .child("pairs")
            .child(pairCode)
            .child("notifications")
            .push()
            .setValue(data)
            .addOnSuccessListener { Log.d("NotiFlow", "Записано: $appName — $title") }
            .addOnFailureListener { Log.e("NotiFlow", "Ошибка: ${it.message}") }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    override fun onDestroy() {
        super.onDestroy()
        heartbeatRunnable?.let { heartbeatHandler?.removeCallbacks(it) }
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }
}