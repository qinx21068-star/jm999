package com.jmreader.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/** Shared, lightweight device state for download constraints and reader prefetch. */
data class DeviceState(
    val online: Boolean = false,
    val wifi: Boolean = false,
    val charging: Boolean = false,
    val lowBattery: Boolean = false,
)

class DeviceConditions(context: Context, scope: CoroutineScope) {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun snapshot(): DeviceState {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return DeviceState(
            online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            charging = charging,
            lowBattery = !charging && level >= 0 && scale > 0 && level * 100 / scale <= 15,
        )
    }

    val state = callbackFlow<DeviceState> {
        fun emitState() { trySend(snapshot()) }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = emitState()
            override fun onLost(network: Network) = emitState()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = emitState()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = emitState()
        }
        connectivity.registerDefaultNetworkCallback(callback)
        // BATTERY_CHANGED is a system-only protected broadcast.
        app.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        emitState()
        awaitClose {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            runCatching { app.unregisterReceiver(receiver) }
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, snapshot())
}
