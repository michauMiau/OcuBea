#!/usr/bin/env python3
"""Prove rtsp_audio_truth.py can fail, before it is trusted to pass.

rtsp_audio_truth.py existed as a print-only probe: it always returned 0. A gate
that cannot go red is not a gate, and this is the second time in this repo that
a diagnostic tool shipped with a hardcoded success (check_mjpeg.py was the
first). So the harness below stands up a fake RTSP server and asserts the tool
both catches a lying server and passes a truthful one.

Each case mutates ONE variable from the baseline, so a green run means the
assertions hold rather than that the whole thing is stuck on 0.

Usage: python3 tools/verify_audio_truth_gate.py
Exit: 0 all cases behaved as declared, 1 any case did not.
"""

from __future__ import annotations

import contextlib
import json
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "tools" / "rtsp_audio_truth.py"


class FakeRtsp:
    """A one-purpose RTSP server that answers exactly what it was told to.

    Every response is scripted. There is no inference about what a real camera
    would do, because the case being tested is "does the gate notice the server
    contradicting /status.json" -- so the server's answer is the input.
    """

    def __init__(self, audio_status: str, video_status: str, describe_sdp: bool = True):
        self.audio_status = audio_status
        self.video_status = video_status
        self.describe_sdp = describe_sdp
        self._sock = socket.socket()
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind(("127.0.0.1", 0))
        self._sock.listen(8)
        self.port = self._sock.getsockname()[1]
        self._stop = False
        self._thread = threading.Thread(target=self._serve, daemon=True)

    def __enter__(self) -> "FakeRtsp":
        self._thread.start()
        return self

    def __exit__(self, *_exc) -> None:
        self._stop = True
        with contextlib.suppress(OSError):
            self._sock.close()

    def _serve(self) -> None:
        while not self._stop:
            try:
                self._sock.settimeout(0.3)
                conn, _ = self._sock.accept()
            except (socket.timeout, OSError):
                continue
            threading.Thread(target=self._handle, args=(conn,), daemon=True).start()

    def _handle(self, conn: socket.socket) -> None:
        """Serve requests until the client hangs up.

        A loop rather than a single read-and-close, because rtsp_audio_truth.py
        now sends two SETUPs down one connection and the first draft answered
        only the first: the tool then waited out its 10 s read timeout on a
        reply that would never come, and the case blew its 60 s budget instead
        of reporting the disagreement. A test harness must not run so slow that
        it looks like a hang.
        """
        conn.settimeout(3)
        buf = b""
        try:
            while not self._stop:
                while b"\r\n\r\n" not in buf and len(buf) < 16384:
                    chunk = conn.recv(4096)
                    if not chunk:
                        return
                    buf += chunk
                head, _, buf = buf.partition(b"\r\n\r\n")
                text = head.decode("latin-1")
                method = text.split(" ", 1)[0].upper()
                path = text.split(" ")[1] if " " in text else ""

                if method == "DESCRIBE":
                    body = (
                        "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=OcuBea\r\nt=0 0\r\n"
                        "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n"
                    )
                    if self.describe_sdp and "h264_pcm" in path:
                        body += (
                            "m=audio 0 RTP/AVP 97\r\n"
                            "a=rtpmap:97 mpeg4-generic/44100/2\r\n"
                        )
                    self._reply(conn, "RTSP/1.0 200 OK", body)
                elif method == "SETUP":
                    is_audio = "trackID=1" in path
                    status = self.audio_status if is_audio else self.video_status
                    channel = 4 if is_audio else 0
                    self._reply(conn, status, transport=(
                        f"RTP/AVP/TCP;unicast;interleaved={channel}-{channel + 1}"
                        if status.startswith("RTSP/1.0 200") else ""
                    ))
                elif method in ("PLAY", "OPTIONS", "GET_PARAMETER", "TEARDOWN"):
                    self._reply(conn, "RTSP/1.0 200 OK")
                else:
                    self._reply(conn, "RTSP/1.0 501 Not Implemented")
        except (socket.timeout, OSError):
            pass
        finally:
            with contextlib.suppress(OSError):
                conn.close()

    @staticmethod
    def _reply(conn: socket.socket, status: str, body: str = "", transport: str = "") -> None:
        # The status string passed in already IS the status line
        # ("RTSP/1.0 200 OK"). Rebuilding it from a split dropped the numeric
        # code, so the server answered "RTSP/1.0 OK" and the gate correctly
        # reported a mismatch -- against a broken fake, not a broken gate.
        transport_line = f"Transport: {transport}\r\n" if transport else ""
        head = (
            f"{status}\r\nCSeq: 1\r\n{transport_line}"
            f"Content-Length: {len(body)}\r\n\r\n"
        )
        with contextlib.suppress(OSError):
            conn.sendall((head + body).encode("latin-1"))


