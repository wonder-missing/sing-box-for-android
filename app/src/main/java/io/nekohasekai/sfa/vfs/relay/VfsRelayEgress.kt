package io.nekohasekai.sfa.vfs.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import io.nekohasekai.sfa.bg.VPNService
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

fun interface RelayNetworkResolver {
    fun resolve(host: String): List<InetAddress>
}

/**
 * OkHttp socket factory for Relay traffic. Sockets are created, protected from
 * the VPN and bound to the underlying non-VPN network before OkHttp connects.
 * Failures are logged and never stop the request: Android already applies its
 * normal routing when protect/bind is unavailable.
 */
class RelayProtectedSocketFactory(
    private val protect: (Socket) -> Unit = {},
    private val bind: (Socket) -> Unit = {},
    private val newSocket: () -> Socket = { Socket() },
) : SocketFactory() {
    override fun createSocket(): Socket =
        newSocket().also { socket ->
            protect(socket)
            bind(socket)
        }

    override fun createSocket(
        host: String,
        port: Int,
    ): Socket = createSocket().also { it.connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket =
        createSocket().also { socket ->
            socket.bind(InetSocketAddress(localHost, localPort))
            socket.connect(InetSocketAddress(host, port))
        }

    override fun createSocket(
        host: InetAddress,
        port: Int,
    ): Socket = createSocket().also { it.connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        host: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket =
        createSocket().also { socket ->
            socket.bind(InetSocketAddress(localAddress, localPort))
            socket.connect(InetSocketAddress(host, port))
        }
}

/**
 * Egress policy for the Share Key VFS Relay: DNS resolves through the
 * underlying non-VPN network, and sockets are protected/bound before connect.
 * Tests inject the resolver, socket factory and protector; production uses
 * [create].
 */
class VfsRelayEgress(
    private val resolver: () -> RelayNetworkResolver?,
    private val protector: (Socket) -> Boolean = { false },
    private val binder: (Socket) -> Unit = {},
    newSocket: () -> Socket = { Socket() },
    private val logger: (String) -> Unit = {},
) {
    val dns: Dns =
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses =
                    runCatching { resolver()?.resolve(hostname) }
                        .onFailure { logger("DNS via underlying network failed: ${it.message}") }
                        .getOrNull()
                return if (!addresses.isNullOrEmpty()) addresses else Dns.SYSTEM.lookup(hostname)
            }
        }

    val socketFactory: SocketFactory =
        RelayProtectedSocketFactory(
            protect = { socket ->
                runCatching { protector(socket) }
                    .onFailure { logger("VpnService.protect failed: ${it.message}") }
            },
            bind = { socket ->
                runCatching { binder(socket) }
                    .onFailure { logger("network.bindSocket failed: ${it.message}") }
            },
            newSocket = newSocket,
        )

    fun httpClient(): OkHttpClient =
        baseClient()
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

    fun relayClient(): OkHttpClient =
        baseClient()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

    private fun baseClient(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .dns(dns)
            .socketFactory(socketFactory)
            .connectTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

    companion object {
        private const val TAG = "VfsRelayEgress"

        fun create(context: Context): VfsRelayEgress {
            val app = context.applicationContext
            val manager =
                runCatching {
                    app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                }.getOrNull()
            return VfsRelayEgress(
                resolver = {
                    manager?.let { current ->
                        underlyingNetwork(current)?.let { network ->
                            RelayNetworkResolver { host -> network.getAllByName(host).toList() }
                        }
                    }
                },
                protector = { socket ->
                    // No active VPN means the app is not routed; nothing to protect.
                    VPNService.active?.protect(socket) ?: false
                },
                binder = { socket ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        manager?.let { current ->
                            underlyingNetwork(current)?.let { network ->
                                runCatching { network.bindSocket(socket) }
                                    .onFailure { Log.w(TAG, "bindSocket failed: ${it.message}") }
                            }
                        }
                    }
                },
                logger = { message -> Log.w(TAG, message) },
            )
        }

        private fun underlyingNetwork(manager: ConnectivityManager): Network? {
            val networks = runCatching { manager.allNetworks }.getOrNull() ?: return null
            fun usable(network: Network): Boolean {
                val caps = manager.getNetworkCapabilities(network) ?: return false
                return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            return networks.firstOrNull(::usable)
        }
    }
}
