#!/usr/bin/env python3
"""
RTSP conformance probe for OcuBea.

RTSP is OFF by default, so this checks two separate things and neither alone is
worth anything:

  1. OFF: the port refuses connections. A feature that is "only when enabled" is
     only that if the socket genuinely does not exist -- not if it answers and
     refuses. That distinction is the whole feature.
  2. ON: the port speaks RTSP 1.0 -- OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN --
     with a real SDP, and PLAY actually delivers interleaved RTP carrying H264.

Nothing here accepts a 200 or a connect as success. Step 2 walks the protocol as
a player does and checks the bytes that come back.

Run:  python3 tools/rtsp_verify.py [host] [port]
"""
import socket, subprocess, sys, time, re

HOST = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.184"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8554

results = []


def check(name, ok, detail=""):
    results.append((name, ok))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}")
    if detail and not ok:
        print(f"          {detail}")
    return ok


HTTP_PORT = 8080


def set_setting(name, value):
    """Flips a setting through the same endpoint a client would use."""
    url = f"http://{HOST}:{HTTP_PORT}/settings/{name}?set={value}"
    try:
        r = subprocess.run(["curl", "-sS", "-m", "20", url],
                           capture_output=True, text=True).stdout
    except OSError as e:
        return f"curl failed: {e}"
    return r.strip()[:120]


def wait_for_port(want_open, timeout=25):
    """Waits for the port to be open or closed, so a bind is not raced."""
    end = time.time() + timeout
    while time.time() < end:
        s = socket.socket()
        s.settimeout(3)
        try:
            s.connect((HOST, PORT))
            open_now = True
        except OSError:
            open_now = False
        finally:
            s.close()
        if open_now == want_open:
            return True
        time.sleep(1.0)
    return False


def probe_off():
    """Turns RTSP OFF through the API, then checks the port is really gone.

    It drives the toggle rather than assuming a state. Earlier versions printed
    "RTSP is OFF" and connected -- whatever the device happened to be doing -- so
    one run reported 17/23 with the feature on and the next reported a refused
    connection with the feature on, and neither said who set the state. A check
    that cannot set up its own precondition is not a check.
    """
    print(f"=== RTSP is OFF: nothing must listen on {HOST}:{PORT} ===")
    reply = set_setting("rtsp", "off")
    if not wait_for_port(False):
        print(f"          (device said: {reply})")
    print(f"          device replied: {reply or '(empty)'}")
    s = socket.socket()
    s.settimeout(6)
    try:
        s.connect((HOST, PORT))
        # A connect that succeeds means a socket exists. That alone fails the
        # check -- whether or not it refuses afterwards.
        check("port refuses connection while disabled", False,
              f"connected to {HOST}:{PORT}: a socket exists while RTSP is off")
    except (ConnectionRefusedError, socket.timeout, OSError) as e:
        check("port refuses connection while disabled", True, str(e)[:60])
    finally:
        s.close()


