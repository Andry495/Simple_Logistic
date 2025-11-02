package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.ScrollView
import android.webkit.WebView
import android.location.Location
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.positioning.HybridPositioningManager
import com.example.myapplication.positioning.PositioningMode
import com.example.myapplication.positioning.PositioningMath
import com.example.myapplication.wifi.WifiScanner
import com.example.myapplication.wifi.ApSignal
import com.example.myapplication.ui.FloorPlanView
import com.example.myapplication.ui.AccessPointPin
import com.example.myapplication.ui.ApDistanceInfo
import com.example.myapplication.ui.PinStatus
import com.example.myapplication.map.MapManager
import com.example.myapplication.map.MapProvider
import com.example.myapplication.location.GpsLocationManager
import android.graphics.PointF
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.PI
import android.view.ViewTreeObserver
import com.example.myapplication.data.ApRepository
import com.example.myapplication.data.SavedPin
import android.app.AlertDialog
import android.widget.EditText
import android.text.InputType
import android.os.Handler
import android.os.Looper


class MainActivity : AppCompatActivity() {

    private var floorPlanView: FloorPlanView? = null
    private var wifiScanner: WifiScanner? = null
    private var distancePxPerMm: Float = 0.002f // default scale: ~2 px per meter
    private lateinit var apRepository: ApRepository
    private lateinit var positioningManager: HybridPositioningManager
    private var mapManager: MapManager? = null
    private var gpsManager: GpsLocationManager? = null
    private var techInfoExpanded = true
    private var calibrating: Boolean = false
    private var trainingMode: Boolean = false
    private var trainingLocation: PointF? = null
    private var referenceRssi: Int = -50 // Reference RSSI at 1 meter distance (calibrated)
    private var autoScanning = false
    private var mapLoaded = false // Флаг загрузки карты
    private val scanHandler = Handler(Looper.getMainLooper())
    private var scanInterval: Long = 3000 // Интервал сканирования в мс (3 секунды по умолчанию)
    private val scanRunnable = object : Runnable {
        override fun run() {
            if (autoScanning) {
                startWifiScan()
                scanHandler.postDelayed(this, scanInterval)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ensurePermissions()

        floorPlanView = findViewById(R.id.floorPlanOverlay)
        wifiScanner = WifiScanner(this)
        apRepository = ApRepository(this)
        positioningManager = HybridPositioningManager(this)
        
        // Инициализация карты
        val mapWebView = findViewById<WebView>(R.id.mapWebView)
        mapManager = MapManager(mapWebView)
        // Загружаем OpenStreetMap по умолчанию (координаты Москвы)
        mapManager?.loadOpenStreetMap(55.7558, 37.6173, 17)
        
        // Инициализация GPS
        gpsManager = GpsLocationManager(this)
        
        // Автоматически загружаем карту по текущей GPS позиции (если доступна)
        if (gpsManager!!.hasPermissions()) {
            gpsManager?.startTracking(object : GpsLocationManager.LocationCallback {
                override fun onLocationUpdate(location: Location) {
                    runOnUiThread {
                        // Загружаем карту в центре текущей GPS позиции (только при первом запуске)
                        if (!mapLoaded) {
                            mapManager?.loadOpenStreetMap(location.latitude, location.longitude, 17)
                            mapLoaded = true
                        }
                        // Обновляем маркер GPS позиции на карте
                        mapManager?.updateUserLocation(location.latitude, location.longitude)
                    }
                }
                
                override fun onError(error: String) {
                    // Ошибка GPS - используем карту по умолчанию
                }
            })
        }

        // Устанавливаем параметры для позиционирования
        positioningManager.referenceRssi = referenceRssi
        positioningManager.distancePxPerMm = distancePxPerMm
        positioningManager.wifiUpdateInterval = 3000 // 3 секунды

        floorPlanView?.onPinsChanged = { pins ->
            apRepository.savePins(pins.map { SavedPin(it.bssid, it.positionPx.x, it.positionPx.y, it.status) })
        }

        // Load saved pins after layout is ready to get proper dimensions
        floorPlanView?.viewTreeObserver?.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                floorPlanView?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                val saved = apRepository.loadPins()
                if (saved.isNotEmpty()) {
                    floorPlanView?.setPins(saved.map { AccessPointPin(it.bssid, PointF(it.x, it.y), it.status) })
                }
                updateFingerprintCount()
            }
        })

