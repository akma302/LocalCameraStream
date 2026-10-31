package dev.localstream.network

import android.content.Context
import android.net.*
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

data class DiscoveredServer(val id: String, val name: String, val host: String, val port: Int)

class LocalDiscovery(context: Context) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val resolving = Mutex()
    fun discover(): Flow<List<DiscoveredServer>> = callbackFlow {
        val found = mutableMapOf<String, DiscoveredServer>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { trySend(emptyList()) }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { close(IllegalStateException("NSD: $error")) }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) { synchronized(found) { found.remove(info.serviceName); trySend(found.values.toList()) } }
            override fun onServiceFound(info: NsdServiceInfo) {
                launch {
                    val resolved = resolving.withLock { withTimeoutOrNull(4_000) { resolve(info) } } ?: return@launch
                    val host = resolved.host?.hostAddress ?: return@launch
                    val id = resolved.attributes["id"]?.toString(Charsets.UTF_8) ?: return@launch
                    synchronized(found) { found[resolved.serviceName] = DiscoveredServer(id, resolved.serviceName, host, resolved.port); trySend(found.values.toList()) }
                }
            }
        }
        manager.discoverServices("_localcam._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { manager.stopServiceDiscovery(listener) } }
    }
    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { if (cont.isActive) cont.resume(null) }
            override fun onServiceResolved(info: NsdServiceInfo) { if (cont.isActive) cont.resume(info) }
        })
    }
    fun advertise(id: String, name: String, port: Int): AutoCloseable {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        manager.registerService(NsdServiceInfo().apply {
            serviceName = name; serviceType = "_localcam._tcp."; setPort(port); setAttribute("id", id); setAttribute("v", "1")
        }, NsdManager.PROTOCOL_DNS_SD, listener)
        return AutoCloseable { runCatching { manager.unregisterService(listener) } }
    }
}

/** Local Wi-Fi does not need INTERNET or VALIDATED capabilities. */
class LocalNetworkMonitor(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    data class Snapshot(val available: Boolean, val revision: Long)
    fun changes(): Flow<Snapshot> = callbackFlow {
        var revision = 0L
        fun update() {
            val available = manager.allNetworks.any { network -> manager.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true }
            trySend(Snapshot(available, ++revision))
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update()
            override fun onLost(network: Network) = update()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = update()
        }
        val request = NetworkRequest.Builder().clearCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
        manager.registerNetworkCallback(request, callback)
        update()
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.conflate()
}
