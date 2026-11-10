package io.nekohasekai.sfa.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.telemetry.TelemetrySignals
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Device-local observations only. Nothing here is sent through CommandClient,
 * so network_snapshot labels these fields as local_sense.
 */
class AndroidDeviceSnapshotSource(
    private val context: Context,
    private val serviceMode: () -> String? = { null },
    private val serviceStarted: () -> Boolean? = { null },
) : DeviceSnapshotSource {
    override fun snapshot(): DeviceSnapshot =
        try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val capabilities =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    connectivityManager?.activeNetwork?.let {
                        connectivityManager.getNetworkCapabilities(it)
                    }
                } else {
                    null
                }
            val power = TelemetrySignals.powerState(context)
            val mode = runCatching { serviceMode() }.getOrNull()
            val started = runCatching { serviceStarted() }.getOrNull()
            DeviceSnapshot(
                networkType = describeNetwork(capabilities),
                localTime = SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.getDefault()).format(Date()),
                metered = connectivityManager?.isActiveNetworkMetered,
                vpnActive =
                    when {
                        started == null -> null
                        !started -> false
                        else -> mode == ServiceMode.VPN
                    },
                serviceMode = mode,
                batteryOptimizationIgnored = power["battery_opt_exempt"] as? Boolean,
                powerSave = power["power_save"] as? Boolean,
                doze = power["doze"] as? Boolean,
            )
        } catch (t: Throwable) {
            DeviceSnapshot(error = t.message ?: t.toString())
        }

    private fun describeNetwork(capabilities: NetworkCapabilities?): String =
        when {
            capabilities == null -> "unknown"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
}
