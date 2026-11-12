package io.nekohasekai.sfa.telemetry

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager

/**
 * Cheap, privacy-safe device signals for telemetry events: default network
 * transport changes (no SSID / IP) and power restrictions (battery optimisation,
 * power save, doze). Everything here is best effort and never throws.
 */
internal object TelemetrySignals {
    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var lastNetwork: String? = null

    fun powerState(context: Context): Map<String, Any?> {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return emptyMap()
        return runCatching {
            mapOf(
                "battery_opt_exempt" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm.isIgnoringBatteryOptimizations(context.packageName) else null,
                "power_save" to pm.isPowerSaveMode,
                "doze" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm.isDeviceIdleMode else null,
            )
        }.getOrDefault(emptyMap())
    }

    private fun describe(caps: NetworkCapabilities?): String {
        if (caps == null) return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    /**
     * Stable-ish per-network id: transport plus the Network hash (netId). No
     * SSID, carrier name or address ever enters this string.
     */
    private fun networkId(kind: String, network: Network): String =
        "$kind:${network.hashCode().toString(16)}"

    /**
     * Physical uplink under the tunnel. No SSID, carrier name or address.
     * [networkId] is `transport` plus the Network hash and changes when the
     * network object changes; it is not stable across reconnects.
     */
    internal data class Uplink(
        val transport: String,
        val networkId: String,
        val metered: Boolean,
        val validated: Boolean,
    )

    fun currentUplink(context: Context): Uplink? {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return runCatching {
            val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.activeNetwork else null
            val chosen = active?.let { network ->
                cm.getNetworkCapabilities(network)?.let { caps -> if (isUplink(caps)) network to caps else null }
            } ?: runCatching { cm.allNetworks }.getOrNull()?.firstNotNullOfOrNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
                if (isUplink(caps)) network to caps else null
            } ?: return@runCatching null
            val kind = describe(chosen.second)
            Uplink(
                transport = kind,
                networkId = networkId(kind, chosen.first),
                metered = !chosen.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                validated = chosen.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }.getOrNull()
    }

    /**
     * Snapshot of the current default uplink, used for the service_start event
     * and for network_change. The VPN itself is skipped so "default" means the
     * physical network the tunnel rides on.
     */
    fun currentDefaultNetwork(context: Context): Map<String, Any?> {
        val uplink = currentUplink(context) ?: return emptyMap()
        return mapOf(
            "transport" to uplink.transport,
            "default_network_id" to uplink.networkId,
        )
    }

    private fun isUplink(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    fun startNetworkWatch(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || callback != null) return
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val kind = describe(caps)
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                val id = networkId(kind, network)
                val key = "$id/$validated/$metered"
                if (key == lastNetwork) return
                lastNetwork = key
                TelemetryEventLog.record(
                    app,
                    "network_change",
                    mapOf(
                        "transport" to kind,
                        "default_network_id" to id,
                        "validated" to validated,
                        "metered" to metered,
                    ),
                )
            }

            override fun onLost(network: Network) {
                lastNetwork = null
                TelemetryEventLog.record(app, "network_lost")
            }
        }
        runCatching {
            // Physical (non-VPN) internet networks only, so the uplink transport is visible.
            val request = android.net.NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(request, cb)
            callback = cb
        }
    }

    fun stopNetworkWatch(context: Context) {
        val cb = callback ?: return
        callback = null
        lastNetwork = null
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }
}
