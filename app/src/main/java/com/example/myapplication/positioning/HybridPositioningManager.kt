package com.example.myapplication.positioning

import android.content.Context
import android.graphics.PointF
import com.example.myapplication.fingerprinting.FingerprintMatcher
import com.example.myapplication.fingerprinting.FingerprintRepository
import com.example.myapplication.fingerprinting.WifiFingerprint
import com.example.myapplication.ins.InsSensorManager
import com.example.myapplication.positioning.PositioningMath
import com.example.myapplication.positioning.Anchor
import com.example.myapplication.wifi.ApSignal
import kotlin.math.sqrt

/**
 * Режимы позиционирования
 */
enum class PositioningMode {
    TRILATERATION,      // Триангуляция (текущий метод)
    FINGERPRINTING,     // Wi-Fi Fingerprinting
    HYBRID,             // Комбинация Fingerprinting + INS
    TRIANGULATION_INS   // Триангуляция + INS
}

/**
 * Гибридный менеджер позиционирования
 * Объединяет Wi-Fi Fingerprinting, триангуляцию и INS
 */
class HybridPositioningManager(private val context: Context) {
    
    private val fingerprintRepo = FingerprintRepository(context)
    private val insManager = InsSensorManager(context)
    
    private var currentMode = PositioningMode.TRIANGULATION_INS
    private var isInsTracking = false
    private var lastWifiPosition: PointF? = null
    private var lastWifiUpdateTime = 0L
    private var lastErrorTime = 0L // Для ограничения частоты сообщений об ошибках
    
    // Параметры для триангуляции
    var referenceRssi: Int = -50
    var distancePxPerMm: Float = 0.002f
    var pathLossExponent: Float = 2.5f
    
    // Параметры для гибридного режима
    var wifiUpdateInterval: Long = 3000 // Интервал обновления Wi-Fi в мс
    var maxInsDrift: Float = 5.0f // Максимальный дрейф INS в метрах перед принудительным обновлением Wi-Fi
    
    interface PositionUpdateListener {
        fun onPositionUpdate(position: PointF, confidence: Float, method: String)
        fun onError(error: String)
    }
    
    private var positionListener: PositionUpdateListener? = null
    
    /**
     * Установить режим позиционирования
     */
    fun setMode(mode: PositioningMode) {
        if (isInsTracking && mode != PositioningMode.HYBRID && mode != PositioningMode.TRIANGULATION_INS) {
            insManager.stopTracking()
            isInsTracking = false
        }
        currentMode = mode
    }
    
    /**
     * Получить текущий режим
     */
    fun getMode(): PositioningMode = currentMode
    
    /**
     * Получить количество сохраненных отпечатков
     */
    fun getFingerprintCount(): Int = fingerprintRepo.getCount()
    
    /**
     * Обработать Wi-Fi сканирование и определить позицию
     */
    fun processWifiScan(
        signals: List<ApSignal>,
        knownApPositions: Map<String, PointF>,
        listener: PositionUpdateListener
    ) {
        this.positionListener = listener
        
        when (currentMode) {
            PositioningMode.TRILATERATION -> {
                handleTrilateration(signals, knownApPositions)
            }
            PositioningMode.FINGERPRINTING -> {
                handleFingerprinting(signals)
            }
            PositioningMode.HYBRID -> {
                handleHybrid(signals, knownApPositions)
            }
            PositioningMode.TRIANGULATION_INS -> {
                handleTrilaterationIns(signals, knownApPositions)
            }
        }
    }
    
    /**
     * Вычислить fallback позицию (взвешенный центроид)
     */
    private fun calculateFallbackPosition(anchors: List<Anchor>): PointF? {
        if (anchors.isEmpty()) return null
        
        var totalWeight = 0.0
        var weightedX = 0.0
        var weightedY = 0.0
        
        for (anchor in anchors) {
            // Вес обратно пропорционален расстоянию (чем ближе, тем больше вес)
            val weight = 1.0 / (anchor.distancePx.toDouble() + 1.0)
            totalWeight += weight
            weightedX += anchor.x * weight
            weightedY += anchor.y * weight
        }
        
        if (totalWeight > 0.0) {
            return PointF(
                (weightedX / totalWeight).toFloat(),
                (weightedY / totalWeight).toFloat()
            )
        }
        
        return null
    }
    
