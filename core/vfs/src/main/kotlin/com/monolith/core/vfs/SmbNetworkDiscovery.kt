package com.monolith.core.vfs

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredSmbHost(
    val host: String,
    val ip: String,
    val port: Int = 445,
    val displayName: String,
    val source: String,
)

object SmbNetworkDiscovery {
    private const val SERVICE_TYPE = "_smb._tcp."
    private const val SCAN_TIMEOUT_MS = 250
    private const val MAX_CONCURRENT_PROBES = 48

    fun discover(context: Context): Flow<List<DiscoveredSmbHost>> = callbackFlow {
        val appContext = context.applicationContext
        val resultsByIp = ConcurrentHashMap<String, DiscoveredSmbHost>()

        fun emitSorted() {
            val sorted = resultsByIp.values.sortedWith(
                compareBy<DiscoveredSmbHost> { it.displayName.lowercase() }.thenBy { it.ip },
            )
            trySend(sorted)
        }

        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = runCatching {
            wifiManager?.createMulticastLock("monolith_smb_mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        var discoveryListener: NsdManager.DiscoveryListener? = null

        if (nsdManager != null) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    runCatching {
                        @Suppress("DEPRECATION")
                        nsdManager.resolveService(
                            serviceInfo,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                                override fun onServiceResolved(info: NsdServiceInfo) {
                                    @Suppress("DEPRECATION")
                                    val hostAddr = info.host?.hostAddress ?: return
                                    val port = info.port.takeIf { it > 0 } ?: 445
                                    val serviceName = info.serviceName.trim().ifEmpty { hostAddr }
                                    resultsByIp[hostAddr] = DiscoveredSmbHost(
                                        host = hostAddr,
                                        ip = hostAddr,
                                        port = port,
                                        displayName = serviceName,
                                        source = "mDNS",
                                    )
                                    emitSorted()
                                }
                            },
                        )
                    }
                }
            }
            discoveryListener = listener
            runCatching {
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        }

        val scanJob = launch(Dispatchers.IO) {
            val localIps = getPrivateIpv4Addresses()
            val semaphore = Semaphore(MAX_CONCURRENT_PROBES)
            coroutineScope {
                for (localIp in localIps) {
                    val prefix = localIp.substringBeforeLast('.')
                    val selfOctet = localIp.substringAfterLast('.').toIntOrNull()
                    for (hostOctet in 1..254) {
                        if (hostOctet == selfOctet) continue
                        val candidateIp = "$prefix.$hostOctet"
                        launch {
                            semaphore.withPermit {
                                val reachable = runCatching {
                                    Socket().use { socket ->
                                        socket.connect(InetSocketAddress(candidateIp, 445), SCAN_TIMEOUT_MS)
                                    }
                                    true
                                }.getOrDefault(false)
                                if (reachable) {
                                    val resolvedName = runCatching {
                                        val raw = InetAddress.getByName(candidateIp).hostName?.trim().orEmpty()
                                        if (raw.isBlank() || raw == candidateIp || raw.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) {
                                            null
                                        } else {
                                            raw.substringBefore('.')
                                        }
                                    }.getOrNull()
                                    resultsByIp.compute(candidateIp) { _, existing ->
                                        if (existing != null && existing.source == "mDNS") {
                                            existing
                                        } else {
                                            DiscoveredSmbHost(
                                                host = candidateIp,
                                                ip = candidateIp,
                                                port = 445,
                                                displayName = resolvedName ?: candidateIp,
                                                source = "LAN",
                                            )
                                        }
                                    }
                                    emitSorted()
                                }
                            }
                        }
                    }
                }
            }
        }

        awaitClose {
            scanJob.cancel()
            discoveryListener?.let { listener ->
                runCatching { nsdManager?.stopServiceDiscovery(listener) }
            }
            runCatching {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            }
        }
    }

    private fun getPrivateIpv4Addresses(): List<String> {
        val ips = LinkedHashSet<String>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        }
        return ips.toList()
    }
}
