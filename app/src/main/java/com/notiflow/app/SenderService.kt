package com.lexlebeau.notiflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class SenderService : NotificationListenerService() {

    private val database = Firebase.database.reference
    private lateinit var prefs: SharedPreferences
    private val recentNotifications = mutableMapOf<String, Long>()
    // Presence: online/offline определяет сам Firebase-сервер (onDisconnect), без таймеров и периодических записей.
    private var presenceRef: DatabaseReference? = null
    private var connectedListener: ValueEventListener? = null
    private var isConnected = false
    private var lastPublishedBattery = -2
    private var batteryReceiverRegistered = false
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

    /** Ключевые слова-фолбэк для мессенджеров, которые не проставляют CATEGORY_CALL верно. */
    private val callKeywords = listOf(
        "calling", "incoming call", "video call", "voice call",
        "звонит", "входящий вызов", "видеозвонок", "аудиозвонок"
    )

    /** Определяет, что уведомление — о звонке (нативном или из мессенджера), шире чем просто CATEGORY_CALL. */
    private fun isCallNotification(sbn: StatusBarNotification, title: String, text: String): Boolean {
        if (sbn.notification.category == android.app.Notification.CATEGORY_CALL) return true
        val combined = "$title $text".lowercase()
        return callKeywords.any { combined.contains(it) }
    }

    /** pairCode, на который уже оформлены Firebase-подписки — защищает от повторной регистрации листенеров. */
    private var subscribedPairCode: String? = null

    /** Подписывается на Firebase для данного pairCode один раз; повторный вызов с тем же кодом — no-op. */
    private fun subscribeToPair(pairCode: String) {
        if (subscribedPairCode == pairCode) return
        subscribedPairCode = pairCode
        startPresence(pairCode)
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

    /**
     * Presence вместо пульса раз в минуту: при каждом (пере)подключении к Firebase пишем online=true,
     * а сервер сам выставит online=false, когда соединение оборвётся (onDisconnect). Никаких таймеров,
     * которые Doze может задержать, и никакого периодического трафика.
     */
    private fun startPresence(pairCode: String) {
        stopPresence()
        val ref = database.child("pairs").child(pairCode).child("senderOnline")
        presenceRef = ref

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                isConnected = snapshot.getValue(Boolean::class.java) == true
                if (!isConnected) return
                // Сначала регистрируем на сервере действие при обрыве, потом объявляем себя онлайн
                ref.onDisconnect()
                    .updateChildren(mapOf("online" to false, "timestamp" to ServerValue.TIMESTAMP))
                    .addOnSuccessListener { publishPresence() }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        connectedListener = listener
        database.root.child(".info").child("connected").addValueEventListener(listener)

        if (!batteryReceiverRegistered) {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiverRegistered = true
        }
    }

    private fun publishPresence() {
        val battery = getBatteryLevel()
        lastPublishedBattery = battery
        presenceRef?.updateChildren(
            mapOf("online" to true, "battery" to battery, "timestamp" to ServerValue.TIMESTAMP)
        )
            ?.addOnSuccessListener { Log.d("NotiFlow", "Presence: online, battery: $battery%") }
            ?.addOnFailureListener { Log.e("NotiFlow", "Presence ошибка: ${it.message}") }
    }

    private fun stopPresence() {
        connectedListener?.let { database.root.child(".info").child("connected").removeEventListener(it) }
        connectedListener = null
        presenceRef?.onDisconnect()?.cancel()
        presenceRef = null
        isConnected = false
    }

    /** Пишет батарею только при смене процента и только когда есть связь — редкие и крошечные записи. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ref = presenceRef ?: return
            if (!isConnected) return
            val level = getBatteryLevel()
            if (level < 0 || level == lastPublishedBattery) return
            lastPublishedBattery = level
            ref.updateChildren(mapOf("battery" to level, "timestamp" to ServerValue.TIMESTAMP))
        }
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

        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        // Пропускаем ongoing уведомления (прогресс загрузки, VK-звонки-спам и т.д.) —
        // кроме входящих звонков: мессенджеры (VK, Telegram, WhatsApp, Teams...) тоже
        // помечают их FLAG_ONGOING_EVENT, и без исключения звонки просто пропадали.
        val isOngoing = sbn.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0
        val isCall = isCallNotification(sbn, title, text)
        if (isOngoing && !isCall) return

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

        // Дедупликация: для звонков — по packageName+title без текста (мессенджеры могут
        // переотправлять ongoing-уведомление звонка с меняющимся текстом, например тикающим
        // таймером, но это тот же самый звонок — иначе долетал бы дубль на каждый апдейт).
        // Для остальных групповых уведомлений — берём только последнее из группы.
        val groupKey = sbn.notification.group
        val key = when {
            isCall -> "$packageName|call|$title"
            groupKey != null -> "$packageName|$groupKey"
            else -> "$packageName|$title|$text"
        }
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
        presenceRef?.updateChildren(mapOf("online" to false, "timestamp" to ServerValue.TIMESTAMP))
        stopPresence()
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver)
            batteryReceiverRegistered = false
        }
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }
}