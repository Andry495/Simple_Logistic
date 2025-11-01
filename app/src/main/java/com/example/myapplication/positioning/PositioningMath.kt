package com.example.myapplication.positioning

import android.graphics.PointF
import kotlin.math.pow

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
        val x = solve2x2(AtA, Atb) ?: return null
        return PointF(x[0].toFloat(), x[1].toFloat())
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


