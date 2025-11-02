package com.example.myapplication.map

import android.content.Context
import android.graphics.PointF
import android.location.Location
import android.view.ScaleGestureDetector
import android.webkit.WebView
import android.webkit.WebViewClient

enum class MapProvider {
    GOOGLE_MAPS,
    YANDEX_MAPS,
    NONE
}

/**
 * Менеджер для работы с картами через WebView
 */
class MapManager(private val webView: WebView) {
    
    private var currentProvider: MapProvider = MapProvider.NONE
    private var mapCenterLat: Double = 55.7558 // Москва по умолчанию
    private var mapCenterLon: Double = 37.6173
    private var zoomLevel: Int = 17
    
    // Коэффициенты преобразования GPS -> пиксели карты
    private var gpsToPixelScaleX: Float = 1f
    private var gpsToPixelScaleY: Float = 1f
    private var gpsOffsetX: Float = 0f
    private var gpsOffsetY: Float = 0f
    private var isCalibrated = false
    
    init {
        setupWebView()
    }
    
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = false
            displayZoomControls = false
            // Включаем поддержку масштабирования жестами
            setSupportZoom(true)
            builtInZoomControls = false
        }
        webView.webViewClient = WebViewClient()
    }
    
    /**
     * Загрузить карту Google Maps
     */
    fun loadGoogleMaps(lat: Double, lon: Double, zoom: Int = 17) {
        currentProvider = MapProvider.GOOGLE_MAPS
        mapCenterLat = lat
        mapCenterLon = lon
        zoomLevel = zoom
        
        // Используем статический API Google Maps (не требует API ключа для простых случаев)
        // Или можно использовать embed карту
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { margin: 0; padding: 0; }
                    #map { width: 100%; height: 100vh; }
                </style>
            </head>
            <body>
                <iframe
                    id="map"
                    frameborder="0"
                    style="border:0"
                    src="https://www.google.com/maps/embed/v1/view?key=AIzaSyBFw0Qbyq9zTFTd-tUY6d-s6Y4cHHuWGcY&center=$lat,$lon&zoom=$zoom"
                    allowfullscreen>
                </iframe>
                <script>
                    // Альтернатива через Leaflet (OpenStreetMap) - не требует API ключа
                </script>
            </body>
            </html>
        """.trimIndent()
        
        // Используем OpenStreetMap через Leaflet (не требует API ключа)
        loadOpenStreetMap(lat, lon, zoom)
    }
    
    /**
     * Загрузить карту через OpenStreetMap (не требует API ключа)
     */
    fun loadOpenStreetMap(lat: Double, lon: Double, zoom: Int = 17) {
        currentProvider = MapProvider.GOOGLE_MAPS // Используем как основной провайдер
        mapCenterLat = lat
        mapCenterLon = lon
        zoomLevel = zoom
        
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
                <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                <style>
                    body { margin: 0; padding: 0; }
                    #map { width: 100%; height: 100vh; }
                </style>
            </head>
            <body>
                <div id="map"></div>
                <script>
                    var map = L.map('map').setView([$lat, $lon], $zoom);
                    
                    // Добавляем тайлы OpenStreetMap
                    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                        attribution: '© OpenStreetMap contributors',
                        maxZoom: 19
                    }).addTo(map);
                    
                    // Инициализируем хранилище маркеров
                    if (typeof window.mapMarkers === 'undefined') window.mapMarkers = {};
                    window.mapUserMarker = null;
                    if (typeof window.mapApPlacemarks === 'undefined') window.mapApPlacemarks = {};
                    
                    // Включаем масштабирование и перетаскивание карты
                    map.touchZoom.enable();
                    map.doubleClickZoom.enable();
                    map.dragging.enable(); // Включаем перетаскивание карты
                    map.scrollWheelZoom.disable();
                    map.boxZoom.disable();
                    map.keyboard.disable();
                    if (map.tap) map.tap.disable();
                    
                    // Сохраняем ссылку на карту для управления через JavaScript
                    window.mapInstance = map;
                </script>
            </body>
            </html>
        """.trimIndent()
        
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
    
    /**
     * Загрузить Яндекс.Карты
     */
    fun loadYandexMaps(lat: Double, lon: Double, zoom: Int = 17, apiKey: String = "") {
        currentProvider = MapProvider.YANDEX_MAPS
        mapCenterLat = lat
        mapCenterLon = lon
        zoomLevel = zoom
        
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <script src="https://api-maps.yandex.ru/2.1/?lang=ru_RU&apikey=$apiKey" type="text/javascript"></script>
                <style>
                    body { margin: 0; padding: 0; }
                    #map { width: 100%; height: 100vh; }
                </style>
            </head>
            <body>
                <div id="map"></div>
                <script>
                    ymaps.ready(function () {
                        var myMap = new ymaps.Map('map', {
                            center: [$lon, $lat],
                            zoom: $zoom,
                            controls: []
                        });
                        
                        var myPlacemark = new ymaps.Placemark([$lon, $lat], {}, {
                            preset: 'islands#redDotIcon'
                        });
                        myMap.geoObjects.add(myPlacemark);
                        
                        // Включаем масштабирование и перетаскивание
                        myMap.behaviors.enable('multiTouch');
                        myMap.behaviors.enable('drag'); // Включаем перетаскивание
                    });
                </script>
            </body>
            </html>
        """.trimIndent()
        
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
    
    /**
     * Увеличить масштаб карты
     */
    fun zoomIn() {
        zoomLevel = (zoomLevel + 1).coerceAtMost(19)
        updateMapZoom()
    }
    
    /**
     * Уменьшить масштаб карты
     */
    fun zoomOut() {
        zoomLevel = (zoomLevel - 1).coerceAtLeast(1)
        updateMapZoom()
    }
    
    /**
     * Установить уровень масштаба
     */
    fun setZoom(level: Int) {
        zoomLevel = level.coerceIn(1, 19)
        updateMapZoom()
    }
    
    /**
     * Обновить масштаб карты через JavaScript
     */
    private fun updateMapZoom() {
        when (currentProvider) {
            MapProvider.GOOGLE_MAPS, MapProvider.NONE -> {
                // Для OpenStreetMap через Leaflet
                val js = "if (window.mapInstance) { window.mapInstance.setZoom($zoomLevel); }"
                webView.evaluateJavascript(js, null)
            }
            MapProvider.YANDEX_MAPS -> {
                val js = "if (window.myMap) { window.myMap.setZoom($zoomLevel); }"
                webView.evaluateJavascript(js, null)
            }
        }
    }
    
    /**
     * Калибровка: привязка GPS координат к пикселям карты
     */
    fun calibrateGpsToPixels(
        gpsPoint1: Location,
        pixelPoint1: PointF,
        gpsPoint2: Location,
        pixelPoint2: PointF
    ) {
        // Вычисляем масштабы по X и Y
        val gpsDx = (gpsPoint2.longitude - gpsPoint1.longitude).toFloat()
        val gpsDy = (gpsPoint2.latitude - gpsPoint1.latitude).toFloat()
        val pixelDx = pixelPoint2.x - pixelPoint1.x
        val pixelDy = pixelPoint2.y - pixelPoint1.y
        
        if (kotlin.math.abs(gpsDx) > 1e-6f && kotlin.math.abs(gpsDy) > 1e-6f) {
            gpsToPixelScaleX = pixelDx / gpsDx
            gpsToPixelScaleY = pixelDy / gpsDy
            
            // Вычисляем смещения
            gpsOffsetX = pixelPoint1.x - (gpsPoint1.longitude.toFloat() * gpsToPixelScaleX)
            gpsOffsetY = pixelPoint1.y - (gpsPoint1.latitude.toFloat() * gpsToPixelScaleY)
            
            isCalibrated = true
        }
    }
    
    /**
     * Преобразовать GPS координаты в пиксели карты
     */
    fun gpsToPixels(location: Location): PointF? {
        if (!isCalibrated) return null
        return PointF(
            (location.longitude.toFloat() * gpsToPixelScaleX + gpsOffsetX),
            (location.latitude.toFloat() * gpsToPixelScaleY + gpsOffsetY)
        )
    }
    
    /**
     * Преобразовать пиксели карты в GPS координаты
     */
    fun pixelsToGps(pixel: PointF): Location? {
        if (!isCalibrated) return null
        val location = Location("map")
        location.longitude = (pixel.x - gpsOffsetX) / gpsToPixelScaleX.toDouble()
        location.latitude = (pixel.y - gpsOffsetY) / gpsToPixelScaleY.toDouble()
        return location
    }
    
    fun isCalibrated(): Boolean = isCalibrated
    
    fun getCurrentCenter(): Pair<Double, Double> = Pair(mapCenterLat, mapCenterLon)
    
    fun getCurrentProvider(): MapProvider = currentProvider
    
    /**
     * Центрировать карту на указанных GPS координатах
     */
    fun centerOnLocation(lat: Double, lon: Double) {
        mapCenterLat = lat
        mapCenterLon = lon
        
        when (currentProvider) {
            MapProvider.GOOGLE_MAPS, MapProvider.NONE -> {
                // Для OpenStreetMap через Leaflet
                val js = """
                    if (window.mapInstance) { 
                        window.mapInstance.setView([$lat, $lon], $zoomLevel); 
                    }
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
            MapProvider.YANDEX_MAPS -> {
                val js = """
                    if (window.myMap) { 
                        window.myMap.setCenter([$lon, $lat], $zoomLevel); 
                    }
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
        }
    }
    
    /**
     * Обновить маркер GPS позиции пользователя на карте
     */
    fun updateUserLocation(lat: Double, lon: Double) {
        when (currentProvider) {
            MapProvider.GOOGLE_MAPS, MapProvider.NONE -> {
                val js = """
                    if (window.mapInstance) {
                        if (window.mapUserMarker) {
                            window.mapUserMarker.setLatLng([$lat, $lon]);
                        } else {
                            window.mapUserMarker = L.marker([$lat, $lon], {
                                icon: L.icon({
                                    iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-blue.png',
                                    shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
                                    iconSize: [25, 41],
                                    iconAnchor: [12, 41],
                                    popupAnchor: [1, -34],
                                    shadowSize: [41, 41]
                                })
                            }).addTo(window.mapInstance);
                            window.mapUserMarker.bindPopup('Ваша позиция').openPopup();
                        }
                    }
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
            MapProvider.YANDEX_MAPS -> {
                val js = """
                    if (window.myMap && window.mapUserPlacemark) {
                        window.mapUserPlacemark.geometry.setCoordinates([$lon, $lat]);
                    } else if (window.myMap) {
                        window.mapUserPlacemark = new ymaps.Placemark([$lon, $lat], {
                            balloonContent: 'Ваша позиция'
                        }, {
                            preset: 'islands#blueCircleDotIcon'
                        });
                        window.myMap.geoObjects.add(window.mapUserPlacemark);
                    }
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
        }
    }
    
    /**
     * Добавить или обновить маркер точки доступа на карте
     */
    fun updateApMarker(bssid: String, ssid: String, lat: Double, lon: Double) {
        // Экранируем спецсимволы в строках для JavaScript
        val safeBssid = bssid.replace("'", "\\'").replace("\"", "\\\"")
        val safeSsid = ssid.replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        
        when (currentProvider) {
            MapProvider.GOOGLE_MAPS, MapProvider.NONE -> {
                val js = """
                    (function() {
                        if (window.mapInstance) {
                            if (!window.mapMarkers) window.mapMarkers = {};
                            var bssid = '$safeBssid';
                            if (window.mapMarkers[bssid]) {
                                window.mapMarkers[bssid].setLatLng([$lat, $lon]);
                            } else {
                                var marker = L.marker([$lat, $lon], {
                                    icon: L.icon({
                                        iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-red.png',
                                        shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
                                        iconSize: [25, 41],
                                        iconAnchor: [12, 41],
                                        popupAnchor: [1, -34],
                                        shadowSize: [41, 41]
                                    })
                                });
                                marker.addTo(window.mapInstance);
                                marker.bindPopup('$safeSsid<br>BSSID: ' + bssid);
                                window.mapMarkers[bssid] = marker;
                            }
                        }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
            MapProvider.YANDEX_MAPS -> {
                val js = """
                    if (window.myMap) {
                        if (!window.mapApPlacemarks) window.mapApPlacemarks = {};
                        if (window.mapApPlacemarks['$bssid']) {
                            window.mapApPlacemarks['$bssid'].geometry.setCoordinates([$lon, $lat]);
                        } else {
                            window.mapApPlacemarks['$bssid'] = new ymaps.Placemark([$lon, $lat], {
                                balloonContent: '$ssid<br>BSSID: $bssid'
                            }, {
                                preset: 'islands#redCircleDotIcon'
                            });
                            window.myMap.geoObjects.add(window.mapApPlacemarks['$bssid']);
                        }
                    }
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
        }
    }
    
    /**
     * Удалить маркер точки доступа
     */
    fun removeApMarker(bssid: String) {
        when (currentProvider) {
            MapProvider.GOOGLE_MAPS, MapProvider.NONE -> {
                val js = """
                    if (window.mapInstance && window.mapMarkers && window.mapMarkers['$bssid']) {
                        window.mapInstance.removeLayer(window.mapMarkers['$bssid']);
                        delete window.mapMarkers['$bssid'];
                    }
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
            MapProvider.YANDEX_MAPS -> {
                val js = """
                    if (window.myMap && window.mapApPlacemarks && window.mapApPlacemarks['$bssid']) {
                        window.myMap.geoObjects.remove(window.mapApPlacemarks['$bssid']);
                        delete window.mapApPlacemarks['$bssid'];
                    }
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
        }
    }
}

