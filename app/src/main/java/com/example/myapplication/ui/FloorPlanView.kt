package com.example.myapplication.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.example.myapplication.R
import kotlin.math.hypot
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

enum class PinStatus {
    FREE,   // Свободная - может автоматически корректироваться
    FIXED   // Фиксированная - позиция зафиксирована пользователем
}

data class AccessPointPin(
    val bssid: String,
    var positionPx: PointF,
    var status: PinStatus = PinStatus.FREE,
    var lastUpdateTime: Long = System.currentTimeMillis()
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
        strokeWidth = 6f
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
    
    // Кисти для линий к точкам доступа
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 255, 0) // Полностью непрозрачный желтый
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    
        // Кисть для текста расстояний
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        textSize = 28f
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }
    
    // Кисть для текста информации о точках доступа
    private val apInfoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    
    // Кисть для фона информации о точке доступа
    private val apInfoBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 0, 0)
        style = Paint.Style.FILL
    }
    
    // Кисть для вектора направления (стрелки)
    private val vectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 0, 255, 0) // Полностью непрозрачный зеленый
        style = Paint.Style.STROKE
        strokeWidth = 6f // Увеличенная толщина для видимости
    }
    
    private val vectorHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 0, 200, 0) // Темно-зеленый для наконечника
        style = Paint.Style.FILL
    }

    private val pins: MutableList<AccessPointPin> = mutableListOf()
    private var distanceInfos: List<ApDistanceInfo> = emptyList()
    private var draggingIndex: Int = -1
    private var lastTouch = PointF()
    var onPinsChanged: ((List<AccessPointPin>) -> Unit)? = null
    
    // Матрица преобразования для масштабирования и панорамирования
    private val transformMatrix = Matrix()
    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f
    private var minScale = 0.5f
    private var maxScale = 5.0f
    
    // Для масштабирования и панорамирования
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector
    private var isPanning = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var lastTapTime = 0L
    private var lastTappedIndex = -1
    
    // Кисти для разных статусов точек
    private val fixedPinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.MAGENTA
        style = Paint.Style.FILL
    }
    
    init {
        // Инициализация детекторов жестов
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                // Начало жеста масштабирования
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scale = detector.scaleFactor
                // Применяем масштабирование относительно точки фокуса
                val focusX = detector.focusX
                val focusY = detector.focusY
                
                // Преобразуем точку фокуса в координаты карты
                val mapFocus = screenToMap(PointF(focusX, focusY))
                
                // Применяем масштабирование
                val oldScale = scaleFactor
                val newScale = scaleFactor * scale
                scaleFactor = newScale.coerceIn(minScale, maxScale)
                
                // Корректируем смещение, чтобы точка фокуса осталась на месте
                val scaleChange = scaleFactor / oldScale
                translateX = mapFocus.x * (1 - scaleChange) + translateX * scaleChange
                translateY = mapFocus.y * (1 - scaleChange) + translateY * scaleChange
                
                updateTransform()
                invalidate()
                return true
            }
            
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                parent.requestDisallowInterceptTouchEvent(false)
            }
        })
        
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // Возвращаем true, чтобы получать последующие события
                return true
            }
            
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (draggingIndex < 0 && !calibrationMode && !trainingMode) {
                    // Применяем панорамирование в координатах карты
                    translateX += distanceX / scaleFactor
                    translateY += distanceY / scaleFactor
                    updateTransform()
                    invalidate()
                    return true
                }
                return false
            }
        })
        
        // Инициализация матрицы преобразования
        updateTransform()
        
        // Устанавливаем прозрачный фон
        setBackgroundColor(Color.TRANSPARENT)
    }
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Убеждаемся, что фон прозрачен
        setBackgroundColor(Color.TRANSPARENT)
    }

    var calibrationMode: Boolean = false
        set(value) {
            field = value
            if (!value) calibrationPoints.clear()
            invalidate()
        }
    private val calibrationPoints: MutableList<PointF> = mutableListOf()
    var onCalibrationPointsChanged: ((List<PointF>) -> Unit)? = null
    
    var trainingMode: Boolean = false
    var onTrainingLocationSelected: ((PointF) -> Unit)? = null

    var devicePositionPx: PointF? = null
        set(value) {
            field = value
            invalidate()
        }
    
    /**
     * Установить информацию о расстояниях до точек доступа
     */
    fun setDistanceInfos(infos: List<ApDistanceInfo>) {
        distanceInfos = infos
        invalidate()
    }

    fun setPins(newPins: List<AccessPointPin>) {
        pins.clear()
        pins.addAll(newPins)
        invalidate()
    }

    fun upsertPin(bssid: String, positionPx: PointF, status: PinStatus = PinStatus.FREE) {
        val idx = pins.indexOfFirst { it.bssid == bssid }
        if (idx >= 0) {
            if (pins[idx].status == PinStatus.FREE) {
                pins[idx].positionPx = positionPx
                pins[idx].lastUpdateTime = System.currentTimeMillis()
            }
        } else {
            pins.add(AccessPointPin(bssid, positionPx, status))
        }
        invalidate()
    }
    
    /**
     * Автоматическая корректировка позиции свободной точки доступа
     */
    fun adjustPinPosition(bssid: String, newPosition: PointF, learningRate: Float = 0.1f) {
        val idx = pins.indexOfFirst { it.bssid == bssid }
        if (idx >= 0 && pins[idx].status == PinStatus.FREE) {
            val pin = pins[idx]
            // Плавная коррекция позиции (exponential moving average)
            pin.positionPx.x = pin.positionPx.x * (1f - learningRate) + newPosition.x * learningRate
            pin.positionPx.y = pin.positionPx.y * (1f - learningRate) + newPosition.y * learningRate
            pin.lastUpdateTime = System.currentTimeMillis()
            invalidate()
            onPinsChanged?.invoke(pins.toList())
        }
    }
    
    /**
     * Переключить статус точки доступа
     */
    fun togglePinStatus(bssid: String) {
        val idx = pins.indexOfFirst { it.bssid == bssid }
        if (idx >= 0) {
            pins[idx].status = when (pins[idx].status) {
                PinStatus.FREE -> PinStatus.FIXED
                PinStatus.FIXED -> PinStatus.FREE
            }
            invalidate()
            onPinsChanged?.invoke(pins.toList())
        }
    }
    
    /**
     * Установить статус точки доступа
     */
    fun setPinStatus(bssid: String, status: PinStatus) {
        val idx = pins.indexOfFirst { it.bssid == bssid }
        if (idx >= 0) {
            pins[idx].status = status
            invalidate()
            onPinsChanged?.invoke(pins.toList())
        }
    }
    
    /**
     * Получить все точки с определенным статусом
     */
    fun getPinsByStatus(status: PinStatus): List<AccessPointPin> {
        return pins.filter { it.status == status }
    }
    
    /**
     * Обновить матрицу преобразования
     */
    private fun updateTransform() {
        transformMatrix.reset()
        transformMatrix.postScale(scaleFactor, scaleFactor)
        transformMatrix.postTranslate(translateX, translateY)
    }
    
    /**
     * Преобразовать координаты экрана в координаты карты
     */
    private fun screenToMap(screenPoint: PointF): PointF {
        val inverseMatrix = Matrix()
        transformMatrix.invert(inverseMatrix)
        val points = floatArrayOf(screenPoint.x, screenPoint.y)
        inverseMatrix.mapPoints(points)
        return PointF(points[0], points[1])
    }
    
    /**
     * Преобразовать координаты карты в координаты экрана
     */
    private fun mapToScreen(mapPoint: PointF): PointF {
        val points = floatArrayOf(mapPoint.x, mapPoint.y)
        transformMatrix.mapPoints(points)
        return PointF(points[0], points[1])
    }
    
    /**
     * Сброс масштаба и позиции
     */
    fun resetTransform() {
        scaleFactor = 1.0f
        translateX = 0f
        translateY = 0f
        updateTransform()
        invalidate()
    }

    fun getPins(): List<AccessPointPin> = pins.toList()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Убеждаемся, что фон прозрачен
        setBackgroundColor(Color.TRANSPARENT)
        
        // Сохраняем состояние canvas
        canvas.save()
        // Применяем матрицу преобразования
        canvas.concat(transformMatrix)
        
        val devicePos = devicePositionPx
        
        // Draw lines and vectors from device to access points
        if (devicePos != null && distanceInfos.isNotEmpty()) {
            for (info in distanceInfos) {
                val pin = pins.find { it.bssid == info.bssid } ?: continue
                val apPos = pin.positionPx
                
                // Draw line from device to AP
                canvas.drawLine(devicePos.x, devicePos.y, apPos.x, apPos.y, linePaint)
                
                // Draw vector (arrow) from device to AP
                drawVector(canvas, devicePos, apPos, info.distanceMeters)
                
                // Draw distance text at midpoint
                val midX = (devicePos.x + apPos.x) / 2f
                val midY = (devicePos.y + apPos.y) / 2f - 15f
                val distanceText = String.format("%.1fm", info.distanceMeters)
                canvas.drawText(distanceText, midX, midY, textPaint)
            }
        }
        
        // draw AP pins с технической информацией
        for (pin in pins) {
            val info = distanceInfos.find { it.bssid == pin.bssid }
            val paint = if (pin.status == PinStatus.FIXED) fixedPinPaint else pinPaint
            
            // Размер точки зависит от силы сигнала (чем сильнее сигнал, тем больше точка)
            val pinSize = if (info != null) {
                // Нормализуем RSSI от -100 до -30 dBm в размер от 25 до 35
                val normalizedRssi = ((info.rssi + 100) / 70f).coerceIn(0f, 1f)
                25f + normalizedRssi * 10f
            } else {
                30f // Увеличен размер по умолчанию для лучшей видимости
            }
            
            // Рисуем точку с тенью для лучшей видимости
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 0, 0, 0) // Более темная тень
                style = Paint.Style.FILL
            }
            // Рисуем большую тень для контраста
            canvas.drawCircle(pin.positionPx.x + 3f, pin.positionPx.y + 3f, pinSize + 2f, shadowPaint)
            // Рисуем саму точку
            canvas.drawCircle(pin.positionPx.x, pin.positionPx.y, pinSize, paint)
            // Рисуем обводку
            canvas.drawCircle(pin.positionPx.x, pin.positionPx.y, pinSize, pinStroke)
            
            // Рисуем индикатор статуса (маленький квадрат)
            val statusIndicator = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (pin.status == PinStatus.FIXED) Color.YELLOW else Color.GREEN
                style = Paint.Style.FILL
            }
            canvas.drawRect(
                pin.positionPx.x - 6f,
                pin.positionPx.y - pinSize - 8f,
                pin.positionPx.x + 6f,
                pin.positionPx.y - pinSize + 4f,
                statusIndicator
            )
            
            // Рисуем техническую информацию о точке доступа
            if (info != null) {
                drawApInfo(canvas, pin.positionPx, info)
            }
        }
        
        // draw device position if available
        devicePositionPx?.let {
            // Рисуем тень для устройства
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 0, 0, 0)
                style = Paint.Style.FILL
            }
            canvas.drawCircle(it.x + 3f, it.y + 3f, 18f, shadowPaint)
            canvas.drawCircle(it.x, it.y, 18f, devicePaint)
            
            // Обводка для лучшей видимости
            val deviceStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            canvas.drawCircle(it.x, it.y, 18f, deviceStroke)
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
        
        // Восстанавливаем состояние canvas
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Всегда сначала обрабатываем масштабирование и жесты
        val scaleHandled = scaleDetector.onTouchEvent(event)
        val gestureHandled = gestureDetector.onTouchEvent(event)
        
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (calibrationMode) {
                    // В режиме калибровки обрабатываем все события
                    parent.requestDisallowInterceptTouchEvent(true)
                    if (calibrationPoints.size < 2) {
                        val mapPoint = screenToMap(PointF(event.x, event.y))
                        calibrationPoints.add(mapPoint)
                        onCalibrationPointsChanged?.invoke(calibrationPoints.toList())
                        invalidate()
                    }
                    return true
                }
                if (trainingMode) {
                    // В режиме обучения клик устанавливает позицию для сохранения отпечатка
                    val mapPoint = screenToMap(PointF(event.x, event.y))
                    onTrainingLocationSelected?.invoke(mapPoint)
                    return true
                }
                
                // Преобразуем координаты экрана в координаты карты для проверки попадания
                val mapPoint = screenToMap(PointF(event.x, event.y))
                val idx = hitTest(mapPoint.x, mapPoint.y)
                if (idx >= 0) {
                    val currentTime = System.currentTimeMillis()
                    // Проверяем на двойной тап для переключения статуса
                    if (lastTappedIndex == idx && (currentTime - lastTapTime) < 500) {
                        // Двойной тап - переключаем статус
                        togglePinStatus(pins[idx].bssid)
                        lastTapTime = 0
                        lastTappedIndex = -1
                        return true
                    }
                    lastTapTime = currentTime
                    lastTappedIndex = idx
                    
                    draggingIndex = idx
                    // Сохраняем координаты в системе карты
                    lastTouch.set(mapPoint.x, mapPoint.y)
                    return true
                } else {
                    lastTappedIndex = -1
                    isPanning = false
                    // Если клик не по пину - не блокируем события, позволяем карте обрабатывать
                    // Возвращаем false, чтобы событие могло пройти к MapWebView для перетаскивания карты
                    return false
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Начало жеста масштабирования (второй палец)
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Если обработан жест масштабирования, возвращаем true
                if (scaleHandled) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                
                if (draggingIndex >= 0) {
                    // Перетаскивание пина - обрабатываем
                    parent.requestDisallowInterceptTouchEvent(true)
                    val currentMapPoint = screenToMap(PointF(event.x, event.y))
                    val dx = currentMapPoint.x - lastTouch.x
                    val dy = currentMapPoint.y - lastTouch.y
                    pins[draggingIndex].positionPx.apply {
                        x += dx
                        y += dy
                    }
                    lastTouch.set(currentMapPoint.x, currentMapPoint.y)
                    invalidate()
                    return true
                }
                
                // Если обработан жест панорамирования overlay, возвращаем true
                if (gestureHandled) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                
                // Если это просто движение без обработки, не блокируем - позволяем карте обрабатывать
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                draggingIndex = -1
                isPanning = false
                parent.requestDisallowInterceptTouchEvent(false)
                onPinsChanged?.let { it(pins.toList()) }
                
                // Если обработан жест, возвращаем true
                if (gestureHandled || scaleHandled) {
                    return true
                }
                
                // Всегда возвращаем true для UP, чтобы завершить последовательность событий
                return true
            }
        }
        
        // Если обработали жест масштабирования или панорамирования, возвращаем true
        if (scaleHandled || gestureHandled) {
            return true
        }
        
        // Если обрабатываем перетаскивание пина, возвращаем true
        if (draggingIndex >= 0 || calibrationMode || trainingMode) {
            return true
        }
        
        // В остальных случаях возвращаем true, чтобы получать последующие события жестов
        return true
    }

    /**
     * Рисовать вектор (стрелку) от устройства к точке доступа
     */
    private fun drawVector(canvas: Canvas, from: PointF, to: PointF, distance: Float) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        
        if (length < 5f) return
        
        // Нормализуем направление
        val unitX = dx / length
        val unitY = dy / length
        
        // Вычисляем длину вектора (не до конца, а чуть короче для видимости)
        val vectorLength = min(length * 0.8f, 150f) // Увеличена максимальная длина
        val endX = from.x + unitX * vectorLength
        val endY = from.y + unitY * vectorLength
        
        // Рисуем линию вектора
        canvas.drawLine(from.x, from.y, endX, endY, vectorPaint)
        
        // Рисуем наконечник стрелки (треугольник) - увеличен размер
        val arrowSize = 18f
        val angle = kotlin.math.atan2(dy.toDouble(), dx.toDouble())
        
        val arrowX1 = endX - arrowSize * kotlin.math.cos(angle - PI / 6).toFloat()
        val arrowY1 = endY - arrowSize * kotlin.math.sin(angle - PI / 6).toFloat()
        val arrowX2 = endX - arrowSize * kotlin.math.cos(angle + PI / 6).toFloat()
        val arrowY2 = endY - arrowSize * kotlin.math.sin(angle + PI / 6).toFloat()
        
        val arrowPath = android.graphics.Path().apply {
            moveTo(endX, endY)
            lineTo(arrowX1, arrowY1)
            lineTo(arrowX2, arrowY2)
            close()
        }
        canvas.drawPath(arrowPath, vectorHeadPaint)
    }
    
    /**
     * Рисовать техническую информацию о точке доступа
     */
    private fun drawApInfo(canvas: Canvas, position: PointF, info: ApDistanceInfo) {
        val infoY = position.y + 35f
        val padding = 6f
        
        // Формируем текст информации
        val lines = listOf(
            info.ssid.take(15), // Ограничиваем длину SSID
            "${info.rssi} dBm",
            String.format("%.1fm", info.distanceMeters),
            info.getDirectionString()
        )
        
        var maxWidth = 0f
        val lineHeights = FloatArray(lines.size)
        for (i in lines.indices) {
            val width = apInfoTextPaint.measureText(lines[i])
            lineHeights[i] = apInfoTextPaint.fontMetrics.let { 
                it.descent - it.ascent 
            }
            maxWidth = max(maxWidth, width)
        }
        
        val totalHeight = lineHeights.sum() + padding * (lines.size + 1)
        val rectLeft = position.x - maxWidth / 2 - padding
        val rectTop = infoY - padding
        val rectRight = position.x + maxWidth / 2 + padding
        val rectBottom = infoY + totalHeight
        
        // Рисуем фон
        canvas.drawRoundRect(
            rectLeft,
            rectTop,
            rectRight,
            rectBottom,
            8f,
            8f,
            apInfoBgPaint
        )
        
        // Рисуем текст
        var currentY = infoY
        for (i in lines.indices) {
            val textX = position.x - maxWidth / 2
            canvas.drawText(lines[i], textX, currentY, apInfoTextPaint)
            currentY += lineHeights[i] + padding
        }
    }
    
    private fun hitTest(x: Float, y: Float): Int {
        // Учитываем масштаб при проверке попадания
        val hitRadius = 24.0 / scaleFactor.toDouble()
        for (i in pins.indices.reversed()) {
            val p = pins[i].positionPx
            if (hypot((x - p.x).toDouble(), (y - p.y).toDouble()) <= hitRadius) return i
        }
        return -1
    }
}
