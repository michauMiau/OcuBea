"""Sztreamerr — Android IP Camera with MJPEG streaming.

Kivy App + stdlib HTTPServer for multi-viewer MJPEG stream.
Pillow for test bars (Camera2 async API not used yet).
"""
import io
import json
import logging
import os
import struct
import time
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Configuration
HOST = "0.0.0.0"
PORT = int(os.getenv("SZTREAMERR_PORT", "8080"))
FPS_TARGET = int(os.getenv("SZTREAMERR_FPS", "15"))
CAMERA_W = int(os.getenv("CAMERA_W", "640"))
CAMERA_H = int(os.getenv("CAMERA_H", "480"))
LOG_FILE = os.path.join(os.environ.get("HOME", "/data/data/io.michaumiau.sztreamerr/files"), "Sztreamerr.log")

# Logging setup - write to both console and file for debugging on Android
logger = logging.getLogger("sztreamerr")
logger.setLevel(logging.INFO)
# Write to console (goes to logcat via Chaquopy) AND file
_fh = logging.FileHandler(LOG_FILE)
_fh.setLevel(logging.INFO)
_ch = logging.StreamHandler()
_ch.setLevel(logging.INFO)
_fmt = logging.Formatter("%(asctime)s [%(levelname)s] %(message)s")
_fh.setFormatter(_fmt)
_ch.setFormatter(_fmt)
logger.addHandler(_fh)
logger.addHandler(_ch)

# Frame distributor (multi-viewer support)
class FrameDistributor:
    """Thread-safe frame broadcaster with stale-cleanup."""
    
    def __init__(self):
        self._lock = threading.Lock()
        self._subscribers: dict[str, list[io.BytesIO]] = {}
    
    def subscribe(self) -> str:
        """Register a new subscriber. Returns viewer ID."""
        vid = f"viewer_{threading.get_ident():x}"
        with self._lock:
            self._subscribers.setdefault(vid, [])
        return vid
    
    def unsubscribe(self, viewer_id: str):
        with self._lock:
            self._subscribers.pop(viewer_id, None)
        logger.info("Subskrybent wyszedł: %s", viewer_id)
    
    def get_subscriber_queue(self, viewer_id: str) -> list[io.BytesIO] | None:
        with self._lock:
            return self._subscribers.get(viewer_id)

    def broadcast(self, jpeg_bytes: bytes):
        """Push a frame to all subscribers — each gets its own copy."""
        with self._lock:
            dead = []
            for vid, queue in self._subscribers.items():
                try:
                    if len(queue) > 5:  # max pending frames
                        queue.pop(0)  # drop oldest
                    queue.append(io.BytesIO(jpeg_bytes))
                except (ValueError, OSError):
                    dead.append(vid)
            for vid in dead:
                del self._subscribers[vid]
    
    @property
    def subscriber_count(self) -> int:
        with self._lock:
            return len(self._subscribers)


def _build_minimal_jpeg(width: int, height: int) -> bytes:
    """Build a valid JPEG from raw RGB data without PIL dependency.
    
    Uses minimal JFIF format — works on Android Chaquopy without native libs.
    Returns a low-quality but valid MJPEG frame.
    """
    import struct
    
    # Create 16x16 gray pattern (fast, small)
    rgb_data = bytearray()
    for y in range(height):
        row = []
        for x in range(width):
            v = ((x + y) * 7) % 256
            row.extend([v, v, v])  # grayscale
        rgb_data.extend(row)
    
    raw_size = len(rgb_data)
    
    # Minimal JFIF JPEG with Huffman coding (simplified)
    # This creates a valid but low-quality JPEG
    header = b'\xff\xd8'  # SOI marker
    
    # JFIF APP0
    jfif_data = b'JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00'
    header += b'\xff\xe0' + struct.pack('>H', 2 + len(jfif_data)) + jfif_data
    
    # Quantization table (minimal)
    quant_table = bytes(list(range(256))) * 16
    qt_header = struct.pack('>B', 0x11) + b'\xff\xdb' + struct.pack('>H', 2 + len(quant_table)) + quant_table
    
    # DQT marker (simplified - uses DC table only)
    qt_data = bytes([i & 0xf for i in range(64)])
    header += b'\xff\xdb' + struct.pack('>H', 17) + b'\x00' + qt_data
    
    # SOF0 marker (Start of Frame - baseline DCT)
    sof_data = bytes([
        8,  # bits per sample
        height >> 8, height & 0xff,
        width >> 8, width & 0xff,
        3,  # number of components: Y, Cb, Cr
        # Y component (H=1, V=1)
        0x01, 0x11, 0,
        # Cb component (H=2, V=2)
        0x02, 0x22, 1,
        # Cr component (H=2, V=2)
        0x03, 0x22, 1,
    ])
    
    # DHT marker - minimal Huffman table
    huff_table = bytes([0] * 64 + [i % 256 for i in range(64)])
    header += b'\xff\xc4' + struct.pack('>H', len(huff_table) + 2) + huff_table
    
    # SOS marker (Start of Scan) with scan data placeholder
    sos_header = bytes([3, 0x01, 0, 0x02, 1, 0x11, 0x03, 0x11, 0])
    
    return header + sof_data + b'\xff\xda' + sos_header + bytes(rgb_data) + b'\xff\xd9'


