package com.example.myapplication


import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.positioning.PositioningMath
import com.example.myapplication.positioning.Anchor
import com.example.myapplication.wifi.WifiScanner
import com.example.myapplication.wifi.ApSignal
import com.example.myapplication.ui.FloorPlanView
import com.example.myapplication.ui.AccessPointPin
import android.graphics.PointF
import android.view.ViewTreeObserver
import com.example.myapplication.data.ApRepository
import com.example.myapplication.data.SavedPin
import android.app.AlertDialog
import android.widget.EditText
import android.text.InputType


class MainActivity : AppCompatActivity() {

    private var floorPlanView: FloorPlanView? = null
    private var wifiScanner: WifiScanner? = null
    private var distancePxPerMm: Float = 0.002f // default scale: ~2 px per meter
    private lateinit var apRepository: ApRepository
    private var calibrating: Boolean = false
    private var referenceRssi: Int = -50 // Reference RSSI at 1 meter distance (calibrated)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ensurePermissions()

        floorPlanView = findViewById(R.id.floorPlanOverlay)
        wifiScanner = WifiScanner(this)
        apRepository = ApRepository(this)

        floorPlanView?.onPinsChanged = { pins ->
            apRepository.savePins(pins.map { SavedPin(it.bssid, it.positionPx.x, it.positionPx.y) })
        }

        // Load saved pins after layout is ready to get proper dimensions
        floorPlanView?.viewTreeObserver?.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                floorPlanView?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                val saved = apRepository.loadPins()
                if (saved.isNotEmpty()) {
                    floorPlanView?.setPins(saved.map { AccessPointPin(it.bssid, PointF(it.x, it.y)) })
                }
            }
        })

        findViewById<Button>(R.id.btnRange).setOnClickListener {
            startWifiScan()
        }
        findViewById<Button>(R.id.btnCalibrate).setOnClickListener {
            toggleCalibration()
        }

        // Update UI state based on current permissions
        refreshButtonState()
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
                        val cx = overlay.width / 2f
                        val cy = overlay.height / 2f
                        var angle = 0.0
                        val radius = (overlay.width.coerceAtMost(overlay.height)) * 0.3f
                        for (signal in results) {
                            val x = cx + (radius * kotlin.math.cos(angle)).toFloat()
                            val y = cy + (radius * kotlin.math.sin(angle)).toFloat()
                            currentPins[signal.bssid] = AccessPointPin(signal.bssid, PointF(x, y))
                            angle += (2 * Math.PI) / results.size
                        }
                        overlay.setPins(currentPins.values.toList())
                        // Save auto-placed pins
                        apRepository.savePins(currentPins.values.map { SavedPin(it.bssid, it.positionPx.x, it.positionPx.y) })
                    }

                    // Convert RSSI to distances and create anchors
                    val anchors = results.mapNotNull { signal ->
                        val pin = currentPins[signal.bssid] ?: return@mapNotNull null
                        val distancePx = PositioningMath.rssiToDistancePx(
                            rssi = signal.rssi,
                            referenceRssi = referenceRssi,
                            referenceDistancePx = 100f * distancePxPerMm * 1000f, // Convert to pixels
                            pathLossExponent = 2.5f
                        )
                        Anchor(
                            bssid = signal.bssid,
                            x = pin.positionPx.x,
                            y = pin.positionPx.y,
                            distancePx = distancePx
                        )
                    }
                    
                    if (anchors.size >= 3) {
                        val estimate = PositioningMath.trilaterate(anchors)
                        overlay.devicePositionPx = estimate
                        if (estimate != null) {
                            applyLearning(overlay, estimate, anchors)
                        }
                    } else {
                        overlay.devicePositionPx = null
                        Toast.makeText(this@MainActivity, "Need at least 3 known APs for positioning", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun applyLearning(overlay: FloorPlanView, device: PointF, anchors: List<Anchor>) {
        // Small nudge to AP pins to reduce distance residuals
        val lr = 0.05f
        val pins = overlay.getPins().associateBy { it.bssid }.toMutableMap()
        var changed = false
        anchors.forEach { a ->
            val pin = pins[a.bssid] ?: return@forEach
            val dx = device.x - pin.positionPx.x
            val dy = device.y - pin.positionPx.y
            val modelDist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (modelDist > 1f) {
                val error = a.distancePx - modelDist
                val nx = dx / modelDist
                val ny = dy / modelDist
                pin.positionPx.x -= lr * error * nx
                pin.positionPx.y -= lr * error * ny
                changed = true
            }
        }
        if (changed) {
            overlay.setPins(pins.values.toList())
            apRepository.savePins(pins.values.map { SavedPin(it.bssid, it.positionPx.x, it.positionPx.y) })
        }
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