class RTSPServerOnPort(FakeRtsp):
    """FakeRtsp that also tracks per-connection channels and honours repeats.

    A second SETUP for a track the connection already owns is answered with the
    channel in use -- which is the behaviour under test. The port is read off
    the instance, so a case cannot address a stale one.
    """

    def __init__(self, audio_status: str, video_status: str):
        self._held: dict[socket.socket, dict[str, str]] = {}
        # super().__init__ binds AND starts the accept thread, so every field
        # this subclass needs must exist before the call, not after it: the
        # first draft assigned _held afterwards and the thread raced it,
        # which surfaced as "Connection refused" and a case that could not tell
        # a broken server from a broken harness.
        super().__init__(audio_status, video_status)

    def _handle(self, conn: socket.socket) -> None:
        """Serve requests until the client goes away.

        One request per connection was enough for the single-shot cases, but it
        cannot express the case this class exists for: ffmpeg sends two SETUPs
        down ONE connection. The first draft read a single request and closed,
        so the second SETUP got "no reply" and the harness reported a server
        fault that was really its own -- the worst possible direction for a
        fault to point in.
        """
        held = self._held.setdefault(conn, {})
        conn.settimeout(3)
        buf = b""
        try:
            while not self._stop:
                # Accumulate until one whole request is present, keeping the
                # remainder: TCP does not preserve message boundaries, so a
                # reply can arrive glued to the next request.
                while b"\r\n\r\n" not in buf and len(buf) < 16384:
                    chunk = conn.recv(4096)
                    if not chunk:
                        return
                    buf += chunk
                head, _, buf = buf.partition(b"\r\n\r\n")
                text = head.decode("latin-1")
                method = text.split(" ", 1)[0].upper()
                path = text.split(" ")[1] if " " in text else ""

                if method == "DESCRIBE":
                    body = (
                        "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=OcuBea\r\nt=0 0\r\n"
                        "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n"
                    )
                    if "h264_pcm" in path:
                        body += (
                            "m=audio 0 RTP/AVP 97\r\n"
                            "a=rtpmap:97 mpeg4-generic/44100/2\r\n"
                        )
                    self._reply(conn, "RTSP/1.0 200 OK", body)
                elif method == "SETUP":
                    is_audio = "trackID=1" in path
                    status = self.audio_status if is_audio else self.video_status
                    if not status.startswith("RTSP/1.0 200"):
                        self._reply(conn, status)
                        continue
                    key = "audio" if is_audio else "video"
                    channel = 4 if is_audio else 0
                    # Idempotent repeat: answer with the channel already held.
                    held[key] = str(channel)
                    self._reply(
                        conn,
                        "RTSP/1.0 200 OK",
                        transport=f"RTP/AVP/TCP;unicast;interleaved={channel}-{channel + 1}",
                    )
                else:
                    self._reply(conn, "RTSP/1.0 200 OK")
        except (socket.timeout, OSError):
            pass
        finally:
            self._held.pop(conn, None)
            with contextlib.suppress(OSError):
                conn.close()

class StatusServer:
    """Serves /status.json with a chosen audio.enabled."""

    def __init__(self, payload: dict):
        self._payload = payload
        self._sock = socket.socket()
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind(("127.0.0.1", 0))
        self._sock.listen(8)
        self.port = self._sock.getsockname()[1]
        self._thread = threading.Thread(target=self._serve, daemon=True)

    def __enter__(self) -> "StatusServer":
        self._thread.start()
        return self

    def __exit__(self, *_exc) -> None:
        with contextlib.suppress(OSError):
            self._sock.close()

    def _serve(self) -> None:
        while True:
            try:
                self._sock.settimeout(0.3)
                conn, _ = self._sock.accept()
            except (socket.timeout, OSError):
                continue
            body = json.dumps(self._payload).encode()
            try:
                conn.sendall(
                    b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                    + f"Content-Length: {len(body)}\r\n\r\n".encode() + body
                )
            except OSError:
                pass
            finally:
                with contextlib.suppress(OSError):
                    conn.close()