    /**
     * Обработка триангуляции
     */
    private fun handleTrilateration(
        signals: List<ApSignal>,
        knownApPositions: Map<String, PointF>
    ) {
        val anchors = signals.mapNotNull { signal ->
            val apPosition = knownApPositions[signal.bssid] ?: return@mapNotNull null
            val distancePx = PositioningMath.rssiToDistancePx(
                rssi = signal.rssi,
                referenceRssi = referenceRssi,
                referenceDistancePx = 100f * distancePxPerMm * 1000f,
                pathLossExponent = pathLossExponent
            )
            Anchor(
                bssid = signal.bssid,
                x = apPosition.x,
                y = apPosition.y,
                distancePx = distancePx
            )
        }
        
        if (anchors.size >= 3) {
            val position = PositioningMath.trilaterate(anchors)
            if (position != null) {
                lastWifiPosition = position
                lastWifiUpdateTime = System.currentTimeMillis()
                positionListener?.onPositionUpdate(
                    position,
                    confidence = 0.7f,
                    method = "Trilateration"
                )
            } else {
                // Пробуем использовать fallback метод (взвешенный центроид)
                val fallbackPosition = calculateFallbackPosition(anchors)
                if (fallbackPosition != null) {
                    lastWifiPosition = fallbackPosition
                    lastWifiUpdateTime = System.currentTimeMillis()
                    positionListener?.onPositionUpdate(
                        fallbackPosition,
                        confidence = 0.5f,
                        method = "Trilateration (fallback)"
                    )
                } else {
                    // Только если даже fallback не сработал, отправляем ошибку (но без частых повторов)
                    if (System.currentTimeMillis() - lastErrorTime > 5000) { // Не чаще раза в 5 секунд
                        positionListener?.onError("Trilateration failed - need better AP positions")
                        lastErrorTime = System.currentTimeMillis()
                    }
                }
            }
        } else {
            positionListener?.onError("Need at least 3 known APs")
        }
    }
    
    /**
     * Обработка Fingerprinting
     */
    private fun handleFingerprinting(signals: List<ApSignal>) {
        val database = fingerprintRepo.loadAllFingerprints()
        
        if (database.isEmpty()) {
            positionListener?.onError("No fingerprints in database. Please train the system first.")
            return
        }
        
        val position = FingerprintMatcher.knnPosition(signals, database, k = 3)
        
        if (position != null) {
            lastWifiPosition = position
            lastWifiUpdateTime = System.currentTimeMillis()
            
            // Вычисляем уверенность на основе схожести лучшего совпадения
            val matches = FingerprintMatcher.findMatches(signals, database, topK = 1)
            val confidence = if (matches.isNotEmpty()) {
                matches[0].similarity.toFloat()
            } else {
                0.5f
            }
            
            positionListener?.onPositionUpdate(
                position,
                confidence = confidence,
                method = "Fingerprinting"
            )
        } else {
            positionListener?.onError("Fingerprint matching failed")
        }
    }
    
    /**
     * Гибридный режим: Fingerprinting + INS
     */
    private fun handleHybrid(
        signals: List<ApSignal>,
        knownApPositions: Map<String, PointF>
    ) {
        val database = fingerprintRepo.loadAllFingerprints()
        
        if (database.isEmpty()) {
            // Если нет отпечатков, используем триангуляцию
            handleTrilaterationIns(signals, knownApPositions)
            return
        }
        
        // Получаем позицию через Fingerprinting
        val fingerprintPos = FingerprintMatcher.knnPosition(signals, database, k = 3)
        
        if (fingerprintPos != null) {
            lastWifiPosition = fingerprintPos
            lastWifiUpdateTime = System.currentTimeMillis()
            
            // Корректируем INS
            if (isInsTracking) {
                insManager.setPosition(fingerprintPos)
            } else {
                // Запускаем INS отслеживание
                startInsTracking(fingerprintPos)
            }
            
            val matches = FingerprintMatcher.findMatches(signals, database, topK = 1)
            val confidence = if (matches.isNotEmpty()) {
                matches[0].similarity.toFloat()
            } else {
                0.7f
            }
            
            positionListener?.onPositionUpdate(
                fingerprintPos,
                confidence = confidence,
                method = "Hybrid (Wi-Fi)"
            )
        } else {
            // Если Fingerprinting не сработал, используем INS если он активен
            if (isInsTracking) {
                val insPos = insManager.getCurrentPosition()
                positionListener?.onPositionUpdate(
                    insPos,
                    confidence = 0.4f,
                    method = "Hybrid (INS only)"
                )
            } else {
                positionListener?.onError("Fingerprinting failed and INS not available")
            }
        }
    }
    
