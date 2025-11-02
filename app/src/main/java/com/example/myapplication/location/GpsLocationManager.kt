package com.example.myapplication.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Менеджер для работы с GPS
 */
class GpsLocationManager(private val context: Context) : LocationListener {
    
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var isListening = false
    private var lastLocation: Location? = null
    
    interface LocationCallback {
        fun onLocationUpdate(location: Location)
        fun onError(error: String)
    }
    
    private var callback: LocationCallback? = null
    
    /**
     * Начать отслеживание GPS
     */
    fun startTracking(callback: LocationCallback) {
        if (!hasPermissions()) {
            callback.onError("GPS permissions not granted")
            return
        }
        
        this.callback = callback
        
        try {
            // Пробуем получить последнюю известную позицию
            val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastKnownLocation != null) {
                lastLocation = lastKnownLocation
                callback.onLocationUpdate(lastKnownLocation)
            }
            
            // Запрашиваем обновления
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, // 1 секунда
                1f, // 1 метр
                this
            )
            
            // Также слушаем Network Provider для быстрого получения позиции
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    5f,
                    this
                )
            }
            
            isListening = true
        } catch (e: SecurityException) {
            callback.onError("Location permission denied")
        }
    }
    
    /**
     * Остановить отслеживание
     */
    fun stopTracking() {
        locationManager.removeUpdates(this)
        isListening = false
        callback = null
    }
    
    /**
     * Получить последнюю известную позицию
     */
    fun getLastLocation(): Location? = lastLocation
    
    /**
     * Проверить наличие разрешений
     */
    fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onLocationChanged(location: Location) {
        lastLocation = location
        callback?.onLocationUpdate(location)
    }
    
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        // Обработка изменения статуса провайдера
    }
    
    override fun onProviderEnabled(provider: String) {
        // Провайдер включен
    }
    
    override fun onProviderDisabled(provider: String) {
        callback?.onError("GPS provider disabled")
    }
}

