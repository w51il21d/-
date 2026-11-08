package com.w51il21d.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/** Phone status for the idle pill: mobile signal 0..4, Wi-Fi 0..3 (-1 = off), battery. Main thread only. */
object StatusState {
    var cell = -1; private set
    var wifi = -1; private set
    var battery = 0; private set
    var charging = false; private set
    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun set(cell: Int = this.cell, wifi: Int = this.wifi, battery: Int = this.battery, charging: Boolean = this.charging) {
        if (cell == this.cell && wifi == this.wifi && battery == this.battery && charging == this.charging) return
        this.cell = cell; this.wifi = wifi; this.battery = battery; this.charging = charging
        for (l in listeners.toList()) l()
    }
}

/** Feeds [StatusState] from the system. No permissions beyond network-state are needed. */
class StatusMonitor(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val wm = ctx.getSystemService(WifiManager::class.java)
    private val tm = ctx.getSystemService(TelephonyManager::class.java)

    private val batteryRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale
            StatusState.set(battery = lvl, charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }
    }

    private val net = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            val level = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val rssi = (caps.transportInfo as? WifiInfo)?.rssi ?: caps.signalStrength
                try {
                    val l = wm.calculateSignalLevel(rssi); val max = wm.maxSignalLevel.coerceAtLeast(1)
                    (l * 3 + max / 2) / max
                } catch (_: Exception) { 3 }
            } else -1
            main.post { StatusState.set(wifi = level) }
        }
        override fun onLost(n: Network) { main.post { StatusState.set(wifi = -1) } }
    }

    private val signal = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
        override fun onSignalStrengthsChanged(s: SignalStrength) { main.post { StatusState.set(cell = s.level) } }
    }

    fun start() {
        ctx.registerReceiver(batteryRx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        try { cm.registerDefaultNetworkCallback(net) } catch (_: Exception) { }
        try {
            if (tm.simState == TelephonyManager.SIM_STATE_READY) tm.registerTelephonyCallback(ctx.mainExecutor, signal)
            else StatusState.set(cell = -1)
        } catch (_: Exception) { }
    }

    fun stop() {
        try { ctx.unregisterReceiver(batteryRx) } catch (_: Exception) { }
        try { cm.unregisterNetworkCallback(net) } catch (_: Exception) { }
        try { tm.unregisterTelephonyCallback(signal) } catch (_: Exception) { }
    }
}