    /**
     * Триангуляция + INS
     */
    private fun handleTrilaterationIns(
        signals: List<ApSignal>,
        knownApPositions: Map<String, PointF>
    ) {
        val anchors = signals.mapNotNull { signal ->
            val apPosition = knownApPositions[signal.bssid] ?: return@mapNotNull null
            val distancePx = PositioningMath.rssiToDistancePx(
                rssi = signal.rssi,
                referenceRssi = referenceRssi,
                referenceDistancePx = 100f * distancePxPerMm * 1000f,
                pathLossExponent = pathLossExponent
            )
            Anchor(
                bssid = signal.bssid,
                x = apPosition.x,
                y = apPosition.y,
                distancePx = distancePx
            )
        }
        
        if (anchors.size >= 3) {
            val position = PositioningMath.trilaterate(anchors)
            if (position != null) {
                lastWifiPosition = position
                lastWifiUpdateTime = System.currentTimeMillis()
                
                // Корректируем INS
                if (isInsTracking) {
                    insManager.setPosition(position)
                } else {
                    startInsTracking(position)
                }
                
                positionListener?.onPositionUpdate(
                    position,
                    confidence = 0.7f,
                    method = "Triangulation+INS (Wi-Fi)"
                )
                } else {
                    // Пробуем использовать fallback метод
                    val fallbackPosition = calculateFallbackPosition(anchors)
                    if (fallbackPosition != null) {
                        lastWifiPosition = fallbackPosition
                        lastWifiUpdateTime = System.currentTimeMillis()
                        
                        if (isInsTracking) {
                            insManager.setPosition(fallbackPosition)
                        } else {
                            startInsTracking(fallbackPosition)
                        }
                        
                        positionListener?.onPositionUpdate(
                            fallbackPosition,
                            confidence = 0.5f,
                            method = "Triangulation+INS (fallback)"
                        )
                    } else if (isInsTracking) {
                        // Используем INS если он активен
                        val insPos = insManager.getCurrentPosition()
                        positionListener?.onPositionUpdate(
                            insPos,
                            confidence = 0.4f,
                            method = "Triangulation+INS (INS only)"
                        )
                    } else {
                        // Только если все методы не сработали и не чаще раза в 5 секунд
                        if (System.currentTimeMillis() - lastErrorTime > 5000) {
                            positionListener?.onError("Trilateration failed - need better AP positions")
                            lastErrorTime = System.currentTimeMillis()
                        }
                    }
                }
        } else {
            // Используем INS если он активен
            if (isInsTracking) {
                val insPos = insManager.getCurrentPosition()
                positionListener?.onPositionUpdate(
                    insPos,
                    confidence = 0.3f,
                    method = "Triangulation+INS (INS only)"
                )
            } else {
                positionListener?.onError("Need at least 3 known APs")
            }
        }
    }
    
    /**
     * Запустить INS отслеживание
     */
    private fun startInsTracking(initialPosition: PointF) {
        if (!isInsTracking) {
            insManager.startTracking(
                initialPosition = initialPosition,
                initialHeading = 0f,
                positionListener = object : InsSensorManager.PositionListener {
                    override fun onPositionUpdate(position: PointF, velocity: PointF, heading: Float) {
                        // Проверяем, не нужно ли обновить Wi-Fi
                        val timeSinceLastWifi = System.currentTimeMillis() - lastWifiUpdateTime
                        val shouldUpdateWifi = timeSinceLastWifi > wifiUpdateInterval
                        
                        if (shouldUpdateWifi && lastWifiPosition != null) {
                            // Вычисляем дрейф
                            val drift = sqrt(
                                (position.x - lastWifiPosition!!.x) * (position.x - lastWifiPosition!!.x) +
                                (position.y - lastWifiPosition!!.y) * (position.y - lastWifiPosition!!.y)
                            )
                            
                            if (drift > maxInsDrift * 100) { // maxInsDrift в метрах, конвертируем в пиксели
                                // Дрейф слишком большой, нужно обновить Wi-Fi
                                // Это будет сделано при следующем сканировании
                            }
                        }
                        
                        // Отправляем обновление позиции через INS
                        positionListener?.onPositionUpdate(
                            position,
                            confidence = 0.5f,
                            method = "INS"
                        )
                    }
                    
                    override fun onError(error: String) {
                        positionListener?.onError("INS error: $error")
                    }
                }
            )
            isInsTracking = true
        }
    }
    
    /**
     * Остановить отслеживание
     */
    fun stopTracking() {
        if (isInsTracking) {
            insManager.stopTracking()
            isInsTracking = false
        }
    }
    
    /**
     * Сохранить отпечаток для обучения
     */
    fun saveFingerprint(signals: List<ApSignal>, location: PointF) {
        val fingerprint = WifiFingerprint(
            location = location,
            signals = signals.associate { it.bssid to it.rssi }
        )
        fingerprintRepo.saveFingerprint(fingerprint)
    }
    
    /**
     * Очистить базу отпечатков
     */
    fun clearFingerprints() {
        fingerprintRepo.clearAll()
    }
}


