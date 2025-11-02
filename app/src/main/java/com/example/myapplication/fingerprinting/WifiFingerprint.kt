package com.example.myapplication.fingerprinting

import android.graphics.PointF
import com.example.myapplication.wifi.ApSignal

/**
 * Wi-Fi отпечаток - набор RSSI значений для всех видимых точек доступа
 */
data class WifiFingerprint(
    val location: PointF,  // Координаты на карте
    val signals: Map<String, Int>,  // BSSID -> RSSI
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Результат сравнения отпечатков
 */
data class FingerprintMatch(
    val location: PointF,
    val similarity: Double,  // 0.0 - 1.0, где 1.0 = идеальное совпадение
    val distance: Double  // Евклидово расстояние в пространстве RSSI
)


