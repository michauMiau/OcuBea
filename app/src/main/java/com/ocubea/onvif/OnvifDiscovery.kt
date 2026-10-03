package com.ocubea.onvif

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal ONVIF compatibility layer:
 *  - WS-Discovery responder (Probe → ProbeMatch) so NVRs find the phone
 *  - SOAP endpoints for GetSystemDateAndTime, GetCapabilities, GetProfiles,
 *    GetStreamUri, GetSnapshotUri (Profile S, media profile over MJPEG HTTP)
 *
 * Runs on the same NanoHTTPD server under the /onvif path (see StreamServer).
 */
class OnvifDiscovery(private val context: Context) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    companion object {
        const val WS_DISCOVERY_PORT = 3702
        const val WS_DISCOVERY_ADDRESS = "239.255.255.250"
        val PROBE_ACTION = "\"http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe\""
        val PROBE_MATCHES_ACTION = "\"http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches\""
    }

    var deviceName: String = "OcuBea"
    var httpPort: Int = 8080
    private var localIp: String = "0.0.0.0"

    fun setLocalIp(ip: String) { localIp = ip }

    /** Start answering WS-Discovery probes on the multicast group. */
    @Synchronized
    fun start() {
        if (running.get()) return
        running.set(true)
        thread = Thread({
            try {
                // Acquire multicast lock (required on many Wi-Fi drivers)
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lock = wifi?.createMulticastLock("ocubea-onvif")
                lock?.setReferenceCounted(false)
                lock?.acquire()

                val group = InetAddress.getByName(WS_DISCOVERY_ADDRESS)
                DatagramSocket(WS_DISCOVERY_PORT).use { socket ->
                    socket.reuseAddress = true
                    socket.soTimeout = 1000
                    while (running.get()) {
                        try {
                            val buf = ByteArray(4096)
                            val packet = DatagramPacket(buf, buf.size)
                            socket.receive(packet)
                            val msg = String(packet.data, 0, packet.length)
                            if (!msg.contains("Probe")) continue
                            val response = buildProbeMatches(msg)
                            val resp = response.toByteArray(Charsets.UTF_8)
                            socket.send(DatagramPacket(resp, resp.size, packet.address, packet.port))
                        } catch (_: Exception) {
                            // timeout loop — normal
                        }
                    }
                }
                lock?.release()
            } catch (e: Exception) {
                if (running.get()) println("ONVIF discovery error: ${e.message}")
            }
        }, "ocubea-onvif-discovery").also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun uuid(): String = "urn:uuid:" + java.util.UUID.randomUUID().toString()

    private val deviceId = "urn:uuid:" + java.util.UUID.randomUUID().toString()

    private fun buildProbeMatches(probeMsg: String): String {
        val relatesTo = Regex("<a:MessageID>(.*?)</a:MessageID>")
            .find(probeMsg)?.groupValues?.get(1) ?: ""
        return """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
<s:Header>
<a:MessageID>${uuid()}</a:MessageID>
<a:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:To>
<a:Action d:mustUnderstand="true">$PROBE_MATCHES_ACTION</a:Action>
<a:RelatesTo>$relatesTo</a:RelatesTo>
</s:Header>
<s:Body>
<d:ProbeMatches>
<d:ProbeMatch>
<a:EndpointReference><a:Address>$deviceId</a:Address></a:EndpointReference>
<d:Types>dn:NetworkVideoTransmitter</d:Types>
<d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/name/$deviceName onvif://www.onvif.org/hardware/OcuBea</d:Scopes>
<d:XAddrs>http://$localIp:$httpPort/onvif/device_service</d:XAddrs>
<d:MetadataVersion>1</d:MetadataVersion>
</d:ProbeMatch>
</d:ProbeMatches>
</s:Body>
</s:Envelope>"""
    }

    /** Build a UUID once per device for stable DeviceIO addresses. */
    fun stableDeviceId(): String = deviceId
}
