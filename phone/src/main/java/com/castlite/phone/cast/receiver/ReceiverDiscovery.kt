package com.castlite.phone.cast.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.castlite.phone.cast.CastDevice
import com.castlite.phone.cast.CastProtocol
import com.castlite.protocol.ReceiverClient
import com.castlite.protocol.ReceiverProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Finds CastLite TV apps advertised over mDNS / DNS-SD (Android NSD). */
class ReceiverDiscovery(context: Context) {

    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)

    @Suppress("DEPRECATION") // resolveService is deprecated on API 34 but is the only option back to API 24.
    fun devices(): Flow<List<CastDevice>> = callbackFlow {
        val lock = app.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("CastLite:nsd")?.apply { setReferenceCounted(false); acquire() }
        val found = ConcurrentHashMap<String, CastDevice>() // service name -> device
        // Older Android versions can only resolve one service at a time.
        val pending = ConcurrentLinkedQueue<NsdServiceInfo>()
        val resolving = AtomicBoolean(false)

        fun publish() = trySend(found.values.sortedBy { it.name.lowercase() })

        fun resolveNext() {
            if (!resolving.compareAndSet(false, true)) return
            val info = pending.poll() ?: run { resolving.set(false); return }
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.d(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    resolving.set(false)
                    resolveNext()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress
                    if (host != null) {
                        found[serviceInfo.serviceName] = CastDevice(
                            id = "castlite:$host",
                            name = serviceInfo.serviceName.removePrefix("CastLite · ").removePrefix("CastLite - "),
                            protocol = CastProtocol.CASTLITE,
                            host = host,
                            manufacturer = "CastLite TV app",
                            receiverPort = serviceInfo.port,
                        )
                        publish()
                    }
                    resolving.set(false)
                    resolveNext()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                pending.add(serviceInfo)
                resolveNext()
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (found.remove(serviceInfo.serviceName) != null) publish()
            }
        }

        trySend(emptyList())
        nsd.discoverServices(ReceiverProtocol.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            lock?.takeIf { it.isHeld }?.release()
        }
    }

    companion object {
        private const val TAG = "ReceiverDiscovery"

        /**
         * Checks that a manually entered IP runs the CastLite TV app.
         * @throws Exception with a readable message if it doesn't answer.
         */
        suspend fun probe(host: String, port: Int = ReceiverProtocol.PORT): CastDevice = withContext(Dispatchers.IO) {
            val info = ReceiverClient(host, port, connectTimeoutMs = 2_500, readTimeoutMs = 3_000).info()
            CastDevice(
                id = "castlite:$host",
                name = info.name,
                protocol = CastProtocol.CASTLITE,
                host = host,
                manufacturer = "CastLite TV app",
                model = info.model,
                receiverPort = port,
                isManual = true,
            )
        }
    }
}