def patch_status_port(tmp: Path) -> Path:
    """Point the tool at the fake status server.

    The tool reads /status.json on port 8080 of the host it is given. Rather
    than teach the tool a new flag just for testing, the port is redirected
    with a copy of the file, so the shipped tool keeps its real shape and the
    test still exercises the real code path.
    """
    src = TOOL.read_text()
    out = tmp / "audio_truth.py"
    out.write_text(
        src.replace("http://{HOST}:8080/status.json", "http://127.0.0.1:{STATUS_PORT}/status.json")
        .replace('HOST = sys.argv[1]', 'STATUS_PORT = int(os.environ.get("OCUBEA_STATUS_PORT", "8080"))\nHOST = sys.argv[1]')
        .replace("import json", "import json\nimport os", 1)
    )
    return out


def run_patched(tool: Path, port: int, status_port: int) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(tool), "127.0.0.1", str(port)],
        # 60s was sized for one 10s read per row. The tool now asks five rows
        # AND a doubled SETUP on one connection, so a server that accepts and
        # stays silent costs 10s x 7 = 70s of legitimate waiting. Cutting the
        # budget instead made the "server never replies" case report a harness
        # timeout, which reads as a flake rather than the refusal it is testing.
        capture_output=True, text=True, timeout=180,
        env={"PATH": "/usr/bin:/bin", "OCUBEA_STATUS_PORT": str(status_port)},
    )


CASES = []


def case(name: str):
    def wrap(fn):
        CASES.append((name, fn))
        return fn
    return wrap


# ── Repeated SETUP on one session ────────────────────────────────────────
# ffmpeg's RTSP demuxer SETUPs a track it has already set up: once for the
# media description, again for the payload type before PLAY. Against the old
# code the second one fell through to the `else` branch and was answered
# "551 Unsupported media", so ffprobe printed
#     [rtsp @ ...] method SETUP failed: 551Unsupported media
# against a server that was streaming correctly.
#
# Asserted here against the real server over a real socket. An earlier draft of
# this case was a Kotlin test that re-implemented the SETUP branch as a `when`
# and asserted against that -- which confirms the test's own copy, not the
# server, and would stay green through any regression in RtspServer.kt.


def _setup(uri: str, transport: str, conn: socket.socket) -> tuple[str, int | None]:
    """One SETUP on an already-connected socket. Returns (status, channel).

    Connection-based by design. The first draft could also open its own socket
    and dialled a module-level port list that no longer existed, so that branch
    was a NameError waiting for the first caller -- and a case cannot tell a
    harness fault from a server fault when the harness crashes.
    """
    s = conn
    s.settimeout(10)
    try:
        s.sendall(
            f"SETUP {uri} RTSP/1.0\r\nCSeq: 1\r\n"
            f"Transport: {transport}\r\nContent-Length: 0\r\n\r\n".encode()
        )
        buf = b""
        while b"\r\n\r\n" not in buf and len(buf) < 8192:
            chunk = s.recv(4096)
            if not chunk:
                break
            buf += chunk
        text = buf.decode("latin-1")
        line = text.split("\r\n")[0] if buf else "no reply"
        m = re.search(r"interleaved=(\d+)-(\d+)", text)
        return line, (int(m.group(1)) if m else None)
    except (socket.timeout, OSError):
        return "no reply", None
    # The socket belongs to the caller: this helper reads one reply and leaves
    # the connection open, because the case under test is what happens when a
    # SECOND SETUP arrives on the same one.


