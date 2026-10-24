package io.github.jqssun.airplay.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

/**
 * A compact DLNA Digital Media Renderer (DMR).
 *
 * Lets Android apps ("Play to / Cast to device") and Windows ("Cast to Device") send
 * media file URLs to this device for playback. This is NOT live screen mirroring — that
 * is AirPlay's job (and the OS handles Cast/Miracast). It implements just enough UPnP to
 * be discovered and controlled: SSDP discovery, the device/service descriptors, and the
 * AVTransport / RenderingControl / ConnectionManager SOAP actions.
 *
 * Hand-rolled with raw sockets on purpose: UPnP uses non-HTTP verbs (SUBSCRIBE / NOTIFY)
 * that lightweight HTTP servers reject, and avoiding jUPnP keeps the app GPL-clean + small.
 */
class DlnaRenderer(
    private val context: Context,
    private val friendlyName: String,
    private val uuid: String,
) {
    private val tag = "DlnaRenderer"
    private val ssdpAddr = InetAddress.getByName("239.255.255.250")
    private val ssdpPort = 1900

    @Volatile private var running = false
    private var httpServer: ServerSocket? = null
    private var ssdpSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var httpPort = 0
    private var localIp = ""

    private val httpDate: String
        get() = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
            .format(java.util.Date(System.currentTimeMillis()))

    private val server = "Android/${android.os.Build.VERSION.RELEASE} UPnP/1.0 AirPlayDLNA/1.0"

    fun start() {
        if (running) return
        localIp = findLocalIp() ?: run {
            Log.w(tag, "No LAN IP; DLNA not started")
            return
        }
        running = true
        acquireMulticastLock()
        startHttpServer()
        startSsdp()
        Log.i(tag, "DLNA renderer up: http://$localIp:$httpPort/description.xml uuid=$uuid")
    }

    fun stop() {
        if (!running) return
        running = false
        try { sendByeBye() } catch (_: Exception) {}
        try { ssdpSocket?.close() } catch (_: Exception) {}
        try { httpServer?.close() } catch (_: Exception) {}
        ssdpSocket = null
        httpServer = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        DlnaController.reset()
        Log.i(tag, "DLNA renderer stopped")
    }

    // ---------------- networking helpers ----------------

    private fun acquireMulticastLock() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("airplay-dlna").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun findLocalIp(): String? {
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                if (!(iface.name.startsWith("wlan") || iface.name.startsWith("eth"))) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr.address.size == 4) return addr.hostAddress
                }
            }
            // fallback: any non-loopback IPv4
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr.address.size == 4) return addr.hostAddress
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "findLocalIp failed", e)
        }
        return null
    }

    private fun multicastInterface(): NetworkInterface? {
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback || !iface.supportsMulticast()) continue
                for (addr in iface.inetAddresses) {
                    if (addr.hostAddress == localIp) return iface
                }
            }
        } catch (_: Exception) {}
        return null
    }

    // ---------------- SSDP ----------------

    private fun startSsdp() {
        thread(name = "dlna-ssdp", isDaemon = true) {
            try {
                // bind UNBOUND first so reuseAddress applies before binding to 1900, otherwise
                // we may fail to receive the multicast M-SEARCH that Windows uses to enumerate.
                val sock = MulticastSocket(null as java.net.SocketAddress?).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(ssdpPort))
                }
                val nif = multicastInterface()
                try {
                    if (nif != null) sock.joinGroup(InetSocketAddress(ssdpAddr, ssdpPort), nif)
                    else @Suppress("DEPRECATION") sock.joinGroup(ssdpAddr)
                } catch (e: Exception) {
                    @Suppress("DEPRECATION") try { sock.joinGroup(ssdpAddr) } catch (_: Exception) {}
                }
                ssdpSocket = sock
                // announce presence
                repeat(2) { sendAlive() }
                val buf = ByteArray(2048)
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (e: Exception) {
                        if (running) Log.w(tag, "ssdp recv error: ${e.message}")
                        break
                    }
                    val msg = String(pkt.data, 0, pkt.length)
                    if (msg.startsWith("M-SEARCH", ignoreCase = true)) {
                        handleSearch(msg, pkt.address, pkt.port)
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(tag, "SSDP failed", e)
            }
        }
        // periodic alive
        thread(name = "dlna-ssdp-alive", isDaemon = true) {
            while (running) {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
                if (running) try { sendAlive() } catch (_: Exception) {}
            }
        }
    }

    private fun searchTargets(): List<Pair<String, String>> = listOf(
        "upnp:rootdevice" to "uuid:$uuid::upnp:rootdevice",
        "uuid:$uuid" to "uuid:$uuid",
        "urn:schemas-upnp-org:device:MediaRenderer:1" to "uuid:$uuid::urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1" to "uuid:$uuid::urn:schemas-upnp-org:service:AVTransport:1",
        "urn:schemas-upnp-org:service:RenderingControl:1" to "uuid:$uuid::urn:schemas-upnp-org:service:RenderingControl:1",
        "urn:schemas-upnp-org:service:ConnectionManager:1" to "uuid:$uuid::urn:schemas-upnp-org:service:ConnectionManager:1",
    )

    private fun handleSearch(msg: String, addr: InetAddress, port: Int) {
        val st = Regex("(?im)^ST:\\s*(.+)$").find(msg)?.groupValues?.get(1)?.trim() ?: return
        val targets = when {
            st.equals("ssdp:all", true) -> searchTargets()
            else -> searchTargets().filter { it.first.equals(st, true) }
        }
        if (targets.isEmpty()) return
        val sock = ssdpSocket ?: return
        for ((nt, usn) in targets) {
            val resp = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("CACHE-CONTROL: max-age=1800\r\n")
                append("DATE: $httpDate\r\n")
                append("EXT:\r\n")
                append("LOCATION: http://$localIp:$httpPort/description.xml\r\n")
                append("SERVER: $server\r\n")
                append("ST: $nt\r\n")
                append("USN: $usn\r\n")
                append("\r\n")
            }.toByteArray()
            try {
                sock.send(DatagramPacket(resp, resp.size, addr, port))
                sock.send(DatagramPacket(resp, resp.size, addr, port)) // UDP is lossy; send twice
            } catch (e: Exception) {
                Log.w(tag, "ssdp reply failed: ${e.message}")
            }
        }
    }

    private fun sendNotify(nts: String) {
        val sock = ssdpSocket ?: return
        for ((nt, usn) in searchTargets()) {
            val msg = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: 239.255.255.250:1900\r\n")
                append("CACHE-CONTROL: max-age=1800\r\n")
                if (nts == "ssdp:alive") append("LOCATION: http://$localIp:$httpPort/description.xml\r\n")
                append("NT: $nt\r\n")
                append("NTS: $nts\r\n")
                append("SERVER: $server\r\n")
                append("USN: $usn\r\n")
                append("\r\n")
            }.toByteArray()
            try {
                sock.send(DatagramPacket(msg, msg.size, ssdpAddr, ssdpPort))
            } catch (_: Exception) {}
        }
    }

    private fun sendAlive() = sendNotify("ssdp:alive")
    private fun sendByeBye() = sendNotify("ssdp:byebye")

    // ---------------- HTTP / SOAP ----------------

    private fun startHttpServer() {
        // Use a STABLE port: Windows "Cast to Device" caches the device LOCATION (which
        // includes the port). An ephemeral port that changes each launch leaves Windows
        // with a stale, dead URL so the device never shows. Fall back to ephemeral if taken.
        val srv = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(DLNA_HTTP_PORT)) }
        } catch (e: Exception) {
            ServerSocket(0)
        }
        httpServer = srv
        httpPort = srv.localPort
        thread(name = "dlna-http", isDaemon = true) {
            while (running) {
                val client = try { srv.accept() } catch (e: Exception) { if (running) Log.w(tag, "accept: ${e.message}"); break }
                thread(isDaemon = true) { handleHttp(client) }
            }
        }
    }

    private fun handleHttp(client: Socket) {
        try {
            client.soTimeout = 15000
            val input = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1]
            val headers = HashMap<String, String>()
            var line = input.readLine()
            while (!line.isNullOrEmpty()) {
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                line = input.readLine()
            }
            var body = ""
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len > 0) {
                val chars = CharArray(len)
                var read = 0
                while (read < len) {
                    val r = input.read(chars, read, len - read)
                    if (r < 0) break
                    read += r
                }
                body = String(chars, 0, read)
            }
            routeHttp(client.getOutputStream(), method, path, headers, body)
        } catch (e: Exception) {
            Log.w(tag, "http handler error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun routeHttp(out: OutputStream, method: String, path: String, headers: Map<String, String>, body: String) {
        when {
            method == "GET" && path == "/description.xml" ->
                writeXml(out, DlnaDescriptors.deviceDescription(uuid, friendlyName))
            method == "GET" && path == "/scpd/AVTransport.xml" ->
                writeXml(out, DlnaDescriptors.AVTRANSPORT_SCPD)
            method == "GET" && path == "/scpd/RenderingControl.xml" ->
                writeXml(out, DlnaDescriptors.RENDERING_SCPD)
            method == "GET" && path == "/scpd/ConnectionManager.xml" ->
                writeXml(out, DlnaDescriptors.CONNECTIONMANAGER_SCPD)
            method == "POST" && path.startsWith("/ctrl/") ->
                handleSoap(out, path.removePrefix("/ctrl/"), headers["soapaction"] ?: "", body)
            method == "SUBSCRIBE" ->
                handleSubscribe(out, path, headers)
            method == "UNSUBSCRIBE" ->
                writeStatus(out, "200 OK", "", "text/plain")
            else ->
                writeStatus(out, "404 Not Found", "", "text/plain")
        }
    }

    private fun handleSubscribe(out: OutputStream, path: String, headers: Map<String, String>) {
        val sid = "uuid:${UUID.randomUUID()}"
        val resp = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("DATE: $httpDate\r\n")
            append("SERVER: $server\r\n")
            append("SID: $sid\r\n")
            append("TIMEOUT: Second-1800\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(resp.toByteArray()); out.flush()

        // Windows "Cast to Device" subscribes and waits for the FIRST event NOTIFY before it
        // treats the renderer as usable. Without it, the device is discovered but hidden.
        val callback = headers["callback"]?.let { Regex("<([^>]+)>").find(it)?.groupValues?.get(1) }
        if (callback != null) {
            val service = when {
                path.contains("AVTransport") -> "AVT"
                path.contains("RenderingControl") -> "RCS"
                else -> "CM"
            }
            thread(isDaemon = true) { sendInitialEvent(callback, sid, service) }
        }
    }

    private fun xmlEsc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun sendInitialEvent(callbackUrl: String, sid: String, service: String) {
        try {
            val url = URL(callbackUrl)
            val host = url.host
            val port = if (url.port == -1) 80 else url.port
            val pathPart = (if (url.path.isNullOrEmpty()) "/" else url.path) +
                (if (url.query != null) "?" + url.query else "")
            val lastChange = when (service) {
                "AVT" -> "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">" +
                    "<TransportState val=\"STOPPED\"/></InstanceID></Event>"
                "RCS" -> "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">" +
                    "<Volume channel=\"Master\" val=\"${DlnaController.volume}\"/>" +
                    "<Mute channel=\"Master\" val=\"0\"/></InstanceID></Event>"
                else -> null
            }
            val body = if (lastChange != null)
                "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property>" +
                    "<LastChange>${xmlEsc(lastChange)}</LastChange></e:property></e:propertyset>"
            else
                "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property>" +
                    "<SinkProtocolInfo></SinkProtocolInfo></e:property></e:propertyset>"
            val bytes = body.toByteArray(Charsets.UTF_8)
            val req = buildString {
                append("NOTIFY $pathPart HTTP/1.1\r\n")
                append("HOST: $host:$port\r\n")
                append("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n")
                append("NT: upnp:event\r\n")
                append("NTS: upnp:propchange\r\n")
                append("SID: $sid\r\n")
                append("SEQ: 0\r\n")
                append("CONTENT-LENGTH: ${bytes.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 5000)
                val o = s.getOutputStream()
                o.write(req.toByteArray()); o.write(bytes); o.flush()
                try { s.getInputStream().read(ByteArray(256)) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(tag, "initial event notify failed: ${e.message}")
        }
    }

    private fun handleSoap(out: OutputStream, service: String, soapAction: String, body: String) {
        val action = soapAction.trim('"').substringAfterLast('#')
        Log.i(tag, "SOAP $service#$action")
        val resp = try {
            when (action) {
                "SetAVTransportURI" -> {
                    val uri = arg(body, "CurrentURI")
                    val meta = arg(body, "CurrentURIMetaData")
                    DlnaController.uri = uri
                    DlnaController.metadata = meta
                    DlnaController.transportState = DlnaController.STATE_STOPPED
                    DlnaSoap.setAvTransportUriResponse()
                }
                "Play" -> {
                    val uri = DlnaController.uri
                    if (uri != null) {
                        DlnaController.transportState = DlnaController.STATE_TRANSITIONING
                        val p = DlnaController.player
                        if (p != null) p.play() else DlnaController.launchPlayer?.invoke(uri, DlnaController.metadata)
                    }
                    DlnaSoap.playResponse()
                }
                "Pause" -> { DlnaController.player?.pause(); DlnaSoap.pauseResponse() }
                "Stop" -> { DlnaController.player?.stop(); DlnaController.transportState = DlnaController.STATE_STOPPED; DlnaSoap.stopResponse() }
                "Seek" -> {
                    val target = arg(body, "Target")
                    DlnaController.player?.seekTo(parseTime(target))
                    DlnaSoap.seekResponse()
                }
                "GetTransportInfo" -> DlnaSoap.getTransportInfo(DlnaController.transportState)
                "GetPositionInfo" -> DlnaSoap.getPositionInfo(
                    DlnaController.positionMs, DlnaController.durationMs,
                    DlnaController.uri ?: "", DlnaController.metadata
                )
                "GetMediaInfo" -> DlnaSoap.getMediaInfo(
                    DlnaController.durationMs, DlnaController.uri ?: "", DlnaController.metadata
                )
                "GetDeviceCapabilities" -> DlnaSoap.getDeviceCapabilities()
                "GetTransportSettings" -> DlnaSoap.getTransportSettings()
                "GetCurrentTransportActions" -> DlnaSoap.getCurrentTransportActions()
                "GetVolume" -> DlnaSoap.getVolume(DlnaController.volume)
                "SetVolume" -> {
                    val v = arg(body, "DesiredVolume").toIntOrNull() ?: DlnaController.volume
                    DlnaController.volume = v.coerceIn(0, 100)
                    DlnaController.player?.setVolume(DlnaController.volume)
                    DlnaSoap.setVolumeResponse()
                }
                "GetMute" -> DlnaSoap.getMute(DlnaController.muted)
                "SetMute" -> {
                    DlnaController.muted = arg(body, "DesiredMute").let { it == "1" || it.equals("true", true) }
                    DlnaSoap.setMuteResponse()
                }
                "GetProtocolInfo" -> DlnaSoap.getProtocolInfo()
                "GetCurrentConnectionIDs" -> DlnaSoap.getCurrentConnectionIDs()
                "GetCurrentConnectionInfo" -> DlnaSoap.getCurrentConnectionInfo()
                else -> { writeStatus(out, "501 Not Implemented", "", "text/plain"); return }
            }
        } catch (e: Exception) {
            Log.w(tag, "SOAP $action error", e)
            writeStatus(out, "500 Internal Server Error", DlnaSoap.fault(), "text/xml")
            return
        }
        writeStatus(out, "200 OK", resp, "text/xml; charset=\"utf-8\"")
    }

    private fun arg(body: String, name: String): String {
        val m = Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(body) ?: return ""
        return m.groupValues[1].trim()
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
    }

    private fun parseTime(t: String): Long {
        // HH:MM:SS(.ms)
        val parts = t.split(":")
        return try {
            when (parts.size) {
                3 -> ((parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble().toLong()) * 1000)
                else -> 0
            }
        } catch (_: Exception) { 0 }
    }

    private fun writeXml(out: OutputStream, xml: String) = writeStatus(out, "200 OK", xml, "text/xml; charset=\"utf-8\"")

    private fun writeStatus(out: OutputStream, status: String, body: String, contentType: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("DATE: $httpDate\r\n")
            append("SERVER: $server\r\n")
            append("EXT:\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    companion object {
        private const val DLNA_HTTP_PORT = 8200 // stable port so Windows' cached LOCATION stays valid

        fun stableUuid(seed: String): String =
            UUID.nameUUIDFromBytes("airplay-dlna:$seed".toByteArray()).toString()
    }
}
