package com.castlite.tv.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.castlite.protocol.ReceiverProtocol

/** Announces this TV on the LAN over mDNS / DNS-SD so the phone finds it automatically. */
class NsdAdvertiser(context: Context) {

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun register(name: String, port: Int) {
        if (listener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "CastLite · $name"
            serviceType = ReceiverProtocol.NSD_SERVICE_TYPE
            setPort(port)
            setAttribute("api", ReceiverProtocol.API_VERSION.toString())
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Advertising as ${serviceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        listener = l
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "NSD unavailable", e)
            listener = null
        }
    }

    fun unregister() {
        val l = listener ?: return
        listener = null
        runCatching { nsd.unregisterService(l) }
    }

    private companion object {
        const val TAG = "NsdAdvertiser"
    }
}