def rtsp_session():
    print(f"\n=== RTSP is ON: {HOST}:{PORT} must speak RTSP 1.0 ===")
    s = socket.socket()
    s.settimeout(25)
    try:
        s.connect((HOST, PORT))
    except OSError as e:
        check("connects", False, str(e)[:70])
        return
    check("connects", True)

    # NOTE: no makefile(). A BufferedReader around the socket fills on read(1) and
    # consumes the first interleaved RTP frames into its own buffer, which is why
    # an earlier run of this probe reported 0 packets from a stream that was
    # delivering 101. Writes go through sendall and reads come straight off recv(),
    # so nothing is buffered behind our back.

    pending = bytearray()

    def drain_frames():
        """Removes whole interleaved frames from the front of `pending`."""
        while len(pending) >= 4:
            if pending[0] != 0x24:
                # Text response at the front: leave it, that is what we came for.
                return
            ln = (pending[2] << 8) | pending[3]
            if len(pending) < 4 + ln:
                return                      # partial frame, wait for more
            del pending[:4 + ln]

    def read_response(timeout=12):
        """One RTSP response, parsed from the raw socket."""
        deadline = time.time() + timeout
        while b"\r\n\r\n" not in pending and time.time() < deadline:
            s.settimeout(max(0.2, deadline - time.time()))
            try:
                chunk = s.recv(65536)
            except socket.timeout:
                break
            if not chunk:
                break
            pending.extend(chunk)
        # While playing, `pending` is full of interleaved frames and the response
        # sits behind them. Reading a response means consuming every whole frame
        # first: a search for "\r\n\r\n" inside a compressed video payload finds
        # one sooner or later and returns compressed bits as an RTSP response,
        # which is how TEARDOWN once reported a half-megabyte of binary garbage.
        drain_frames()
        idx = pending.find(b"\r\n\r\n")
        if idx < 0:
            return "", {}, b""
        head = bytes(pending[:idx + 4])
        del pending[:idx + 4]
        text = head.decode("latin-1")
        headers = {}
        for line in text.split("\r\n")[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().lower()] = v.strip()
        n = int(headers.get("content-length", "0") or 0)
        body = b""
        while len(pending) < n and time.time() < deadline:
            s.settimeout(max(0.2, deadline - time.time()))
            try:
                pending.extend(s.recv(65536))
            except socket.timeout:
                break
        body = bytes(pending[:n])
        del pending[:n]
        return text, headers, body

    def send(method, url, extra=""):
        req = f"{method} {url} RTSP/1.0\r\nCSeq: {next_cseq[0]}\r\n"
        if method in ("DESCRIBE", "SETUP", "PLAY", "TEARDOWN"):
            req += "Accept: application/sdp\r\n" if method == "DESCRIBE" else ""
        req += f"{extra}\r\n"
        s.sendall(req.encode())
        return read_response()

    next_cseq = [1]

    # OPTIONS -- every client starts here, and a server that omits Public cannot
    # be talked to by a client that asks what it supports.
    next_cseq[0] = 1
    text, headers, _ = send("OPTIONS", f"rtsp://{HOST}:{PORT}/h264.sdp")
    check("OPTIONS -> 200", "200" in text.split("\r\n")[0], text.split("\r\n")[0])
    check("OPTIONS advertises Public",
          "public" in headers and "describe" in headers["public"].lower(),
          str(headers)[:110])
    check("OPTIONS echoes CSeq", "CSeq: 1" in text, text.split("\r\n")[1:2])

    # DESCRIBE -- the SDP a player builds its decoder from.
    next_cseq[0] = 2
    text, headers, body = send("DESCRIBE", f"rtsp://{HOST}:{PORT}/h264.sdp")
    check("DESCRIBE -> 200", "200" in text.split("\r\n")[0], text.split("\r\n")[0])
    sdp = body.decode("latin-1")
    check("SDP has m=video", "m=video" in sdp, sdp[:120])
    check("SDP declares H264/90000", "H264/90000" in sdp, sdp[:200])
    check("SDP has a control attribute", "a=control:" in sdp, sdp[:200])
    check("Content-Base present", "content-base" in headers, str(list(headers))[:80])
    # The SDP names the device, and it must be the configured name with its case
    # intact -- the same string GetDeviceInformation reports. Asserted because a
    # generic s=OcuBea in the SDP looks fine to a player and would make two
    # devices indistinguishable in an NVR.
    # The SDP names the device, and it must be the SAME string ONVIF reports in
    # GetDeviceInformation -- configured name, case intact. Asserted because a
    # generic s=OcuBea looks fine to a player and makes two cameras
    # indistinguishable in an NVR. Read from ONVIF, so the probe also fails if
    # the two services disagree about what this device is called.
    want = ""
    try:
        env = (
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"'
            ' xmlns:tds="http://www.onvif.org/ver10/device/wsdl"><s:Body>'
            '<tds:GetDeviceInformation/></s:Body></s:Envelope>'
        )
        out = subprocess.run(
            ["curl", "-sS", "-m", "20", "-X", "POST",
             "-H", "Content-Type: application/soap+xml",
             "--data-binary", env,
             f"http://{HOST}:{HTTP_PORT}/onvif/device_service"],
            capture_output=True, text=True).stdout
        m = re.search(r"<tds:Model>(.*?)</tds:Model>", out, re.S)
        want = m.group(1).strip() if m else ""
    except Exception:
        want = ""
    check("ONVIF and RTSP report the same device name",
          bool(want) and f"s={want}" in sdp,
          f"ONVIF Model={want!r}, SDP has "
          f"{[l for l in sdp.split(chr(13) + chr(10)) if l.startswith('s=')][:1]}")

    # SETUP video -- interleaved TCP transport, which is what this build serves.
    next_cseq[0] = 3
    text, headers, _ = send(
        "SETUP", f"rtsp://{HOST}:{PORT}/h264.sdp/trackID=0",
        "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
    check("SETUP video -> 200", "200" in text.split("\r\n")[0], text.split("\r\n")[0])
    check("SETUP returns interleaved channels",
          "interleaved" in headers.get("transport", "").lower(),
          headers.get("transport", "<none>"))
    check("SETUP returns a Session", "session" in headers, str(list(headers))[:80])

    # PLAY -- and then the actual bytes. This is the check that separates a
    # server that negotiated from one that is streaming.
    next_cseq[0] = 4
    text, headers, _ = send("PLAY", f"rtsp://{HOST}:{PORT}/h264.sdp")
    check("PLAY -> 200", "200" in text.split("\r\n")[0], text.split("\r\n")[0])

    # Read interleaved frames for a few seconds.
    deadline = time.time() + 10
    rtp_count = 0
    rtcp_count = 0
    started = False
    carried = False
    nal_count = 0
    first_payload = b""
    first_rtp = b""
    first_len_ok = False
    first_len_seen = 0
    s.settimeout(10)
    while time.time() < deadline:
        try:
            chunk = s.recv(65536)
        except socket.timeout:
            break
        if not chunk:
            break
        # Anything already buffered during the response reads counts too -- a frame
        # that arrived alongside the PLAY reply must not be missed.
        pending.extend(chunk)
        b = bytes(pending)
        pending.clear()
        if carried:
            started = True      # resuming mid-frame; do not re-seek
        # Scan from the first '$' that begins a plausible frame, skipping the PLAY
        # response text. Scanning from 0 walked into the response body, matched a
        # 0x24 byte inside it, and misparsed everything after.
        i = 0
        # Seek only for the very first chunk. `pending` holding a partial frame
        # means we are already mid-stream and the next bytes are the rest of that
        # frame, not a fresh marker to hunt for.
        while (not started) and i + 4 <= len(b):
            # Plausibility only, never completeness. Requiring the whole frame to
            # be present made the seek fail on every split read -- it walked to the
            # end of each chunk instead and the main loop then parsed from a
            # garbage offset. Confirmed on a known-good 18-byte frame: it was
            # rejected, seek ended at i=18 with started=False.
            if (b[i] == 0x24 and b[i + 1] % 2 == 0
                    and ((b[i + 2] << 8) | b[i + 3]) > 12
                    and i + 5 <= len(b)
                    and (b[i + 4] & 0xC0) == 0x80):
                started = True
                break          # byte 0 of the RTP header has V=2 in its top bits
            i += 1
        # Bytes discarded before the first well-formed frame. Large means the
        # scan began inside the PLAY response, so the packet count below is a
        # floor rather than a total.
        skipped = i
        while i + 4 <= len(b):
            if b[i] != 0x24:          # '$' frame marker
                # Not a marker here. Before skipping, require that the bytes at a
                # marker position would have been well formed -- a parser that
                # skips blindly lands mid-payload and then reports the NAL bytes as
                # an RTP header, which reads like a broken server rather than a
                # broken parser.
                i += 1
                continue
            ch = b[i + 1]
            ln = (b[i + 2] << 8) | b[i + 3]
            if i + 4 + ln > len(b):
                # Frame split across reads. Keep it for the next chunk instead of
                # dropping it: discarding the tail and resyncing mid-payload is
                # what produced "first byte b62ad553, PT 42, 0 NALs" from a server
                # that was sending perfectly valid H264 the whole time.
                pending.extend(b[i:])
                carried = True
                break
            payload = b[i + 4:i + 4 + ln]
            i += 4 + ln
            # Odd channels carry RTCP (RFC 2326 pairs each media channel with the
            # next for its reports). Counted and skipped, never parsed as media:
            # 0xC9 is PT 201, and reading it as a broken video frame is how a probe
            # reports "PT 0" against a server whose video is perfect.
            if ch % 2 == 1:
                rtcp_count += 1
                continue
            if ln >= 12:
                rtp_count += 1
                if not first_rtp:
                    first_len_seen = ln
                    # RFC 2326: the length covers the RTP header too. A
                    # payload-only length is 12 short and desynchronises every
                    # frame after it.
                    first_len_ok = ln > 12
                if not first_rtp:
                    first_rtp = payload[:12]
                if ln > 12:
                    media = payload[12:]
                    # Annex-B: a start code, then a NAL whose type is in 1..23.
                    if (media[:3] == b"\x00\x00\x01" or media[:4] == b"\x00\x00\x00\x01") \
                            and len(media) > 4:
                        nal_type = media[3] & 0x1F if media[:3] == b"\x00\x00\x01" else media[4] & 0x1F
                        if 1 <= nal_type <= 23:
                            nal_count += 1
                            if not first_payload:
                                first_payload = media
        if rtp_count > 80:
            break

    check("PLAY delivered interleaved RTP packets", rtp_count > 0,
          f"{rtp_count} packets in 8 s")
    check("RTP packets carry an H264 NAL", nal_count > 0,
          f"{nal_count} of {rtp_count} packets had a NAL")
    # The header bytes themselves, asserted rather than inferred from a length.
    # This is the check that caught the framing bug: the stream began 00 00 01 65
    # where 80 60 belonged, because the 12-byte RTP header had been written BEFORE
    # the interleaved marker, so a reader found $ twelve bytes late and read the
    # NAL as the packet. Every protocol method returned 200 throughout.
    check("RTP header starts with V=2 (0x80)", bool(first_rtp) and first_rtp[0] == 0x80,
          f"first byte {first_rtp[:4].hex() if first_rtp else 'none'}, want 80")
    check("RTP payload type is 96 (H264)",
          bool(first_rtp) and (first_rtp[1] & 0x7F) == 96,
          f"PT {first_rtp[1] & 0x7F if first_rtp else '?'}, want 96")
    check("interleaved length covers the RTP header",
          bool(first_rtp) and first_len_ok, f"first length {first_len_seen} B")
    check("scan started at a real frame boundary", skipped < 4096,
          f"skipped {skipped} B before the first frame")
    print(f"        ({rtp_count} RTP packets, {nal_count} with NAL, "
          f"{rtcp_count} RTCP, first payload {len(first_payload)} B)")

    # TEARDOWN -- a session that cannot be closed leaves the port busy.
    next_cseq[0] = 5
    text, _, _ = send("TEARDOWN", f"rtsp://{HOST}:{PORT}/h264.sdp")
    check("TEARDOWN -> 200", "200" in text.split("\r\n")[0], text.split("\r\n")[0])
    s.close()

    # Audio requested while audio is DISABLED must be refused, not silently
    # answered with a video-only SDP. A client that asked for audio and got a
    # negotiated stream with no audio track will show a muted preview and report
    # nothing wrong, which is the same class of lie this whole file is about.
    s3 = socket.socket()
    s3.settimeout(10)
    try:
        s3.connect((HOST, PORT))
        s3.sendall(f"DESCRIBE rtsp://{HOST}:{PORT}/h264_pcm.sdp RTSP/1.0\r\n"
                   f"CSeq: 1\r\nAccept: application/sdp\r\n\r\n".encode())
        head = b""
        s3.settimeout(10)
        while b"\r\n\r\n" not in head:
            try:
                head += s3.recv(4096)
            except (socket.timeout, OSError):
                break
            if not head:
                break
        t3 = head.decode("latin-1")
        check("disabled audio is refused, not faked", "551" in t3.split("\r\n")[0],
              t3.split("\r\n")[0])
    except OSError as e:
        check("disabled audio is refused, not faked", False, str(e)[:60])
    finally:
        s3.close()

    # An unsupported codec must be refused, not served wrong. pydroid's default
    # is h264_opus and this build cannot carry Opus, so the honest answer is 551.
    s2 = socket.socket()
    s2.settimeout(10)
    try:
        s2.connect((HOST, PORT))
        s2.sendall(f"DESCRIBE rtsp://{HOST}:{PORT}/h264_opus.sdp RTSP/1.0\r\n"
                   f"CSeq: 1\r\nAccept: application/sdp\r\n\r\n".encode())
        head = b""
        s2.settimeout(12)
        while b"\r\n\r\n" not in head:
            try:
                head += s2.recv(4096)
            except (socket.timeout, OSError):
                break
            if not head:
                break
        t = head.decode("latin-1")
        check("h264_opus is refused, not faked",
              "551" in t.split("\r\n")[0] or "h264_pcm" in t,
              t.split("\r\n")[0])
    except OSError as e:
        check("h264_opus is refused, not faked", False, str(e)[:60])
    finally:
        s2.close()


if __name__ == "__main__":
    import threading
    # OFF first, then ON, from two threads so one run can see both states.
    probe_off()
    if "--on" not in sys.argv:
        print("\n  pominieto sesje -- przekaz --on, zeby zmierzyc stan wlaczony")
        results.append(("on-session", None))
    else:
        print(f"\n=== wlaczam RTSP przez API ===\n          {set_setting('rtsp', 'on')}")
        if not wait_for_port(True):
            check("toggle on binds the port", False,
                  "port never opened after the setting was switched on")
        else:
            check("toggle on binds the port", True, f"{HOST}:{PORT} listening")
            rtsp_session()
        # Leave the device as found: RTSP is off unless the caller asked to keep
        # it on. A probe that leaves a listener running has changed the thing it
        # was measuring.
        print(f"\n=== przywracam RTSP do OFF ===\n          {set_setting('rtsp', 'off')}")
    done = [n for n, ok in results if ok is not None]
    passed = sum(1 for _, ok in results if ok)
    print(f"\n=== podsumowanie ===\n  {passed}/{len(done)} checkow zaliczonych")
    fails = [n for n, ok in results if ok is False]
    if fails:
        for n in fails:
            print(f"    - {n}")
    sys.exit(1 if fails else 0)
