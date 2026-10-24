package io.github.jqssun.airplay.dlna

/** Builds SOAP action responses for the renderer's services. */
object DlnaSoap {

    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
    private const val CM = "urn:schemas-upnp-org:service:ConnectionManager:1"

    private fun envelope(inner: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>$inner</s:Body>
</s:Envelope>"""

    private fun resp(service: String, action: String, args: String): String =
        envelope("""<u:${action}Response xmlns:u="$service">$args</u:${action}Response>""")

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun time(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%d:%02d:%02d".format(h, m, s)
    }

    // ---- AVTransport ----
    fun setAvTransportUriResponse() = resp(AVT, "SetAVTransportURI", "")
    fun playResponse() = resp(AVT, "Play", "")
    fun pauseResponse() = resp(AVT, "Pause", "")
    fun stopResponse() = resp(AVT, "Stop", "")
    fun seekResponse() = resp(AVT, "Seek", "")

    fun getTransportInfo(state: String) = resp(
        AVT, "GetTransportInfo",
        "<CurrentTransportState>$state</CurrentTransportState>" +
            "<CurrentTransportStatus>OK</CurrentTransportStatus>" +
            "<CurrentSpeed>1</CurrentSpeed>"
    )

    fun getPositionInfo(posMs: Long, durMs: Long, uri: String, meta: String) = resp(
        AVT, "GetPositionInfo",
        "<Track>1</Track>" +
            "<TrackDuration>${time(durMs)}</TrackDuration>" +
            "<TrackMetaData>${esc(meta)}</TrackMetaData>" +
            "<TrackURI>${esc(uri)}</TrackURI>" +
            "<RelTime>${time(posMs)}</RelTime>" +
            "<AbsTime>${time(posMs)}</AbsTime>" +
            "<RelCount>2147483647</RelCount>" +
            "<AbsCount>2147483647</AbsCount>"
    )

    fun getMediaInfo(durMs: Long, uri: String, meta: String) = resp(
        AVT, "GetMediaInfo",
        "<NrTracks>1</NrTracks>" +
            "<MediaDuration>${time(durMs)}</MediaDuration>" +
            "<CurrentURI>${esc(uri)}</CurrentURI>" +
            "<CurrentURIMetaData>${esc(meta)}</CurrentURIMetaData>" +
            "<NextURI></NextURI><NextURIMetaData></NextURIMetaData>" +
            "<PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium>" +
            "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>"
    )

    fun getDeviceCapabilities() = resp(
        AVT, "GetDeviceCapabilities",
        "<PlayMedia>NETWORK,NONE</PlayMedia><RecMedia>NOT_IMPLEMENTED</RecMedia><RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>"
    )

    fun getTransportSettings() = resp(
        AVT, "GetTransportSettings",
        "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>"
    )

    fun getCurrentTransportActions() = resp(
        AVT, "GetCurrentTransportActions",
        "<Actions>Play,Stop,Pause,Seek</Actions>"
    )

    // ---- RenderingControl ----
    fun getVolume(vol: Int) = resp(RC, "GetVolume", "<CurrentVolume>$vol</CurrentVolume>")
    fun setVolumeResponse() = resp(RC, "SetVolume", "")
    fun getMute(muted: Boolean) = resp(RC, "GetMute", "<CurrentMute>${if (muted) 1 else 0}</CurrentMute>")
    fun setMuteResponse() = resp(RC, "SetMute", "")

    // ---- ConnectionManager ----
    fun getProtocolInfo(): String {
        val sink = listOf(
            "video/mp4", "video/x-matroska", "video/webm", "video/3gpp", "video/avc",
            "video/mpeg", "video/quicktime", "video/x-msvideo", "video/x-flv", "video/MP2T",
            "audio/mpeg", "audio/mp4", "audio/aac", "audio/x-wav", "audio/wav", "audio/flac",
            "audio/x-flac", "audio/ogg", "audio/3gpp", "audio/L16",
            "image/jpeg", "image/png", "image/gif", "image/bmp"
        ).joinToString(",") { "http-get:*:$it:*" }
        return resp(
            CM, "GetProtocolInfo",
            "<Source></Source><Sink>${esc(sink)}</Sink>"
        )
    }

    fun getCurrentConnectionIDs() = resp(CM, "GetCurrentConnectionIDs", "<ConnectionIDs>0</ConnectionIDs>")

    fun getCurrentConnectionInfo() = resp(
        CM, "GetCurrentConnectionInfo",
        "<RcsID>0</RcsID><AVTransportID>0</AVTransportID><ProtocolInfo></ProtocolInfo>" +
            "<PeerConnectionManager></PeerConnectionManager><PeerConnectionID>-1</PeerConnectionID>" +
            "<Direction>Input</Direction><Status>OK</Status>"
    )

    fun fault(): String = envelope(
        """<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>""" +
            """<detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>501</errorCode>""" +
            """<errorDescription>Action Failed</errorDescription></UPnPError></detail></s:Fault>"""
    )
}