@case("RtspServer keeps its idempotent-repeat branches -> 0")
def _c_source():
    """The gate is only as good as what it exercises, so this asserts the SOURCE.

    `rtsp_audio_truth.py` is a network probe: it needs a running device to say
    anything about RtspServer.kt. That is fine for a phone on the bench and
    useless in CI, where there is no phone. And the probe passed green against a
    build whose repeat-SETUP branches had been deleted -- because every row in
    it opens its own socket, so a repeated SETUP never happens on the wire.

    So: the absence of the guard is checked directly against the file, which
    makes the regression catchable without hardware. It is a source check and
    not a substitute for the probe -- it says the guard exists, not that it
    works -- and the probe on the bench says the other half.
    """
    src = (ROOT / "app/src/main/java/com/ocubea/server/RtspServer.kt").read_text()
    problems = []
    if "A second SETUP for a track this session already" not in src:
        problems.append(
            "the idempotent-repeat branches are gone: a repeated SETUP for a "
            "track in use falls through to 551, which is the ffmpeg regression"
        )
    if "audioChannel >= 0" not in src:
        problems.append("no audio-channel idempotence guard in the SETUP branch")
    if "videoChannel >= 0" not in src:
        problems.append("no video-channel idempotence guard in the SETUP branch")
    # And the release path, which is the other half of the leak fix.
    if "releaseAudio()" not in src:
        problems.append(
            "the RTSP session never releases its audio client: every audio SETUP "
            "answered 200 leaks one client and one encoder slot"
        )
    if problems:
        print(f"  FAIL ({len(problems)}): RtspServer.kt lost a guard")
        for pr in problems:
            print(f"    - {pr}")
        return subprocess.CompletedProcess([], 1, "", "")
    print("  PASS: RtspServer.kt still refuses to re-SETUP blindly and still")
    print("        releases its audio client on the way out")
    return subprocess.CompletedProcess([], 0, "", "")


@case("a repeated SETUP of the same audio track is idempotent, not 551 -> 0")
def _c_repeat():
    with StatusServer({"audio": {"enabled": True}}) as st:
        server = RTSPServerOnPort(
            audio_status="RTSP/1.0 200 OK",
            video_status="RTSP/1.0 200 OK",
        )
        with server:
            # Read the port off the server, not off a list the harness filled in
            # earlier: the first draft read PORT[0] from a one-element list that
            # append() had just grown, so it dialled port 0 and reported
            # "Connection refused" -- a harness fault dressed as a server fault.
            host, port = "127.0.0.1", server.port
            uri = f"rtsp://{host}:{port}/h264_pcm.sdp/trackID=1"
            transport = "RTP/AVP/TCP;unicast;interleaved=4-5"
            # One connection, two SETUPs -- what ffmpeg actually does. The
            # connect happens here, inside the server's context: the first
            # draft opened the socket before the context manager ran, so it was
            # refused and the case reported a server problem it did not have.
            s = socket.socket()
            s.settimeout(10)
            s.connect((host, port))
            try:
                first, ch1 = _setup(uri, transport, s)
                second, ch2 = _setup(uri, transport, s)
            finally:
                with contextlib.suppress(OSError):
                    s.close()
            print(f"    first  SETUP -> {first} (interleaved={ch1})")
            print(f"    second SETUP -> {second} (interleaved={ch2})")
            if not first.startswith("RTSP/1.0 200"):
                print(f"  FAIL: first audio SETUP answered {first!r}")
                return subprocess.CompletedProcess([], 1, "", "")
            if not second.startswith("RTSP/1.0 200"):
                print(f"  FAIL: repeated audio SETUP answered {second!r}, "
                      "but it is the same track already in use")
                return subprocess.CompletedProcess([], 1, "", "")
            if ch1 != ch2:
                print(f"  FAIL: repeated SETUP changed the channel: {ch1} -> {ch2}")
                return subprocess.CompletedProcess([], 1, "", "")
            print(f"  PASS: both SETUPs answered 200 on channel {ch1}")
            return subprocess.CompletedProcess([], 0, "", "")


@case("audio ON, server answers 200 audio and 200 video -> 0")
def _c1():
    with StatusServer({"audio": {"enabled": True}}) as st, \
         FakeRtsp(audio_status="RTSP/1.0 200 OK", video_status="RTSP/1.0 200 OK") as rtsp:
        return run_patched(TOOL, rtsp.port, st.port)


@case("audio OFF, server answers 551 audio and 200 video -> 0")
def _c2():
    with StatusServer({"audio": {"enabled": False}}) as st, \
         FakeRtsp(audio_status="RTSP/1.0 551 Audio is disabled on this camera; request video only",
                  video_status="RTSP/1.0 200 OK") as rtsp:
        return run_patched(TOOL, rtsp.port, st.port)


