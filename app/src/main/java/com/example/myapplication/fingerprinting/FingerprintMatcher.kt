package com.example.myapplication.fingerprinting

import android.graphics.PointF
import com.example.myapplication.wifi.ApSignal

/**
 * Алгоритм сравнения Wi-Fi отпечатков для определения местоположения
 */
object FingerprintMatcher {
    
    /**
     * Найти наиболее похожие отпечатки для текущего сканирования
     * 
     * @param currentSignals Текущий список сигналов Wi-Fi
     * @param database База данных отпечатков для сравнения
     * @param topK Количество лучших совпадений для возврата
     * @return Список наиболее похожих отпечатков, отсортированный по убыванию схожести
     */
    fun findMatches(
        currentSignals: List<ApSignal>,
        database: List<WifiFingerprint>,
        topK: Int = 5
    ): List<FingerprintMatch> {
        if (currentSignals.isEmpty() || database.isEmpty()) {
            return emptyList()
        }
        
        // Преобразуем список сигналов в Map<String, Int> (BSSID -> RSSI)
        val currentMap = currentSignals.associate { it.bssid to it.rssi }
        
        return database.map { fingerprint ->
            val distance = euclideanDistance(currentMap, fingerprint.signals)
            // Преобразуем расстояние в схожесть (чем меньше расстояние, тем больше схожесть)
            // Используем обратное экспоненциальное преобразование
            val similarity = 1.0 / (1.0 + distance / 100.0) // Нормализация
            FingerprintMatch(
                location = fingerprint.location,
                similarity = similarity,
                distance = distance
            )
        }
        .sortedByDescending { it.similarity }
        .take(topK)
    }
    
    /**
     * Вычислить взвешенное среднее местоположение на основе нескольких совпадений
     */
    fun weightedAverage(matches: List<FingerprintMatch>): PointF? {
        if (matches.isEmpty()) return null
        
        var totalWeight = 0.0
        var weightedX = 0.0
        var weightedY = 0.0
        
        matches.forEach { match ->
            val weight = match.similarity
            totalWeight += weight
            weightedX += match.location.x * weight
            weightedY += match.location.y * weight
        }
        
        if (totalWeight == 0.0) return null
        
        return PointF(
            (weightedX / totalWeight).toFloat(),
            (weightedY / totalWeight).toFloat()
        )
    }
    
    /**
     * Вычислить K-Nearest Neighbors (KNN) для определения позиции
     */
    fun knnPosition(
        currentSignals: List<ApSignal>,
        database: List<WifiFingerprint>,
        k: Int = 3
    ): PointF? {
        val matches = findMatches(currentSignals, database, k)
        return weightedAverage(matches)
    }
    
    /**
     * Евклидово расстояние между двумя отпечатками в пространстве RSSI
     */
    private fun euclideanDistance(
        signals1: Map<String, Int>,
        signals2: Map<String, Int>
    ): Double {
        val allBssids = (signals1.keys + signals2.keys).distinct()
        if (allBssids.isEmpty()) return Double.MAX_VALUE
        
        var sumSquaredDiff = 0.0
        var count = 0
        
        allBssids.forEach { bssid ->
            val rssi1 = signals1[bssid] ?: -100  // Если сигнала нет, считаем очень слабым
            val rssi2 = signals2[bssid] ?: -100
            val diff = (rssi1 - rssi2).toDouble()
            sumSquaredDiff += diff * diff
            count++
        }
        
        // Возвращаем корень из среднего квадрата разности
        return if (count > 0) kotlin.math.sqrt(sumSquaredDiff / count) else Double.MAX_VALUE
    }
    
    /**
     * Косинусное сходство (альтернативный метод сравнения)
     */
    fun cosineSimilarity(
        signals1: Map<String, Int>,
        signals2: Map<String, Int>
    ): Double {
        val allBssids = (signals1.keys + signals2.keys).distinct()
        if (allBssids.isEmpty()) return 0.0
        
        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0
        
        allBssids.forEach { bssid ->
            val rssi1 = (signals1[bssid] ?: -100).toDouble()
            val rssi2 = (signals2[bssid] ?: -100).toDouble()
            dotProduct += rssi1 * rssi2
            norm1 += rssi1 * rssi1
            norm2 += rssi2 * rssi2
        }
        
        val denominator = kotlin.math.sqrt(norm1) * kotlin.math.sqrt(norm2)
        return if (denominator > 0.0) dotProduct / denominator else 0.0
    }
}