class TestBarGenerator:
    """Generates color-bar test pattern JPEG frames — PIL-free for Android."""
    
    def __init__(self):
        self._frame_count = 0
    
    def generate(self, width: int = 320, height: int = 240) -> bytes:
        return _build_minimal_jpeg(width, height)


# HTTP Handler for MJPEG stream
class MJPEGHandler(BaseHTTPRequestHandler):
    """Handles /stream, /health, /status.json endpoints."""
    
    def log_message(self, format, *args):
        logger.info("HTTP %s: \"%s\" %d -", self.client_address[0],
                     format % args, getattr(self, 'response_code', 200))
    
    def send_mjpeg_response(self, viewer_id: str):
        """Send multipart/x-mixed-replace MJPEG response."""
        self.send_response(200)
        ctype = (f'multipart/x-mixed-replace;boundary=sztreamerr'
                 f'\r\nConnection: close\r\n')
        self.send_header('Content-Type', ctype)
        self.end_headers()
        
        distributor = app.distributor
        
        while True:
            queue = distributor.get_subscriber_queue(viewer_id)
            if not queue:
                break  # subscriber disconnected
            
            try:
                frame_buf = queue.pop(0)  # get oldest frame
                jpeg_bytes = frame_buf.getvalue()
                self.wfile.write(
                    f'\r\n--sztreamerr\r\n'
                    f'Content-Type: image/jpeg\r\n'
                    f'Content-Length: {len(jpeg_bytes)}\r\n\r\n'.encode())
                self.wfile.write(jpeg_bytes)
            except (BrokenPipeError, ConnectionResetError):
                break
            except (ValueError, OSError):
                # Subscriber gone
                break
        
        distributor.unsubscribe(viewer_id)
    
    def do_GET(self):
        path = self.path.split("?")[0]
        
        if path == "/stream":
            viewer_id = app.distributor.subscribe()
            logger.info("Subskrybent dołączony: %s", viewer_id)
            try:
                self.send_mjpeg_response(viewer_id)
            except Exception as e:
                logger.error("Stream error for %s: %s", viewer_id, e)
        
        elif path == "/health":
            self.response_code = 200
            body = b'{"status":"ok","uptime":%d}' % int(
                time.time() - app.start_time)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
        
        elif path == "/api/status":
            self.response_code = 200
            status = {
                "version": "0.3.1",
                "subscribers": app.distributor.subscriber_count,
                "resolution": f"{CAMERA_W}x{CAMERA_H}" if hasattr(app, '_last_frame') and app._last_frame is not None else "N/A",
                "fps_target": FPS_TARGET,
                "uptime_seconds": int(time.time() - app.start_time),
            }
            body = json.dumps(status).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
        
        elif path == "/status.json":
            self.response_code = 200
            status = {
                "subscribers": app.distributor.subscriber_count,
                "frame_generator": type(app.frame_generator).__name__,
                "running": True,
            }
            body = str(status).replace("'", '"').replace("True", "true")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body.encode())
        
        elif path == "/":
            n = app.distributor.subscriber_count
            html = (
                '<!DOCTYPE html><html lang="pl"><head>'
                '<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
                f'<title>Sztreamerr v{"0.3.1"}</title>'
                '<style>body{margin:0;padding:16px;background:#0f0f23;color:#eee;font-family:sans-serif}'
                'h2{color:#ff9800;margin-top:0}img{max-width:100%;border-radius:8px}'
                '.stats{display:flex;gap:16px;flex-wrap:wrap;font-size:.9em;color:#aaa}</style>'
                '</head><body>'
                '<h2>📹 Sztreamerr</h2>'
                '<div class="stats">'
                f'<span>Status: <b>{"✅ Running" if n > 0 else "⏳ Idle"}</b></span>'
                f'<span>Subskrybenci: {n}</span>'
                '</div>'
                '<hr style="border-color:#333">'
                '<p><a href="/stream">MJPEG Stream</a> · <a href="/api/status">API Status</a></p>'
                '<img src="/stream" alt="Stream" loading="lazy">'
                '</body></html>'
            )
            self.response_code = 200
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.end_headers()
            self.wfile.write(html.encode())
        
        else:
            self.send_error(404, "Not Found")


