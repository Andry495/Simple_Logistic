package com.example.myapplication.fingerprinting

import android.content.Context
import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject

/**
 * Репозиторий для хранения и загрузки отпечатков Wi-Fi
 */
class FingerprintRepository(private val context: Context) {
    
    private val prefs = context.getSharedPreferences("fingerprints", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_FINGERPRINTS = "fingerprints_data"
    }
    
    /**
     * Сохранить отпечаток
     */
    fun saveFingerprint(fingerprint: WifiFingerprint) {
        val all = loadAllFingerprints().toMutableList()
        all.add(fingerprint)
        saveAllFingerprints(all)
    }
    
    /**
     * Сохранить несколько отпечатков
     */
    fun saveFingerprints(fingerprints: List<WifiFingerprint>) {
        val all = loadAllFingerprints().toMutableList()
        all.addAll(fingerprints)
        saveAllFingerprints(all)
    }
    
    /**
     * Загрузить все отпечатки
     */
    fun loadAllFingerprints(): List<WifiFingerprint> {
        val json = prefs.getString(KEY_FINGERPRINTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<WifiFingerprint>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val location = PointF(
                    obj.getDouble("x").toFloat(),
                    obj.getDouble("y").toFloat()
                )
                val signalsObj = obj.getJSONObject("signals")
                val signals = mutableMapOf<String, Int>()
                val keys = signalsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    signals[key] = signalsObj.getInt(key)
                }
                list.add(WifiFingerprint(
                    location = location,
                    signals = signals,
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                ))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Удалить все отпечатки
     */
    fun clearAll() {
        prefs.edit().remove(KEY_FINGERPRINTS).apply()
    }
    
    /**
     * Получить количество сохраненных отпечатков
     */
    fun getCount(): Int = loadAllFingerprints().size
    
    private fun saveAllFingerprints(fingerprints: List<WifiFingerprint>) {
        val arr = JSONArray()
        fingerprints.forEach { fp ->
            val obj = JSONObject()
                .put("x", fp.location.x.toDouble())
                .put("y", fp.location.y.toDouble())
                .put("timestamp", fp.timestamp)
            val signalsObj = JSONObject()
            fp.signals.forEach { (bssid, rssi) ->
                signalsObj.put(bssid, rssi)
            }
            obj.put("signals", signalsObj)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_FINGERPRINTS, arr.toString()).apply()
    }
}


