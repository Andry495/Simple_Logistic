package com.example.myapplication.ins

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.PointF

/**
 * Менеджер инерциальной навигационной системы (INS)
 * Использует акселерометр и гироскоп для отслеживания движения
 */
class InsSensorManager(private val context: Context) : SensorEventListener {
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    
    private var isListening = false
    private var lastUpdateTime = 0L
    
    // Текущее состояние
    private var currentPosition = PointF(0f, 0f)
    private var currentVelocity = PointF(0f, 0f)
    private var currentHeading = 0f // Направление в радианах
    
    // Фильтры
    private val accelFilter = LowPassFilter(0.8f)
    private val gyroFilter = LowPassFilter(0.8f)
    
    // Смещения (калибровка)
    private var accelOffset = FloatArray(3)
    private var gyroOffset = FloatArray(3)
    private var calibrationSamples = 0
    private var isCalibrating = false
    
    // Последние значения датчиков
    private val lastAccel = FloatArray(3)
    private val lastGyro = FloatArray(3)
    
    interface PositionListener {
        fun onPositionUpdate(position: PointF, velocity: PointF, heading: Float)
        fun onError(error: String)
    }
    
    private var listener: PositionListener? = null
    
    /**
     * Начать отслеживание движения
     */
    fun startTracking(
        initialPosition: PointF,
        initialHeading: Float = 0f,
        positionListener: PositionListener
    ) {
        if (!isSensorAvailable()) {
            positionListener.onError("Sensors not available")
            return
        }
        
        this.listener = positionListener
        this.currentPosition = PointF(initialPosition.x, initialPosition.y)
        this.currentVelocity = PointF(0f, 0f)
        this.currentHeading = initialHeading
        this.lastUpdateTime = System.currentTimeMillis()
        
        // Калибровка датчиков (1 секунда)
        startCalibration(1000)
        
        // Регистрация слушателей
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        
        isListening = true
    }
    
    /**
     * Остановить отслеживание
     */
    fun stopTracking() {
        sensorManager.unregisterListener(this)
        isListening = false
        listener = null
    }
    
    /**
     * Установить текущую позицию (коррекция от Wi-Fi)
     */
    fun setPosition(position: PointF) {
        currentPosition = PointF(position.x, position.y)
        // Сбрасываем накопленную ошибку, обнуляем скорость если нужно
        currentVelocity = PointF(0f, 0f)
    }
    
    /**
     * Получить текущую позицию
     */
    fun getCurrentPosition(): PointF = PointF(currentPosition.x, currentPosition.y)
    
    /**
     * Получить текущую скорость
     */
    fun getCurrentVelocity(): PointF = PointF(currentVelocity.x, currentVelocity.y)
    
