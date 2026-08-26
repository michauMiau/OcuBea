package com.ocubea.onvif

/**
 * Minimal ONVIF Profile S SOAP responses served at /onvif/device_service.
 * Enough for NVRs (e.g. Frigate, Synology, Blue Iris) to discover and pull
 * an HTTP MJPEG stream URI from the phone.
 */
object OnvifSoap {

    fun deviceServiceResponse(action: String, host: String, port: Int, deviceName: String): String {
        val streamUri = "http://$host:$port/video"
        val snapshotUri = "http://$host:$port/shot.jpg"
        val body = when {
            action.contains("GetSystemDateAndTime") -> """
                <tds:GetSystemDateAndTimeResponse>
                  <tds:SystemDateAndTime><tt:DateTimeType>NTP</tt:DateTimeType><tt:DaylightSavings>false</tt:DaylightSavings></tds:SystemDateAndTime>
                </tds:GetSystemDateAndTimeResponse>"""

            action.contains("GetCapabilities") -> """
                <tds:GetCapabilitiesResponse>
                  <tds:Capabilities>
                    <tt:Device><tt:XAddr>http://$host:$port/onvif/device_service</tt:XAddr></tt:Device>
                    <tt:Media><tt:XAddr>http://$host:$port/onvif/device_service</tt:XAddr>
                      <tt:StreamingCapabilities><tt:RTPMulticast>false</tt:RTPMulticast><tt:RTP_TCP>false</tt:RTP_TCP><tt:RTP_UDP>false</tt:RTP_UDP></tt:StreamingCapabilities>
                    </tt:Media>
                  </tds:Capabilities>
                </tds:GetCapabilitiesResponse>"""

            action.contains("GetProfiles") -> """
                <trt:GetProfilesResponse>
                  <trt:Profiles fixed="true" token="profile_1">
                    <tt:Name>${deviceName.escapeXml()}</tt:Name>
                    <tt:VideoEncoderConfiguration>
                      <tt:Name>MJPEG</tt:Name><tt:UseCount>1</tt:UseCount><tt:token>vec_1</tt:token>
                      <tt:Encoding>JPEG</tt:Encoding>
                      <tt:Resolution><tt:Width>1280</tt:Width><tt:Height>720</tt:Height></tt:Resolution>
                      <tt:RateControl><tt:FrameRateLimit>15</tt:FrameRateLimit></tt:RateControl>
                    </tt:VideoEncoderConfiguration>
                  </trt:Profiles>
                </trt:GetProfilesResponse>"""

            action.contains("GetStreamUri") -> """
                <trt:GetStreamUriResponse>
                  <trt:MediaUri><tt:Uri>$streamUri</tt:Uri><tt:InvalidAfterConnect>false</tt:InvalidAfterConnect><tt:InvalidAfterReboot>false</tt:InvalidAfterReboot><tt:Timeout>PT60S</tt:Timeout></trt:MediaUri>
                </trt:GetStreamUriResponse>"""

            action.contains("GetSnapshotUri") -> """
                <trt:GetSnapshotUriResponse>
                  <trt:MediaUri><tt:Uri>$snapshotUri</tt:Uri><tt:InvalidAfterConnect>false</tt:InvalidAfterReboot>false</tt:InvalidAfterReboot><tt:Timeout>PT10S</tt:Timeout></trt:MediaUri>
                </trt:GetSnapshotUriResponse>"""

            action.contains("GetServices") -> """
                <tds:GetServicesResponse>
                  <tds:Service><tds:Namespace>http://www.onvif.org/ver10/device/wsdl</tds:Namespace><tds:XAddr>http://$host:$port/onvif/device_service</tds:XAddr></tds:Service>
                  <tds:Service><tds:Namespace>http://www.onvif.org/ver10/media/wsdl</tds:Namespace><tds:XAddr>http://$host:$port/onvif/device_service</tds:XAddr></tds:Service>
                </tds:GetServicesResponse>"""

            else -> "<tds:Fault><s:Code><s:Value>s:Sender</s:Value></s:Code><s:Reason><s:Text>ActionNotSupported</s:Text></s:Reason></tds:Fault>"
        }
        return soapEnvelope(body)
    }

    private fun soapEnvelope(body: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
 xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
 xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
 xmlns:tt="http://www.onvif.org/ver10/schema">
<s:Body>$body</s:Body>
</s:Envelope>"""

    /** Extract SOAP action from the request envelope's Action header or body root. */
    fun extractAction(soapBody: String): String {
        val m = Regex("<([a-z]+:[A-Za-z]+)[\\s>]").findAll(soapBody)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains(":Get") || it.contains(":Set") || it.contains(":Add") }
        return m ?: ""
    }

    private fun String.escapeXml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
