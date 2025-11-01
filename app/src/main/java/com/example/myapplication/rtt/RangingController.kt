package com.example.myapplication.rtt

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

data class ApDistance(
    val bssid: String,
    val distanceMm: Int,
    val stdDevMm: Int,
    val rssi: Int
)

@RequiresApi(Build.VERSION_CODES.P)
class RangingController(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val rttManager = context.applicationContext.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as WifiRttManager

    interface Listener {
        fun onDistances(results: List<ApDistance>)
        fun onError(error: String)
    }

    @SuppressLint("MissingPermission")
    fun rangeOnce(listener: Listener) {
        val all = wifiManager.scanResults
        val responders = all.filter { it.is80211mcResponder }
        if (responders.isEmpty()) {
            // Trigger a fresh scan and retry once when results arrive
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            val handler = Handler(Looper.getMainLooper())
            var receiver: BroadcastReceiver? = null
            receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    try { context.unregisterReceiver(this) } catch (_: Throwable) {}
                    handler.removeCallbacksAndMessages(null)
                    val all2 = wifiManager.scanResults
                    val responders2 = all2.filter { it.is80211mcResponder }.take(10)
                    if (responders2.isEmpty()) {
                        listener.onError("No RTT responders found (APs: ${all2.size})")
                    } else {
                        startRanging(responders2, listener)
                    }
                }
            }
            context.registerReceiver(receiver, filter)
            wifiManager.startScan()
            // Fallback timeout (5s)
            handler.postDelayed({
                try { context.unregisterReceiver(receiver!!) } catch (_: Throwable) {}
                val all2 = wifiManager.scanResults
                val responders2 = all2.filter { it.is80211mcResponder }.take(10)
                if (responders2.isEmpty()) {
                    listener.onError("No RTT responders found (APs: ${all2.size})")
                } else {
                    startRanging(responders2, listener)
                }
            }, 5000)
            return
        }
        startRanging(responders.take(10), listener)
    }

    @SuppressLint("MissingPermission")
    private fun startRanging(targets: List<android.net.wifi.ScanResult>, listener: Listener) {
        val request = RangingRequest.Builder()
            .addAccessPoints(targets)
            .build()
        rttManager.startRanging(request, context.mainExecutor, object : RangingResultCallback() {
            override fun onRangingFailure(code: Int) {
                listener.onError("Ranging failure: $code")
            }

            override fun onRangingResults(results: List<RangingResult>) {
                val distances = results
                    .filter { it.status == RangingResult.STATUS_SUCCESS }
                    .map {
                        ApDistance(
                            bssid = it.macAddress.toString(),
                            distanceMm = it.distanceMm,
                            stdDevMm = it.distanceStdDevMm,
                            rssi = it.rssi
                        )
                    }
                if (distances.isEmpty()) {
                    listener.onError("No successful ranging results")
                } else {
                    listener.onDistances(distances)
                }
            }
        })
    }
}