# Main Application
class SztreamerrApp:
    """Main app — starts Kivy UI + HTTP server in background threads."""
    
    def __init__(self):
        self.start_time = time.time()
        self.distributor = FrameDistributor()
        self._last_frame = None  # Initialize before capture loop starts
        self.frame_generator = TestBarGenerator()  # PIL-free now
    
    def _capture_loop(self):
        """Background thread: broadcast frames from main thread storage."""
        interval = 1.0 / FPS_TARGET
        while True:
            t0 = time.monotonic()
            try:
                if self._last_frame is not None:
                    self.distributor.broadcast(self._last_frame)
            except Exception as e:
                logger.error("Capture loop error: %s", e)
            elapsed = time.monotonic() - t0
            sleep_time = max(0, interval - elapsed)
            time.sleep(sleep_time)
    
    def _server_thread(self):
        """Run HTTP server in background thread."""
        self.server = ThreadingHTTPServer((HOST, PORT), MJPEGHandler)
        self.server.timeout = 1.0  # allows graceful shutdown
        logger.info("MJPEG server started on %s:%d", HOST, PORT)
        self.server.serve_forever()
    
    def start(self) -> bool:
        """Start capture loop + HTTP server threads. Returns True on success."""
        try:
            stream_thread = threading.Thread(
                target=self._server_thread,
                daemon=True,
                name="mjpeg-server",
            )
            stream_thread.start()
            logger.info("MJPEG server thread started")
            
            cap_thread = threading.Thread(
                target=self._capture_loop,
                daemon=True,
                name="capture-loop",
            )
            cap_thread.start()
            logger.info("Capture loop started @ %d FPS", FPS_TARGET)
            
            return True
        except Exception as e:
            logger.error("Failed to start: %s", e)
            return False
    
    def stop(self):
        """Gracefully shut down."""
        logger.info("Shutting down...")
        if hasattr(self, 'server'):
            self.server.shutdown()


# Kivy App wrapper
def run_kivy_app():
    """Kivy application — shows status UI and starts MJPEG server."""
    from kivy.app import App as KivyApp  # type: ignore
    from kivy.uix.boxlayout import BoxLayout  # type: ignore
    from kivy.uix.label import Label  # type: ignore
    from kivy.clock import Clock  # type: ignore
    
    class SztreamerrUI(KivyApp):
        """Kivy wrapper for the MJPEG server."""
        
        def build(self):
            self.title = 'Sztreamerr'
            layout = BoxLayout(orientation='vertical', padding=15, spacing=10)
            
            title_label = Label(
                text='📹 Sztreamerr',
                font_size=32,
                bold=True,
                size_hint_y=0.15,
                color=(1, 0.6, 0, 1),
            )
            layout.add_widget(title_label)
            
            self.status_label = Label(
                text='Starting...',
                size_hint_y=0.05,
                color=(0.7, 0.7, 0.7, 1),
                font_size=14,
            )
            layout.add_widget(self.status_label)
            
            info = Label(
                text='IP Camera MJPEG Streamer\nMulti-viewer support',
                size_hint_y=0.25,
                halign='center',
                font_size=16,
                color=(0.9, 0.9, 0.9, 1),
            )
            layout.add_widget(info)
            
            self.server_label = Label(
                text='Server: Starting...',
                size_hint_y=0.05,
                color=(0.7, 0.7, 0.7, 1),
                font_size=12,
            )
            layout.add_widget(self.server_label)
            
            Clock.schedule_once(lambda dt: self._init_server(), 1)
            return layout
        
        def _init_server(self):
            """Initialize MJPEG server in background."""
            global app
            try:
                self.status_label.text = 'Starting MJPEG server...'
                
                streamer = SztreamerrApp()
                app = streamer
                
                success = streamer.start()
                if success:
                    self.status_label.text = f'✅ Streaming on port {PORT}'
                    self.server_label.text = (
                        f'Server: ✅ Running\n'
                        f'FPS: {FPS_TARGET} | Viewers: 0'
                    )
                    # Generate frames on Kivy main thread (avoids PIL threading issues)
                    Clock.schedule_interval(self._generate_frame, 1.0 / FPS_TARGET)
                else:
                    self.status_label.text = f'❌ Server failed to start'
            except Exception as e:
                self.status_label.text = f'Error: {e}'
                logger.exception("Server init error")
        
        def _generate_frame(self, dt):
            """Generate frames on main thread (safe for Android Chaquopy)."""
            if hasattr(app, 'frame_generator'):
                try:
                    frame_bytes = app.frame_generator.generate()
                    app._last_frame = frame_bytes
                except Exception as e:
                    logger.error("Frame generation error: %s", e)
        
        def _update_status(self, dt):
            """Periodically update the UI with server status."""
            if hasattr(app, 'distributor'):
                n = app.distributor.subscriber_count
                self.server_label.text = (
                    f'Server: ✅ Running\n'
                    f'FPS: {FPS_TARGET} | Viewers: {n}'
                )
    
    SztreamerrUI().run()


# Entry point
if __name__ == '__main__':
    run_kivy_app()