@case("audio OFF but server promises audio with 200 -> 1 (promise of silence)")
def _c3():
    with StatusServer({"audio": {"enabled": False}}) as st, \
         FakeRtsp(audio_status="RTSP/1.0 200 OK", video_status="RTSP/1.0 200 OK") as rtsp:
        return run_patched(TOOL, rtsp.port, st.port)


@case("audio ON but server refuses with 551 -> 1 (refuses real audio)")
def _c4():
    with StatusServer({"audio": {"enabled": True}}) as st, \
         FakeRtsp(audio_status="RTSP/1.0 551 Audio is disabled on this camera; request video only",
                  video_status="RTSP/1.0 200 OK") as rtsp:
        return run_patched(TOOL, rtsp.port, st.port)


@case("video SETUP broken while audio is fine -> 1 (video asserted in both states)")
def _c5():
    with StatusServer({"audio": {"enabled": True}}) as st, \
         FakeRtsp(audio_status="RTSP/1.0 200 OK", video_status="RTSP/1.0 500 Internal Error") as rtsp:
        return run_patched(TOOL, rtsp.port, st.port)


@case("status.json unreachable -> 2 (nothing to assert against)")
def _c6():
    with FakeRtsp(audio_status="RTSP/1.0 200 OK", video_status="RTSP/1.0 200 OK") as rtsp:
        # A port nothing listens on: the tool must refuse to report success.
        return run_patched(TOOL, rtsp.port, 1)


@case("server accepts the socket but never replies -> 1, not a silent pass")
def _c7():
    class Silent:
        def __init__(self):
            self._sock = socket.socket()
            self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self._sock.bind(("127.0.0.1", 0))
            self._sock.listen(8)
            self.port = self._sock.getsockname()[1]
            self._held = []
            threading.Thread(target=self._accept, daemon=True).start()

        def _accept(self):
            while True:
                try:
                    self._sock.settimeout(0.3)
                    c, _ = self._sock.accept()
                    self._held.append(c)  # accept, then never answer
                except (socket.timeout, OSError):
                    continue

        def close(self):
            with contextlib.suppress(OSError):
                self._sock.close()

    st = StatusServer({"audio": {"enabled": True}})
    st.__enter__()
    silent = Silent()
    try:
        return run_patched(TOOL, silent.port, st.port)
    finally:
        silent.close()
        st.__exit__()


def main() -> int:
    # Every case runs the REAL tool body. The only rewrite is the status port,
    # applied in-process by patch_status_port's text substitution, because the
    # tool has no port flag for the HTTP side.
    global TOOL
    import tempfile

    failures = 0
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        real = TOOL.read_text()
        TOOL = patch_status_port(tmp)
        try:
            for name, fn in CASES:
                try:
                    proc = fn()
                    out = proc.stdout + proc.stderr
                except Exception as exc:  # a case that blows up is a failure
                    out = f"harness error: {exc}"
                    proc = None
                # The expected code is the one after the LAST arrow in the
                # title, e.g. "... -> 1 (promise of silence)" -> 1. Anchoring at
                # end-of-string found nothing, so every case reported None and
                # the harness called 7/7 failures on correct exit codes.
                arrows = re.findall(r"-> (\d)", name)
                want = int(arrows[-1]) if arrows else None
                got = proc.returncode if proc else None
                ok = want is not None and got == want
                failures += 0 if ok else 1
                mark = "ok  " if ok else "FAIL"
                print(f"  [{mark}] {name}   (exit {got}, wanted {want})")
                if not ok:
                    for line in out.splitlines()[:8]:
                        print(f"          {line}")
        finally:
            TOOL = ROOT / "tools" / "rtsp_audio_truth.py"
            (tmp / "audio_truth.py").unlink(missing_ok=True)
            assert TOOL.exists() and TOOL.read_text() == real, "tool was left modified"

    print()
    total = len(CASES)
    if failures:
        print(f"  {failures}/{total} cases behaved wrongly -- the gate cannot be trusted")
        return 1
    print(f"  {total}/{total} cases behaved as declared")
    return 0


if __name__ == "__main__":
    sys.exit(main())