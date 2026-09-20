package com.lexlebeau.notiflow

import android.content.SharedPreferences

data class PairedDevice(
    val pairCode: String,
    val aesKey: String,
    val label: String
)

/** Хранилище списка спаренных сендеров для ресивера (лимит [MAX_DEVICES]). */
object PairedDevices {
    const val MAX_DEVICES = 5

    private const val KEY_SET = "paired_devices"
    private const val KEY_PRIMARY = "primary_pair_code"

    private fun encode(device: PairedDevice) = "${device.pairCode}:${device.aesKey}:${device.label}"

    private fun decode(entry: String): PairedDevice? {
        val parts = entry.split(":", limit = 3)
        if (parts.size < 2) return null
        val pairCode = parts[0]
        val aesKey = parts[1]
        val label = if (parts.size >= 3 && parts[2].isNotBlank()) parts[2] else pairCode
        return PairedDevice(pairCode, aesKey, label)
    }

    /** Мигрирует старый одиночный pairCode/aesKey в новый список (однократно). */
    fun migrateIfNeeded(prefs: SharedPreferences) {
        if (prefs.contains(KEY_SET)) return
        val oldPairCode = prefs.getString("pairCode", null)
        val oldAesKey = prefs.getString("aesKey", null)
        if (oldPairCode == null || oldAesKey == null) {
            prefs.edit().putStringSet(KEY_SET, mutableSetOf()).apply()
            return
        }
        val device = PairedDevice(oldPairCode, oldAesKey, oldPairCode)
        prefs.edit()
            .putStringSet(KEY_SET, mutableSetOf(encode(device)))
            .putString(KEY_PRIMARY, oldPairCode)
            .apply()
    }

    fun getAll(prefs: SharedPreferences): List<PairedDevice> {
        migrateIfNeeded(prefs)
        return rawAll(prefs)
    }

    private fun rawAll(prefs: SharedPreferences): List<PairedDevice> {
        val set = prefs.getStringSet(KEY_SET, emptySet()) ?: emptySet()
        return set.mapNotNull { decode(it) }
    }

    fun get(prefs: SharedPreferences, pairCode: String): PairedDevice? =
        getAll(prefs).find { it.pairCode == pairCode }

    /** Добавляет устройство. Возвращает false если достигнут лимит [MAX_DEVICES]. */
    fun add(prefs: SharedPreferences, pairCode: String, aesKey: String, label: String = pairCode): Boolean {
        val current = getAll(prefs).toMutableList()
        val existingIndex = current.indexOfFirst { it.pairCode == pairCode }
        if (existingIndex >= 0) {
            current[existingIndex] = PairedDevice(pairCode, aesKey, label)
            save(prefs, current)
            return true
        }
        if (current.size >= MAX_DEVICES) return false
        current.add(PairedDevice(pairCode, aesKey, label))
        save(prefs, current)
        if (getPrimary(prefs) == null) setPrimary(prefs, pairCode)
        return true
    }

    fun remove(prefs: SharedPreferences, pairCode: String) {
        val current = getAll(prefs).filterNot { it.pairCode == pairCode }
        save(prefs, current)
        prefs.edit()
            .remove("geo_enabled_$pairCode")
            .remove("home_lat_$pairCode")
            .remove("home_lon_$pairCode")
            .remove("home_radius_$pairCode")
            .remove("geo_exempt_apps_$pairCode")
            .remove("only_messengers_$pairCode")
            .apply()
        if (prefs.getString(KEY_PRIMARY, null) == pairCode) {
            val next = current.firstOrNull()?.pairCode
            if (next != null) setPrimary(prefs, next) else prefs.edit().remove(KEY_PRIMARY).apply()
        }
    }

    private fun save(prefs: SharedPreferences, devices: List<PairedDevice>) {
        prefs.edit().putStringSet(KEY_SET, devices.map { encode(it) }.toMutableSet()).apply()
    }

    fun getPrimary(prefs: SharedPreferences): String? {
        val devices = getAll(prefs)
        val primary = prefs.getString(KEY_PRIMARY, null)
        if (primary != null && devices.any { it.pairCode == primary }) return primary
        return devices.firstOrNull()?.pairCode
    }

    fun setPrimary(prefs: SharedPreferences, pairCode: String) {
        prefs.edit().putString(KEY_PRIMARY, pairCode).apply()
    }
}
