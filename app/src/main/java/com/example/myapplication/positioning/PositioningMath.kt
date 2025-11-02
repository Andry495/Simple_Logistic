package com.example.myapplication.positioning

import android.graphics.PointF
import kotlin.math.pow
import kotlin.math.sqrt

data class Anchor(val bssid: String, val x: Float, val y: Float, val distancePx: Float)

object PositioningMath {
    // Convert RSSI (dBm) to estimated distance in pixels
    // Using path loss model: d = 10^((TxPower - RSSI) / (10 * n))
    // Simplified: d ~ k * 10^(-RSSI / (10 * n)) where n is path loss exponent (typically 2-4)
    fun rssiToDistancePx(rssi: Int, referenceRssi: Int = -50, referenceDistancePx: Float = 100f, pathLossExponent: Float = 2.5f): Float {
        // Normalize RSSI relative to reference point
        val rssiDiff = (referenceRssi - rssi).toFloat()
        // Calculate distance ratio using path loss model
        val distanceRatio = 10.0.pow((rssiDiff / (10.0 * pathLossExponent))).toFloat()
        return referenceDistancePx * distanceRatio
    }

    // Linearized least squares trilateration in 2D
    fun trilaterate(anchors: List<Anchor>): PointF? {
        if (anchors.size < 3) return null
        
        // Проверяем, что расстояния валидны
        val validAnchors = anchors.filter { it.distancePx > 0.1f && !it.distancePx.isInfinite() && !it.distancePx.isNaN() }
        if (validAnchors.size < 3) return null
        
        // Используем первые 3-5 анкеров с наилучшим сигналом (наибольшая дистанция = слабый сигнал)
        val sortedAnchors = validAnchors.sortedByDescending { it.distancePx }.take(5)
        
        // Пробуем разные комбинации анкеров для лучшего результата
        for (startIdx in 0 until (sortedAnchors.size - 2)) {
            val selectedAnchors = sortedAnchors.subList(startIdx, minOf(startIdx + 4, sortedAnchors.size))
            val result = trilaterateWithAnchors(selectedAnchors)
            if (result != null && isValidPosition(result, sortedAnchors)) {
                return result
            }
        }
        
        // Fallback: используем центроид взвешенный по расстояниям
        return weightedCentroid(validAnchors)
    }
    
    /**
     * Триангуляция с заданным набором анкеров
     */
    private fun trilaterateWithAnchors(anchors: List<Anchor>): PointF? {
        if (anchors.size < 3) return null
        
        val a0 = anchors[0]
        val A = Array(anchors.size - 1) { DoubleArray(2) }
        val b = DoubleArray(anchors.size - 1)

        for (i in 1 until anchors.size) {
            val ai = anchors[i]
            val xi = ai.x.toDouble()
            val yi = ai.y.toDouble()
            val ri2 = (ai.distancePx * ai.distancePx).toDouble()
            val x0 = a0.x.toDouble()
            val y0 = a0.y.toDouble()
            val r02 = (a0.distancePx * a0.distancePx).toDouble()
            A[i - 1][0] = 2.0 * (xi - x0)
            A[i - 1][1] = 2.0 * (yi - y0)
            b[i - 1] = (ri2 - r02) - (xi * xi + yi * yi) + (x0 * x0 + y0 * y0)
        }

        val At = transpose(A)
        val AtA = multiply(At, A)
        val Atb = multiply(At, b)
        
        // Проверяем на вырожденный случай (все точки на одной линии)
        val det = AtA[0][0] * AtA[1][1] - AtA[0][1] * AtA[1][0]
        if (kotlin.math.abs(det) < 1e-4) return null
        
        val x = solve2x2(AtA, Atb) ?: return null
        
        // Проверяем, что результат валиден
        val result = PointF(x[0].toFloat(), x[1].toFloat())
        if (result.x.isNaN() || result.y.isNaN() || result.x.isInfinite() || result.y.isInfinite()) {
            return null
        }
        
        return result
    }
    
    /**
     * Проверить, валидна ли вычисленная позиция
     */
    private fun isValidPosition(position: PointF, anchors: List<Anchor>): Boolean {
        // Проверяем, что позиция не слишком далеко от всех анкеров
        val maxExpectedDistance = anchors.maxOfOrNull { it.distancePx * 2f } ?: Float.MAX_VALUE
        for (anchor in anchors) {
            val dx = position.x - anchor.x
            val dy = position.y - anchor.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > maxExpectedDistance) {
                return false
            }
        }
        return true
    }
    
    /**
     * Взвешенный центроид как fallback метод
     */
    private fun weightedCentroid(anchors: List<Anchor>): PointF {
        var totalWeight = 0.0
        var weightedX = 0.0
        var weightedY = 0.0
        
        for (anchor in anchors) {
            // Вес обратно пропорционален расстоянию (чем ближе, тем больше вес)
            val weight = 1.0 / (anchor.distancePx + 1.0)
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
        
        // Если все не удалось, возвращаем среднее арифметическое
        val avgX = anchors.map { it.x }.average().toFloat()
        val avgY = anchors.map { it.y }.average().toFloat()
        return PointF(avgX, avgY)
    }

    private fun transpose(m: Array<DoubleArray>): Array<DoubleArray> {
        val rows = m.size
        val cols = m[0].size
        val t = Array(cols) { DoubleArray(rows) }
        for (r in 0 until rows) for (c in 0 until cols) t[c][r] = m[r][c]
        return t
    }

    private fun multiply(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val rows = a.size
        val cols = b[0].size
        val inner = b.size
        val out = Array(rows) { DoubleArray(cols) }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var sum = 0.0
                for (k in 0 until inner) sum += a[r][k] * b[k][c]
                out[r][c] = sum
            }
        }
        return out
    }

    private fun multiply(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val rows = a.size
        val cols = a[0].size
        val out = DoubleArray(rows)
        for (r in 0 until rows) {
            var sum = 0.0
            for (c in 0 until cols) sum += a[r][c] * b[c]
            out[r] = sum
        }
        return out
    }

    // Solve 2x2 linear system (AtA * x = Atb)
    private fun solve2x2(m: Array<DoubleArray>, v: DoubleArray): DoubleArray? {
        if (m.size != 2 || m[0].size != 2 || v.size != 2) return null
        val a = m[0][0]
        val b = m[0][1]
        val c = m[1][0]
        val d = m[1][1]
        val det = a * d - b * c
        if (kotlin.math.abs(det) < 1e-6) return null
        val inv = arrayOf(
            doubleArrayOf(d / det, -b / det),
            doubleArrayOf(-c / det, a / det)
        )
        val x0 = inv[0][0] * v[0] + inv[0][1] * v[1]
        val x1 = inv[1][0] * v[0] + inv[1][1] * v[1]
        return doubleArrayOf(x0, x1)
    }
}


