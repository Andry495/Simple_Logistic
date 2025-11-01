package com.example.myapplication.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.myapplication.R
import kotlin.math.hypot

data class AccessPointPin(
    val bssid: String,
    var positionPx: PointF
)

class FloorPlanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val pinStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val devicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.FILL
    }
    private val calibPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val pins: MutableList<AccessPointPin> = mutableListOf()
    private var draggingIndex: Int = -1
    private var lastTouch = PointF()
    var onPinsChanged: ((List<AccessPointPin>) -> Unit)? = null

    var calibrationMode: Boolean = false
        set(value) {
            field = value
            if (!value) calibrationPoints.clear()
            invalidate()
        }
    private val calibrationPoints: MutableList<PointF> = mutableListOf()
    var onCalibrationPointsChanged: ((List<PointF>) -> Unit)? = null

    var devicePositionPx: PointF? = null
        set(value) {
            field = value
            invalidate()
        }

    fun setPins(newPins: List<AccessPointPin>) {
        pins.clear()
        pins.addAll(newPins)
        invalidate()
    }

    fun upsertPin(bssid: String, positionPx: PointF) {
        val idx = pins.indexOfFirst { it.bssid == bssid }
        if (idx >= 0) pins[idx].positionPx = positionPx else pins.add(AccessPointPin(bssid, positionPx))
        invalidate()
    }

    fun getPins(): List<AccessPointPin> = pins.toList()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // draw AP pins
        for (pin in pins) {
            canvas.drawCircle(pin.positionPx.x, pin.positionPx.y, 16f, pinPaint)
            canvas.drawCircle(pin.positionPx.x, pin.positionPx.y, 16f, pinStroke)
        }
        // draw device position if available
        devicePositionPx?.let {
            canvas.drawCircle(it.x, it.y, 14f, devicePaint)
        }

        // draw calibration points/line
        if (calibrationPoints.isNotEmpty()) {
            calibrationPoints.forEach { p ->
                canvas.drawCircle(p.x, p.y, 10f, calibPaint)
            }
            if (calibrationPoints.size == 2) {
                val a = calibrationPoints[0]
                val b = calibrationPoints[1]
                canvas.drawLine(a.x, a.y, b.x, b.y, calibPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (calibrationMode) {
                    if (calibrationPoints.size < 2) {
                        calibrationPoints.add(PointF(event.x, event.y))
                        onCalibrationPointsChanged?.invoke(calibrationPoints.toList())
                        invalidate()
                    }
                    return true
                }
                val idx = hitTest(event.x, event.y)
                if (idx >= 0) {
                    draggingIndex = idx
                    lastTouch.set(event.x, event.y)
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingIndex >= 0) {
                    val dx = event.x - lastTouch.x
                    val dy = event.y - lastTouch.y
                    pins[draggingIndex].positionPx.apply {
                        x += dx
                        y += dy
                    }
                    lastTouch.set(event.x, event.y)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingIndex = -1
                parent.requestDisallowInterceptTouchEvent(false)
                onPinsChanged?.let { it(pins.toList()) }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): Int {
        for (i in pins.indices.reversed()) {
            val p = pins[i].positionPx
            if (hypot((x - p.x).toDouble(), (y - p.y).toDouble()) <= 24.0) return i
        }
        return -1
    }
}
