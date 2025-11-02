package com.example.myapplication.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

data class ApSignal(
    val bssid: String,
    val ssid: String,
    val rssi: Int, // dBm
    val frequency: Int // MHz
)

class WifiScanner(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    interface Listener {
        fun onSignals(results: List<ApSignal>)
        fun onError(error: String)
    }

    @SuppressLint("MissingPermission")
    fun scanOnce(listener: Listener) {
        // Check if scanning is supported
        if (!wifiManager.isScanAlwaysAvailable && !wifiManager.isWifiEnabled) {
            listener.onError("Wi-Fi is disabled and background scanning unavailable")
            return
        }

        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        val handler = Handler(Looper.getMainLooper())
        var receiver: BroadcastReceiver? = null
        var scanTriggered = false

        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                try {
                    context.unregisterReceiver(this)
                } catch (_: Throwable) {}
                handler.removeCallbacksAndMessages(null)

                val success = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
                val scanResults = wifiManager.scanResults

                if (scanResults.isEmpty()) {
                    listener.onError("No Wi-Fi networks found")
                    return
                }

                val signals = scanResults
                    .filter { it.BSSID != null && !it.BSSID.equals("02:00:00:00:00:00", ignoreCase = true) }
                    .mapNotNull {
                        val bssid = it.BSSID ?: return@mapNotNull null
                        ApSignal(
                            bssid = bssid,
                            ssid = it.SSID ?: "Unknown",
                            rssi = it.level,
                            frequency = it.frequency
                        )
                    }
                    .sortedByDescending { it.rssi }
                    .take(20) // Use top 20 strongest signals

                if (signals.isEmpty()) {
                    listener.onError("No valid Wi-Fi access points found")
                } else {
                    listener.onSignals(signals)
                }
            }
        }

        context.registerReceiver(receiver, filter)
        
        // Start scan
        val scanStarted = wifiManager.startScan()
        if (!scanStarted) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Throwable) {}
            listener.onError("Failed to start Wi-Fi scan")
            return
        }

        // Fallback timeout (10 seconds)
        handler.postDelayed({
            try {
                context.unregisterReceiver(receiver!!)
            } catch (_: Throwable) {}
            val scanResults = wifiManager.scanResults
            if (scanResults.isEmpty()) {
                listener.onError("Wi-Fi scan timeout")
            } else {
                val signals = scanResults
                    .filter { it.BSSID != null && !it.BSSID.equals("02:00:00:00:00:00", ignoreCase = true) }
                    .mapNotNull {
                        val bssid = it.BSSID ?: return@mapNotNull null
                        ApSignal(
                            bssid = bssid,
                            ssid = it.SSID ?: "Unknown",
                            rssi = it.level,
                            frequency = it.frequency
                        )
                    }
                    .sortedByDescending { it.rssi }
                    .take(20)
                listener.onSignals(signals)
            }
        }, 10000)
    }
}

