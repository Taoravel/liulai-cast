package io.github.jqssun.airplay.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** Registration callbacks and lifecycle operations are serialized on the main looper. */
class NsdServiceManager(private val ctx: Context) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivity = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastRefreshAt = SystemClock.elapsedRealtime()
    private var released = false
    private var watching = false
    private var screenAwake = ScreenStateMonitor.isAwake(ctx)
    private val screenMonitor = ScreenStateMonitor(ctx, ::setScreenAwake)
    private val links = mutableMapOf<Network, String>()
    private class Entry(val name: String, val type: String, val port: Int, val txt: Map<String, String>) {
        var listener: NsdManager.RegistrationListener? = null
        var registered = false
        var retiring = false
        var unregistering = false
        var unregisterAt = 0L
        var attemptedAt = 0L
        var retryAt = 0L
        var failures = 0
    }
    private val entries = mutableListOf<Entry>()
    private val refresh = Runnable {
        if (canAdvertise()) {
            lastRefreshAt = SystemClock.elapsedRealtime()
            acquireLock()
            entries.forEach { it.retiring = true; unregister(it) }
        }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (released) return
            setScreenAwake(ScreenStateMonitor.isAwake(ctx))
            val now = SystemClock.elapsedRealtime()
            // Refresh platform records without restarting active media connections.
            if (canAdvertise() && now - lastRefreshAt >= 180_000 && entries.all { it.registered && !it.retiring }) {
                requestRefresh("periodic announcement recovery")
            }
            entries.forEach {
                when {
                    it.unregistering && now - it.unregisterAt > 15_000 -> {
                        it.unregistering = false
                        unregister(it)
                    }
                    canAdvertise() && it.listener == null && now >= it.retryAt -> register(it)
                    it.retiring && !it.unregistering -> unregister(it)
                    !it.registered && !it.retiring && now - it.attemptedAt > 30_000 -> {
                        Log.w(TAG, "${it.type} registration timeout; recovering")
                        it.retiring = true
                        unregister(it)
                    }
                }
            }
            handler.postDelayed(this, 5_000)
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            val signature = properties.interfaceName + ":" + properties.linkAddresses.sortedBy { it.toString() }
            handler.post {
                if (!released && links.put(network, signature) != signature) requestRefresh("network changed")
            }
        }
        override fun onLost(network: Network) {
            handler.post { if (!released && links.remove(network) != null) requestRefresh("network lost") }
        }
    }
    private fun canAdvertise() = !released && screenAwake
    private fun setScreenAwake(awake: Boolean) {
        if (released || screenAwake == awake) return
        screenAwake = awake
        if (awake) {
            acquireLock()
            requestRefresh("screen awake")
        } else {
            handler.removeCallbacks(refresh)
            entries.forEach { it.retiring = true; unregister(it) }
            releaseLocks()
            Log.i(TAG, "Screen asleep: receiver announcements withdrawn")
        }
    }
    fun acquireMulticastLock() {
        acquireLock()
        if (!watching) {
            // Local LAN registration does not require validated internet access.
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
            try {
                connectivity.registerNetworkCallback(request, networkCallback)
                watching = true
            } catch (e: RuntimeException) { Log.w(TAG, "Network monitoring unavailable", e) }
            try {
                screenMonitor.start()
            } catch (e: RuntimeException) { Log.w(TAG, "Wake monitoring unavailable", e) }
            handler.postDelayed(watchdog, 5_000)
        }
    }
    private fun acquireLock() {
        if (!canAdvertise()) return
        if (multicastLock?.isHeld == true) return
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("airplay_mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "airplay_stream").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: RuntimeException) { Log.w(TAG, "Multicast lock unavailable", e) }
    }
    fun requestRefresh(reason: String) {
        if (!canAdvertise()) return
        Log.i(TAG, "Refreshing discovery: $reason")
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 1_500)
    }
    fun registerRaop(serviceName: String, port: Int, txtRecords: Map<String, String>) =
        add(serviceName, "_raop._tcp", port, txtRecords)
    fun registerAirplay(serviceName: String, port: Int, txtRecords: Map<String, String>) =
        add(serviceName, "_airplay._tcp", port, txtRecords)
    fun registerMirror(serviceName: String, port: Int, type: String) =
        add(serviceName, type, port, emptyMap())
    private fun add(name: String, type: String, port: Int, txt: Map<String, String>) {
        if (Looper.myLooper() != handler.looper) {
            handler.post { add(name, type, port, txt) }
            return
        }
        if (released) return
        val entry = Entry(name, type, port, txt.toMap())
        entries.add(entry)
        register(entry)
    }
    private fun retry(entry: Entry) {
        entry.listener = null
        entry.registered = false
        entry.retiring = false
        entry.unregistering = false
        entry.failures = (entry.failures + 1).coerceAtMost(5)
        entry.retryAt = SystemClock.elapsedRealtime() + (1_000L shl entry.failures).coerceAtMost(30_000)
    }
    private fun register(entry: Entry) {
        if (!canAdvertise() || entry.listener != null) return
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { handler.post {
                if (entry.listener !== this) return@post
                entry.registered = true
                entry.failures = 0
                Log.i(TAG, "${entry.type} registered: ${info.serviceName}")
                if (!canAdvertise() || entry.retiring) unregister(entry)
            } }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { handler.post {
                if (entry.listener !== this) return@post
                Log.w(TAG, "${entry.type} registration failed: $code; retry scheduled")
                retry(entry)
            } }
            override fun onServiceUnregistered(info: NsdServiceInfo) { handler.post {
                if (entry.listener !== this) return@post
                entry.listener = null
                entry.registered = false
                entry.retiring = false
                entry.unregistering = false
                entry.retryAt = 0
                if (canAdvertise()) register(entry)
            } }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) { handler.post {
                if (entry.listener !== this) return@post
                // Retain the listener until removed; overlapping registrations rename services.
                entry.unregistering = false
                Log.w(TAG, "${entry.type} unregister failed: $code")
            } }
        }
        entry.listener = listener
        entry.attemptedAt = SystemClock.elapsedRealtime()
        try {
            val info = NsdServiceInfo().apply {
                serviceName = entry.name
                serviceType = entry.type
                port = entry.port
                entry.txt.forEach { (k, v) -> setAttribute(k, v) }
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            Log.w(TAG, "${entry.type} registration threw; retry scheduled", e)
            retry(entry)
        }
    }
    private fun unregister(entry: Entry) {
        val listener = entry.listener
        if (listener == null) {
            entry.retiring = false
            if (canAdvertise()) register(entry)
            return
        }
        if (entry.unregistering) return
        try {
            entry.unregistering = true
            entry.unregisterAt = SystemClock.elapsedRealtime()
            nsd.unregisterService(listener)
        } catch (e: IllegalArgumentException) {
            retry(entry) // Android has already discarded this listener.
        } catch (e: RuntimeException) {
            entry.unregistering = false
            Log.w(TAG, "${entry.type} unregister threw", e)
        }
    }
    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        if (watching) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        screenMonitor.close()
        entries.forEach { it.retiring = true; unregister(it) }
        releaseLocks()
        links.clear()
    }
    private fun releaseLocks() {
        runCatching { if (multicastLock?.isHeld == true) multicastLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
        multicastLock = null
    }
    companion object { private const val TAG = "NsdServiceManager" }
}
