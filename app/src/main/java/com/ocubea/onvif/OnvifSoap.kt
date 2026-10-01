package com.ocubea.onvif

/**
 * Minimal ONVIF Profile S SOAP responses served at /onvif/device_service.
 * Enough for NVRs (e.g. Frigate, Synology, Blue Iris) to discover and pull
 * an HTTP MJPEG stream URI from the phone.
 */
object OnvifSoap {

    /**
     * The WSDL for the device service.
     *
     * Not decoration: a generated client fetches this and builds its stubs from
     * it, so without it the service is unreachable however correct the SOAP is.
     * That is exactly the state tools/onvif_verify.py caught -- the endpoint
     * answered 200 to every request while no client could have used it.
     *
     * Only the operations actually implemented are declared. An operation listed
     * here and missing from deviceServiceResponse() is a promise the device cannot
     * keep, so the two lists are the same one.
     */
    fun deviceServiceWsdl(host: String, port: Int): String {
        val endpoint = "http://$host:$port/onvif/device_service"
        val ops = OPERATIONS.joinToString("\n") { op ->
            """    <operation name="$op">
      <input message="tns:${op}Request"/>
      <output message="tns:${op}Response"/>
    </operation>"""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
  xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
  xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
  xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
  xmlns:tt="http://www.onvif.org/ver10/schema"
  xmlns:tns="http://www.onvif.org/ver10/device/wsdl"
  targetNamespace="http://www.onvif.org/ver10/device/wsdl"
  name="OnvifDeviceService">
  <types/>
  <wsdl:message name="GetSystemDateAndTimeResponse"/>
  <wsdl:message name="GetServicesResponse"/>
  <wsdl:message name="GetDeviceInformationResponse"/>
  <wsdl:message name="GetCapabilitiesResponse"/>
  <wsdl:message name="GetProfilesResponse"/>
  <wsdl:message name="GetStreamUriResponse"/>
  <wsdl:message name="GetSnapshotUriResponse"/>
  <wsdl:portType name="OnvifDevicePortType">
$ops
  </wsdl:portType>
  <wsdl:binding name="OnvifDeviceBinding" type="tns:OnvifDevicePortType">
    <soap:binding style="document"
      transport="http://schemas.xmlsoap.org/soap/http"/>
  </wsdl:binding>
  <wsdl:service name="OnvifDeviceService">
    <wsdl:port name="OnvifDevicePort" binding="tns:OnvifDeviceBinding">
      <soap:address location="$endpoint"/>
    </wsdl:port>
  </wsdl:service>
</wsdl:definitions>"""
    }

    /**
     * The operations this service implements.
     *
     * The WSDL and deviceServiceResponse() are generated from this one list, so
     * an operation cannot be advertised without being answered.
     */
    val OPERATIONS = listOf(
        "GetSystemDateAndTime", "GetServices", "GetDeviceInformation",
        "GetCapabilities", "GetProfiles", "GetStreamUri", "GetSnapshotUri"
    )


    fun deviceServiceResponse(
        action: String, host: String, port: Int, deviceName: String,
        versionName: String, deviceId: String,
    ): String {
        val streamUri = "http://$host:$port/video"
        val snapshotUri = "http://$host:$port/shot.jpg"
        val body = when {
            action.contains("GetDeviceInformation") -> """
                <tds:GetDeviceInformationResponse>
                  <tds:Manufacturer>Occult</tds:Manufacturer>
                  <tds:Model>${deviceName.escapeXml()}</tds:Model>
                  <tds:FirmwareVersion>${versionName.escapeXml()}</tds:FirmwareVersion>
                  <tds:SerialNumber>$deviceId</tds:SerialNumber>
                  <tds:HardwareId>$deviceId</tds:HardwareId>
                </tds:GetDeviceInformationResponse>"""

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

    /**
     * The operation name from a SOAP request, without its namespace prefix.
     *
     * Three ways this was wrong before, all of them found by tools/onvif_verify.py
     * only after the probe itself was corrected to send what a client sends:
     *
     *  - It matched `<tds:GetProfiles ` with a required space or bracket after the
     *    name, so a self-closing `<tds:GetProfiles/>` never matched -- and
     *    self-closing is how every generated client encodes an argument-less
     *    operation, which is nearly all of them.
     *  - It was an alternation, `(Get|Set|Add|...)`, so it captured `Get` out of
     *    `GetDeviceInformation` and the response builder -- which keys on
     *    contains("GetDeviceInformation") -- fell through to ActionNotSupported.
     *    The element name must be captured whole and then matched against the
     *    operations this device implements; truncating a name by prefix is not
     *    the same thing as reading it.
     *  - It ignored the SOAP 1.2 action header, which some clients send instead.
     *
     * Returns the bare name ("GetProfiles"), or "" when nothing matches.
     */
    fun extractAction(soapBody: String): String {
        // Every element name in the request, prefix stripped, captured WHOLE. A
        // name is accepted only on an exact match against an implemented
        // operation, so no fragment and no prefix can ever satisfy it.
        val fromBody = Regex("<(?:[A-Za-z][\\w.-]*:)?([A-Za-z][\\w.-]*)")
            .findAll(soapBody)
            .map { it.groupValues[1] }
            .firstOrNull { name -> OPERATIONS.contains(name) }
        if (fromBody != null) return fromBody

        // The action header, for a client that puts the operation there and sends
        // an empty Body.
        val fromHeader = Regex("<(?:[A-Za-z][\\w.-]*:)?Action[^>]*>([^<]+)<")
            .findAll(soapBody)
            .map { it.groupValues[1].trim().trim('"') }
            .map { it.substringAfterLast('/').substringAfterLast(':') }
            .firstOrNull { candidate -> OPERATIONS.contains(candidate) }
        if (fromHeader != null) return fromHeader

        return ""
    }

    private fun String.escapeXml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