        // Setup mode spinner
        val modeSpinner = findViewById<Spinner>(R.id.spinnerMode)
        val modes = arrayOf(
            "Triangulation",
            "Fingerprinting",
            "Triangulation + INS",
            "Hybrid (Best)"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modeSpinner.adapter = adapter
        modeSpinner.setSelection(2) // Default: Triangulation + INS

        modeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val mode = when (position) {
                    0 -> PositioningMode.TRILATERATION
                    1 -> PositioningMode.FINGERPRINTING
                    2 -> PositioningMode.TRIANGULATION_INS
                    3 -> PositioningMode.HYBRID
                    else -> PositioningMode.TRIANGULATION_INS
                }
                positioningManager.setMode(mode)
                updateFingerprintCount()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnRange).setOnClickListener {
            if (trainingMode) {
                saveFingerprintAtCurrentLocation()
            } else {
                toggleAutoScanning()
            }
        }
        
        findViewById<Button>(R.id.btnCalibrate).setOnClickListener {
            toggleCalibration()
        }

        findViewById<Button>(R.id.btnTrain).setOnClickListener {
            toggleTrainingMode()
        }

        findViewById<Button>(R.id.btnClearFingerprints).setOnClickListener {
            clearFingerprints()
        }
        
        findViewById<Button>(R.id.btnResetZoom).setOnClickListener {
            floorPlanView?.resetTransform()
        }
        
        // Кнопка возврата на свою геопозицию
        findViewById<Button>(R.id.btnMyLocation).setOnClickListener {
            returnToMyLocation()
        }
        
        // Кнопка сворачивания тех информации
        findViewById<Button>(R.id.btnToggleTechInfo).setOnClickListener {
            toggleTechInfo()
        }
        
        // Кнопка GPS калибровки
        findViewById<Button>(R.id.btnGpsCalibrate).setOnClickListener {
            startGpsCalibration()
        }

