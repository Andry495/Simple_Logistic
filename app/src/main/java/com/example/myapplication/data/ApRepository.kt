package com.example.myapplication.data

import android.content.Context
import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject

data class SavedPin(val bssid: String, val x: Float, val y: Float)

class ApRepository(context: Context) {
    private val prefs = context.getSharedPreferences("ap_pins", Context.MODE_PRIVATE)

    fun savePins(pins: List<SavedPin>) {
        val arr = JSONArray()
        pins.forEach { p ->
            val obj = JSONObject()
                .put("bssid", p.bssid)
                .put("x", p.x)
                .put("y", p.y)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_PINS, arr.toString()).apply()
    }

    fun loadPins(): List<SavedPin> {
        val json = prefs.getString(KEY_PINS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<SavedPin>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(SavedPin(
                    bssid = o.getString("bssid"),
                    x = o.getDouble("x").toFloat(),
                    y = o.getDouble("y").toFloat()
                ))
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    companion object {
        private const val KEY_PINS = "pins"
    }
}


