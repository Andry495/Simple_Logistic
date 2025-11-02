package com.example.myapplication.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.webkit.WebView

/**
 * WebView с поддержкой масштабирования двумя пальцами
 */
class MapWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    private var scaleDetector: ScaleGestureDetector
    private var scaleFactor = 1.0f
    private var minScale = 0.5f
    private var maxScale = 3.0f

    init {
        // Настройки для поддержки масштабирования
        settings.apply {
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(true)
        }

        // Инициализация детектора жестов масштабирования
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(minScale, maxScale)
                
                // Масштабируем через JavaScript для карты
                evaluateJavascript("""
                    if (window.mapInstance) {
                        var currentZoom = window.mapInstance.getZoom();
                        var newZoom = currentZoom * $scaleFactor;
                        window.mapInstance.setZoom(newZoom);
                    }
                    if (window.myMap) {
                        var currentZoom = window.myMap.getZoom();
                        var newZoom = currentZoom * $scaleFactor;
                        window.myMap.setZoom(newZoom);
                    }
                """.trimIndent(), null)
                
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scaleFactor = 1.0f
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Если это жест масштабирования (несколько пальцев), обрабатываем сами
        if (event.pointerCount > 1) {
            // Передаем события в детектор масштабирования для двух пальцев
            val handled = scaleDetector.onTouchEvent(event)
            // Разрешаем обработку жеста масштабирования
            parent?.requestDisallowInterceptTouchEvent(true)
            // Вызываем super для базовой обработки WebView (для масштабирования карты)
            super.onTouchEvent(event)
            return handled
        }
        
        // Для одного пальца обрабатываем в WebView для перетаскивания карты
        return super.onTouchEvent(event)
    }
    
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        // Перехватываем жест масштабирования карты (несколько пальцев)
        if (event.pointerCount > 1) {
            return true
        }
        // Для одного пальца пропускаем события - карта будет обрабатывать их через onTouchEvent
        return false
    }
}