        // Update UI state based on current permissions
        refreshButtonState()
    }
    
    /**
     * Переключить видимость технической информации
     */
    private fun toggleTechInfo() {
        techInfoExpanded = !techInfoExpanded
        val scrollView = findViewById<ScrollView>(R.id.scrollTechnicalInfo)
        val toggleBtn = findViewById<Button>(R.id.btnToggleTechInfo)
        
        if (techInfoExpanded) {
            scrollView.visibility = android.view.View.VISIBLE
            toggleBtn.text = "▼"
        } else {
            scrollView.visibility = android.view.View.GONE
            toggleBtn.text = "▲"
        }
    }
    
    /**
     * Начать GPS калибровку для привязки карты
     */
    private fun startGpsCalibration() {
        if (gpsManager == null || !gpsManager!!.hasPermissions()) {
            Toast.makeText(this, "GPS permissions required", Toast.LENGTH_SHORT).show()
            ensurePermissions()
            return
        }
        
        Toast.makeText(this, "Tap 2 points on map after getting GPS location", Toast.LENGTH_LONG).show()
        
        var gpsCalibrationPoint1: Location? = null
        var pixelCalibrationPoint1: PointF? = null
        var calibrationStep = 0
        
        // Начинаем отслеживание GPS
        gpsManager?.startTracking(object : GpsLocationManager.LocationCallback {
            override fun onLocationUpdate(location: Location) {
                runOnUiThread {
                    val statusText = findViewById<TextView>(R.id.tvStatus)
                    statusText?.text = "GPS: ${String.format("%.6f", location.latitude)}, ${String.format("%.6f", location.longitude)}"
                }
            }
            
            override fun onError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "GPS error: $error", Toast.LENGTH_SHORT).show()
                }
            }
        })
        
        // Включаем режим калибровки карты
        floorPlanView?.calibrationMode = true
        floorPlanView?.onCalibrationPointsChanged = { points ->
            if (points.size == 2) {
                val gpsLoc1 = gpsManager?.getLastLocation()
                if (gpsLoc1 != null) {
                    // Для второй точки запрашиваем GPS еще раз через небольшую задержку
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val gpsLoc2 = gpsManager?.getLastLocation()
                        if (gpsLoc2 != null) {
                            // Калибруем карту
                            mapManager?.calibrateGpsToPixels(
                                gpsLoc1, points[0],
                                gpsLoc2, points[1]
                            )
                            
                            Toast.makeText(this@MainActivity, "GPS calibration completed", Toast.LENGTH_SHORT).show()
                            floorPlanView?.calibrationMode = false
                            gpsManager?.stopTracking()
                        } else {
                            Toast.makeText(this@MainActivity, "Failed to get second GPS point", Toast.LENGTH_SHORT).show()
                        }
                    }, 2000)
                } else {
                    Toast.makeText(this@MainActivity, "GPS location not available. Wait for GPS fix.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * Вернуться на свою геопозицию
     */
    private fun returnToMyLocation() {
        if (gpsManager == null || !gpsManager!!.hasPermissions()) {
            Toast.makeText(this, "GPS permissions required", Toast.LENGTH_SHORT).show()
            ensurePermissions()
            return
        }
        
        val lastLocation = gpsManager?.getLastLocation()
        if (lastLocation != null) {
            // Центрируем карту на текущей GPS позиции
            mapManager?.centerOnLocation(lastLocation.latitude, lastLocation.longitude)
            Toast.makeText(this, "Centered on GPS location", Toast.LENGTH_SHORT).show()
        } else {
            // Пробуем получить текущую позицию
            gpsManager?.startTracking(object : GpsLocationManager.LocationCallback {
                override fun onLocationUpdate(location: Location) {
                    runOnUiThread {
                        mapManager?.centerOnLocation(location.latitude, location.longitude)
                        Toast.makeText(this@MainActivity, "Centered on GPS location", Toast.LENGTH_SHORT).show()
                        gpsManager?.stopTracking()
                    }
                }
                
                override fun onError(error: String) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to get GPS location: $error", Toast.LENGTH_SHORT).show()
                        gpsManager?.stopTracking()
                    }
                }
            })
            
            // Останавливаем отслеживание через 5 секунд, если не получили позицию
            Handler(Looper.getMainLooper()).postDelayed({
                gpsManager?.stopTracking()
            }, 5000)
        }
    }

    /**
     * Переключить автоматическое сканирование
     */
    private fun toggleAutoScanning() {
        autoScanning = !autoScanning
        val scanBtn = findViewById<Button>(R.id.btnRange)
        
        if (autoScanning) {
            scanBtn.text = "Stop Scanning"
            // Начинаем периодическое сканирование
            startWifiScan()
            scanHandler.postDelayed(scanRunnable, scanInterval)
        } else {
            scanBtn.text = "Start Scanning"
            scanHandler.removeCallbacks(scanRunnable)
        }
    }
    
    override fun onPause() {
        super.onPause()
        positioningManager.stopTracking()
        if (autoScanning) {
            toggleAutoScanning() // Остановим автоматическое сканирование
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        positioningManager.stopTracking()
        gpsManager?.stopTracking()
        autoScanning = false
        scanHandler.removeCallbacksAndMessages(null)
    }

    private fun ensurePermissions() {
        val required = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val toRequest = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 1001)
        }
    }

    private fun refreshButtonState() {
        val haveLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val haveWifi = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
        val haveChangeWifi = ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
        val haveNearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED else true
        val scanBtn = findViewById<Button>(R.id.btnRange)
        scanBtn.isEnabled = haveLocation && haveWifi && haveChangeWifi && haveNearby
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val denied = grantResults.any { it != PackageManager.PERMISSION_GRANTED }
            if (denied) {
                Toast.makeText(this, "Permissions denied — Wi‑Fi scanning disabled", Toast.LENGTH_SHORT).show()
            }
            refreshButtonState()
        }
    }

    private fun startWifiScan() {
        val scanner = wifiScanner ?: return
        scanner.scanOnce(object : WifiScanner.Listener {
            override fun onSignals(results: List<ApSignal>) {
                runOnUiThread {
                    val overlay = floorPlanView ?: return@runOnUiThread
                    val currentPins = overlay.getPins().associateBy { it.bssid }.toMutableMap()
                    
                    // If no pins exist, auto-place them in a circle
                    if (currentPins.isEmpty()) {
                        val width = overlay.width
                        val height = overlay.height
                        
                        if (width > 0 && height > 0) {
                            val cx = width / 2f
                            val cy = height / 2f
                            var angle = 0.0
                            val radius = (width.coerceAtMost(height)) * 0.3f
                            for (signal in results) {
                                val x = cx + (radius * kotlin.math.cos(angle)).toFloat()
                                val y = cy + (radius * kotlin.math.sin(angle)).toFloat()
                                currentPins[signal.bssid] = AccessPointPin(signal.bssid, PointF(x, y), PinStatus.FREE)
                                angle += (2 * Math.PI) / results.size
                            }
                            overlay.setPins(currentPins.values.toList())
                            // Save auto-placed pins
                            apRepository.savePins(currentPins.values.map { SavedPin(it.bssid, it.positionPx.x, it.positionPx.y, it.status) })
                        } else {
                            // Если размеры еще не готовы, используем дефолтные значения
                            val cx = 500f // Дефолтный центр
                            val cy = 500f
                            var angle = 0.0
                            val radius = 200f
                            for (signal in results) {
                                val x = cx + (radius * kotlin.math.cos(angle)).toFloat()
                                val y = cy + (radius * kotlin.math.sin(angle)).toFloat()
                                currentPins[signal.bssid] = AccessPointPin(signal.bssid, PointF(x, y), PinStatus.FREE)
                                angle += (2 * Math.PI) / results.size
                            }
                            overlay.setPins(currentPins.values.toList())
                            apRepository.savePins(currentPins.values.map { SavedPin(it.bssid, it.positionPx.x, it.positionPx.y, it.status) })
                        }
                    }

                    // Преобразуем позиции точек доступа в формат для менеджера
                    val knownApPositions = currentPins.mapValues { it.value.positionPx }

                    // Используем гибридный менеджер для определения позиции
                    positioningManager.processWifiScan(
                        signals = results,
                        knownApPositions = knownApPositions,
                        listener = object : HybridPositioningManager.PositionUpdateListener {
                            override fun onPositionUpdate(position: PointF, confidence: Float, method: String) {
                                runOnUiThread {
                                    overlay.devicePositionPx = position
                                    val statusText = findViewById<TextView>(R.id.tvStatus)
                                    statusText?.text = "Method: $method | Confidence: ${String.format("%.2f", confidence)}"
                                    
                                    // Вычисляем расстояния и направления до всех точек доступа
                                    val distanceInfos = calculateDistanceInfos(
                                        devicePosition = position,
                                        signals = results,
                                        apPositions = currentPins,
                                        distancePxPerMm = distancePxPerMm
                                    )
                                    overlay.setDistanceInfos(distanceInfos)
                                    updateTechnicalInfo(distanceInfos)
                                    
                                    // Автоматическая коррекция позиций свободных точек доступа
                                    autoAdjustPinPositions(position, results, currentPins, distancePxPerMm)
                                    
                                    // Обновляем маркеры точек доступа на карте
                                    updateApMarkersOnMap(results, currentPins, position, distanceInfos)
                                    
                                    // Если есть GPS позиция, обновляем маркер устройства на карте
                                    gpsManager?.getLastLocation()?.let { gpsLocation ->
                                        mapManager?.updateUserLocation(gpsLocation.latitude, gpsLocation.longitude)
                                    }
                                }
                            }

                            override fun onError(error: String) {
                                runOnUiThread {
                                    // Не показываем Toast для некритичных ошибок при автосканировании
                                    // Обновляем только статус
                                    val statusText = findViewById<TextView>(R.id.tvStatus)
                                    if (statusText != null && !autoScanning) {
                                        // Показываем Toast только если не в режиме автосканирования
                                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                                    }
                                    statusText?.text = "Error: $error"
                                }
                            }
                        }
                    )
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun toggleTrainingMode() {
        trainingMode = !trainingMode
        val trainBtn = findViewById<Button>(R.id.btnTrain)
        val scanBtn = findViewById<Button>(R.id.btnRange)
        val overlay = floorPlanView ?: return
        
        overlay.trainingMode = trainingMode
        
        if (trainingMode) {
            trainBtn.text = "Stop Training"
            scanBtn.text = "Save Fingerprint"
            Toast.makeText(this, "Tap on map to set location, then tap 'Save Fingerprint'", Toast.LENGTH_LONG).show()
            
            // Обработка клика по карте для установки позиции обучения
            overlay.onTrainingLocationSelected = { location ->
                trainingLocation = location
                overlay.devicePositionPx = location // Показываем выбранную позицию
                Toast.makeText(this, "Location set at (${String.format("%.1f", location.x)}, ${String.format("%.1f", location.y)})", Toast.LENGTH_SHORT).show()
            }
        } else {
            trainBtn.text = "Train Fingerprinting"
            scanBtn.text = "Scan Wi‑Fi"
            overlay.onTrainingLocationSelected = null
            trainingLocation = null
            Toast.makeText(this, "Training mode disabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveFingerprintAtCurrentLocation() {
        val overlay = floorPlanView ?: return
        // Используем выбранную позицию обучения или текущую позицию устройства
        val position = trainingLocation ?: overlay.devicePositionPx
        
        if (position == null) {
            Toast.makeText(this, "Please tap on map to set location first", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Сканируем Wi-Fi и сохраняем отпечаток
        val scanner = wifiScanner ?: return
        scanner.scanOnce(object : WifiScanner.Listener {
            override fun onSignals(results: List<ApSignal>) {
                runOnUiThread {
                    positioningManager.saveFingerprint(results, position)
                    updateFingerprintCount()
                    Toast.makeText(this@MainActivity, "Fingerprint saved at (${String.format("%.1f", position.x)}, ${String.format("%.1f", position.y)})", Toast.LENGTH_SHORT).show()
                    trainingLocation = null // Сбрасываем выбранную позицию
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error saving fingerprint: $error", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun updateFingerprintCount() {
        val count = positioningManager.getFingerprintCount()
        val countText = findViewById<TextView>(R.id.tvFingerprintCount)
        countText?.text = "Fingerprints: $count"
        
        val mode = positioningManager.getMode()
        if (mode == PositioningMode.FINGERPRINTING || mode == PositioningMode.HYBRID) {
            if (count == 0) {
                countText?.text = "⚠️ No fingerprints! Train first."
            }
        }
    }

    private fun clearFingerprints() {
        AlertDialog.Builder(this)
            .setTitle("Clear Fingerprints")
            .setMessage("Delete all saved fingerprints? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                positioningManager.clearFingerprints()
                updateFingerprintCount()
                Toast.makeText(this, "All fingerprints cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleCalibration() {
        val overlay = floorPlanView ?: return
        calibrating = !calibrating
        overlay.calibrationMode = calibrating
        val btn = findViewById<Button>(R.id.btnCalibrate)
        btn.text = if (calibrating) "Tap 2 points" else "Calibrate"
        if (calibrating) {
            overlay.onCalibrationPointsChanged = { pts: List<PointF> ->
                if (pts.size == 2) {
                    promptRealDistanceMeters { meters ->
                        if (meters != null && meters > 0f) {
                            val dx = pts[1].x - pts[0].x
                            val dy = pts[1].y - pts[0].y
                            val px = kotlin.math.sqrt(dx * dx + dy * dy)
                            distancePxPerMm = px / (meters * 1000f)
                            positioningManager.distancePxPerMm = distancePxPerMm
                            val msg = "Scale set: %.4f px/mm".format(distancePxPerMm)
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        }
                        calibrating = false
                        overlay.calibrationMode = false
                        btn.text = "Calibrate"
                    }
                }
            }
        } else {
            overlay.onCalibrationPointsChanged = null
        }
    }

    /**
     * Обновить маркеры точек доступа на карте
     */
    private fun updateApMarkersOnMap(
        signals: List<ApSignal>,
        pins: Map<String, AccessPointPin>,
        devicePosition: PointF,
        distanceInfos: List<ApDistanceInfo>
    ) {
        val gpsLocation = gpsManager?.getLastLocation()
        
        if (mapManager?.isCalibrated() == true && gpsLocation != null) {
            // Если есть GPS калибровка - преобразуем пиксели в GPS координаты
            for ((bssid, pin) in pins) {
                val apGps = mapManager?.pixelsToGps(pin.positionPx)
                val signal = signals.find { it.bssid == bssid }
                apGps?.let { gps ->
                    mapManager?.updateApMarker(
                        bssid = bssid,
                        ssid = signal?.ssid ?: "Unknown",
                        lat = gps.latitude,
                        lon = gps.longitude
                    )
                }
            }
        } else if (gpsLocation != null && distanceInfos.isNotEmpty()) {
            // Если нет калибровки, но есть GPS позиция - размещаем точки относительно GPS позиции
            // Используем расстояния и направления из distanceInfos
            for (info in distanceInfos) {
                // Преобразуем направление из радиан в градусы и корректируем для GPS координат
                // bearing в ApDistanceInfo: 0 = север, π/2 = восток
                // В GPS: север = 0°, восток = 90°
                val bearingDeg = info.angleDegrees
                
                // Преобразуем расстояние из метров в градусы (примерно)
                // 1 градус широты ≈ 111 км
                val latDelta = (info.distanceMeters / 111000.0) * kotlin.math.cos(Math.toRadians(bearingDeg.toDouble()))
                // 1 градус долготы ≈ 111 км * cos(широта)
                val lonDelta = (info.distanceMeters / (111000.0 * kotlin.math.cos(Math.toRadians(gpsLocation.latitude)))) * kotlin.math.sin(Math.toRadians(bearingDeg.toDouble()))
                
                val apLat = gpsLocation.latitude + latDelta
                val apLon = gpsLocation.longitude + lonDelta
                
                val signal = signals.find { it.bssid == info.bssid }
                mapManager?.updateApMarker(
                    bssid = info.bssid,
                    ssid = signal?.ssid ?: info.ssid,
                    lat = apLat,
                    lon = apLon
                )
            }
        } else {
            // Если нет GPS - не можем отобразить на карте
            android.util.Log.d("MainActivity", "Cannot show AP markers: GPS=${gpsLocation != null}, distanceInfos=${distanceInfos.size}, calibrated=${mapManager?.isCalibrated()}")
        }
    }

    private fun calculateDistanceInfos(
        devicePosition: PointF,
        signals: List<ApSignal>,
        apPositions: Map<String, AccessPointPin>,
        distancePxPerMm: Float
    ): List<ApDistanceInfo> {
        return signals.mapNotNull { signal ->
            val pin = apPositions[signal.bssid] ?: return@mapNotNull null
            
            // Вычисляем расстояние в пикселях
            val dx = pin.positionPx.x - devicePosition.x
            val dy = pin.positionPx.y - devicePosition.y
            val distancePx = sqrt(dx * dx + dy * dy)
            
            // Конвертируем в метры (если distancePxPerMm установлен)
            val distanceMeters = if (distancePxPerMm > 0) {
                (distancePx / distancePxPerMm) / 1000f // пиксели -> мм -> метры
            } else {
                distancePx / 100f // Примерная конвертация (100 px = 1 м)
            }
            
            // Вычисляем направление (bearing) в радианах
            // В системе координат Android: 0 радиан = вправо, π/2 = вниз
            // Но мы хотим: 0 = север (вверх), π/2 = восток (вправо)
            val bearing = atan2(dy.toDouble(), dx.toDouble()).toFloat() - (PI.toFloat() / 2f) // Поворачиваем на -90 градусов
            val normalizedBearing = if (bearing < 0f) bearing + (2f * PI.toFloat()) else bearing
            
            // Конвертируем в градусы (0-360)
            val angleDegrees = Math.toDegrees(normalizedBearing.toDouble()).toFloat()
            
            ApDistanceInfo(
                bssid = signal.bssid,
                ssid = signal.ssid,
                distancePx = distancePx,
                distanceMeters = distanceMeters,
                bearing = normalizedBearing,
                rssi = signal.rssi,
                angleDegrees = angleDegrees
            )
        }.sortedByDescending { it.rssi } // Сортируем по силе сигнала
    }
    
    /**
     * Автоматическая коррекция позиций свободных точек доступа на основе измерений
     */
    private fun autoAdjustPinPositions(
        devicePosition: PointF,
        signals: List<ApSignal>,
        apPositions: Map<String, AccessPointPin>,
        distancePxPerMm: Float
    ) {
        val overlay = floorPlanView ?: return
        
        signals.forEach { signal ->
            val pin = apPositions[signal.bssid] ?: return@forEach
            if (pin.status == PinStatus.FREE) {
                // Вычисляем ожидаемое расстояние на основе RSSI
                val expectedDistancePx = PositioningMath.rssiToDistancePx(
                    rssi = signal.rssi,
                    referenceRssi = referenceRssi,
                    referenceDistancePx = 100f * distancePxPerMm * 1000f,
                    pathLossExponent = 2.5f
                )
                
                // Вычисляем вектор от устройства к точке доступа
                val dx = pin.positionPx.x - devicePosition.x
                val dy = pin.positionPx.y - devicePosition.y
                val actualDistancePx = sqrt(dx * dx + dy * dy)
                
                // Вычисляем ошибку
                val error = expectedDistancePx - actualDistancePx
                
                // Если ошибка значительная (больше 10% расстояния), корректируем позицию
                if (kotlin.math.abs(error) > actualDistancePx * 0.1f && actualDistancePx > 10f) {
                    // Вычисляем новую позицию точки доступа
                    val directionX = if (actualDistancePx > 0.1f) dx / actualDistancePx else 0f
                    val directionY = if (actualDistancePx > 0.1f) dy / actualDistancePx else 0f
                    
                    val newX = devicePosition.x + directionX * expectedDistancePx
                    val newY = devicePosition.y + directionY * expectedDistancePx
                    
                    // Плавная коррекция с learning rate
                    overlay.adjustPinPosition(signal.bssid, PointF(newX, newY), learningRate = 0.15f)
                }
            }
        }
    }
    
    /**
     * Обновить техническую информацию в UI
     */
    private fun updateTechnicalInfo(infos: List<ApDistanceInfo>) {
        val techInfoText = findViewById<TextView>(R.id.tvTechnicalInfo)
        if (techInfoText == null) return
        
        if (infos.isEmpty()) {
            techInfoText.text = "No access points"
            return
        }
        
        val sb = StringBuilder()
        sb.append("AP Details:\n")
        infos.take(5).forEachIndexed { index, info ->
            sb.append("${index + 1}. ${info.ssid}\n")
            sb.append("   Dist: ${String.format("%.2f", info.distanceMeters)}m | ")
            sb.append("Dir: ${String.format("%.0f°", info.angleDegrees)} (${info.getDirectionString()}) | ")
            sb.append("RSSI: ${info.rssi} dBm\n")
        }
        
        techInfoText.text = sb.toString()
    }

    private fun promptRealDistanceMeters(callback: (Float?) -> Unit) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = "Distance in meters"
        AlertDialog.Builder(this)
            .setTitle("Calibration distance")
            .setView(input)
            .setPositiveButton("OK") { d, _ ->
                val text = input.text?.toString()?.trim()
                val value = text?.toFloatOrNull()
                callback(value)
                d.dismiss()
            }
            .setNegativeButton("Cancel") { d, _ ->
                callback(null)
                d.dismiss()
            }
            .show()
    }
}
