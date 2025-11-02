package com.example.myapplication.ui

import android.graphics.PointF

/**
 * Информация о расстоянии и направлении до точки доступа
 */
data class ApDistanceInfo(
    val bssid: String,
    val ssid: String,
    val distancePx: Float,  // Расстояние в пикселях
    val distanceMeters: Float,  // Расстояние в метрах
    val bearing: Float,  // Направление в радианах (0 = север, π/2 = восток)
    val rssi: Int,  // Сила сигнала
    val angleDegrees: Float  // Угол в градусах (0-360)
) {
    /**
     * Получить строковое представление направления (N, NE, E, SE, S, SW, W, NW)
     */
    fun getDirectionString(): String {
        val degrees = (angleDegrees + 360) % 360
        return when {
            degrees >= 337.5 || degrees < 22.5 -> "N"
            degrees >= 22.5 && degrees < 67.5 -> "NE"
            degrees >= 67.5 && degrees < 112.5 -> "E"
            degrees >= 112.5 && degrees < 157.5 -> "SE"
            degrees >= 157.5 && degrees < 202.5 -> "S"
            degrees >= 202.5 && degrees < 247.5 -> "SW"
            degrees >= 247.5 && degrees < 292.5 -> "W"
            degrees >= 292.5 && degrees < 337.5 -> "NW"
            else -> "N"
        }
    }
}