    /**
     * Получить текущее направление (в радианах)
     */
    fun getCurrentHeading(): Float = currentHeading
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isListening) return
        
        val currentTime = System.currentTimeMillis()
        val deltaTime = (currentTime - lastUpdateTime) / 1000.0f // в секундах
        if (deltaTime <= 0 || deltaTime > 1.0f) {
            lastUpdateTime = currentTime
            return
        }
        
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                if (isCalibrating) {
                    // Накопление смещений для калибровки
                    accelOffset[0] += event.values[0]
                    accelOffset[1] += event.values[1]
                    accelOffset[2] += event.values[2]
                    calibrationSamples++
                    return
                }
                
                // Применяем фильтр и смещения
                val filtered = FloatArray(3)
                filtered[0] = accelFilter.filter(event.values[0] - accelOffset[0] / calibrationSamples)
                filtered[1] = accelFilter.filter(event.values[1] - accelOffset[1] / calibrationSamples)
                filtered[2] = accelFilter.filter(event.values[2] - accelOffset[2] / calibrationSamples)
                
                processAccelerometer(filtered, deltaTime)
                lastAccel[0] = filtered[0]
                lastAccel[1] = filtered[1]
                lastAccel[2] = filtered[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (isCalibrating) {
                    gyroOffset[0] += event.values[0]
                    gyroOffset[1] += event.values[1]
                    gyroOffset[2] += event.values[2]
                    return
                }
                
                val filtered = FloatArray(3)
                filtered[0] = gyroFilter.filter(event.values[0] - gyroOffset[0] / calibrationSamples)
                filtered[1] = gyroFilter.filter(event.values[1] - gyroOffset[1] / calibrationSamples)
                filtered[2] = gyroFilter.filter(event.values[2] - gyroOffset[2] / calibrationSamples)
                
                // Обновляем направление (только Z-ось для поворота в плоскости)
                currentHeading += filtered[2] * deltaTime
                // Нормализация угла
                while (currentHeading > Math.PI) currentHeading -= (2 * Math.PI).toFloat()
                while (currentHeading < -Math.PI) currentHeading += (2 * Math.PI).toFloat()
                
                lastGyro[0] = filtered[0]
                lastGyro[1] = filtered[1]
                lastGyro[2] = filtered[2]
            }
        }
        
        lastUpdateTime = currentTime
    }
    
    private fun processAccelerometer(accel: FloatArray, deltaTime: Float) {
        // Убираем гравитационную составляющую, если устройство не вертикально
        // Упрощенный подход: используем только горизонтальную составляющую
        
        val ax = accel[0]
        val ay = accel[1]
        
        // Вычисляем ускорение в плоскости пола (проекция на XY)
        val magnitude = kotlin.math.sqrt(ax * ax + ay * ay)
        
        // Порог для определения, движется ли устройство
        val threshold = 0.5f // м/с²
        
        if (magnitude > threshold) {
            // Преобразуем ускорение в систему координат пола
            val accelX = ax * kotlin.math.cos(currentHeading) - ay * kotlin.math.sin(currentHeading)
            val accelY = ax * kotlin.math.sin(currentHeading) + ay * kotlin.math.cos(currentHeading)
            
            // Интегрируем ускорение для получения скорости
            currentVelocity.x += accelX * deltaTime
            currentVelocity.y += accelY * deltaTime
            
            // Применяем демпфирование для уменьшения дрейфа
            val damping = 0.95f
            currentVelocity.x *= damping
            currentVelocity.y *= damping
            
            // Интегрируем скорость для получения позиции
            currentPosition.x += currentVelocity.x * deltaTime
            currentPosition.y += currentVelocity.y * deltaTime
        } else {
            // Если нет движения, постепенно останавливаемся
            currentVelocity.x *= 0.9f
            currentVelocity.y *= 0.9f
        }
        
        listener?.onPositionUpdate(
            PointF(currentPosition.x, currentPosition.y),
            PointF(currentVelocity.x, currentVelocity.y),
            currentHeading
        )
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Можно обработать изменения точности датчика
    }
    
    private fun isSensorAvailable(): Boolean {
        return accelerometer != null && gyroscope != null
    }
    
    private fun startCalibration(durationMs: Long) {
        isCalibrating = true
        calibrationSamples = 0
        accelOffset = FloatArray(3)
        gyroOffset = FloatArray(3)
        
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            isCalibrating = false
            if (calibrationSamples > 0) {
                // Сохраняем средние значения смещений
                // Они будут использоваться для вычитания в onSensorChanged
            }
        }, durationMs)
    }
    
    /**
     * Простой фильтр низких частот для сглаживания данных датчиков
     */
    private class LowPassFilter(private val alpha: Float) {
        private var lastValue = FloatArray(3)
        private var initialized = false
        
        fun filter(values: FloatArray): Float {
            if (!initialized) {
                lastValue[0] = values[0]
                lastValue[1] = values[1]
                lastValue[2] = values[2]
                initialized = true
                return values[0] // Возвращаем первую компоненту для простоты
            }
            
            lastValue[0] = lastValue[0] + alpha * (values[0] - lastValue[0])
            lastValue[1] = lastValue[1] + alpha * (values[1] - lastValue[1])
            lastValue[2] = lastValue[2] + alpha * (values[2] - lastValue[2])
            
            return lastValue[0]
        }
        
        fun filter(value: Float): Float {
            if (!initialized) {
                lastValue[0] = value
                initialized = true
                return value
            }
            
            lastValue[0] = lastValue[0] + alpha * (value - lastValue[0])
            return lastValue[0]
        }
    }
}


