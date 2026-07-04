"""Sztreamerr — Android IP Camera with MJPEG streaming.

Kivy App + stdlib HTTPServer for multi-viewer MJPEG stream.
Pillow for test bars (Camera2 async API not used yet).
"""
import io
import logging
import os
import time
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
try:
    from PIL import Image, ImageDraw  # type: ignore
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

# Configuration
HOST = "0.0.0.0"
PORT = int(os.getenv("SZTREAMERR_PORT", "8080"))
FPS_TARGET = int(os.getenv("SZTREAMERR_FPS", "15"))
LOG_FILE = "/sdcard/Sztreamerr.log"

# Logging setup
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("sztreamerr")

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

# Frame generator (test bars when no camera)
class TestBarGenerator:
    """Generates color-bar test pattern JPEG frames."""
    
    def __init__(self):
        self._frame_count = 0
    
    def generate(self, width: int = 320, height: int = 240) -> bytes:
        if not HAS_PIL:
            return self._empty_frame()
        
        img = Image.new("RGB", (width, height))
        draw = ImageDraw.Draw(img)
        bar_w = width // 7
        
        colors = [(255, 255, 255), (255, 255, 0), (0, 255, 255),
                  (0, 255, 0), (255, 0, 255), (255, 0, 0), (0, 0, 255)]
        for i, color in enumerate(colors):
            draw.rectangle([i * bar_w, 0, (i + 1) * bar_w - 1, height // 3], fill=color)
        
        # Bottom: frame counter + timestamp
        self._frame_count += 1
        ts = time.strftime("%H:%M:%S")
        draw.rectangle([0, height * 2 // 3, width, height], fill=(40, 40, 60))
        try:
            draw.text((10, height - 25), f"#{self._frame_count} {ts}", fill=(200, 200, 200))
        except Exception:
            pass
        
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=80)
        return buf.getvalue()
    
    def _empty_frame(self) -> bytes:
        """Fallback: minimal valid JPEG."""
        # Minimal 1x1 white pixel JPEG
        return (
            b'\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01'
            b'\x00\x01\x00\x00\xff\xdb\x00C\x00\x08\x06\x06\x07\x06'
            b'\x05\x08\x07\x07\x07\t\t\x08\n\x0c\x14\r\x0c\x0b\x0b'
            b'\x0c\x19\x12\x13\x0f\x14\x1d\x1a\x1f\x1e\x1d\x1a\x1c'
            b'\x1c $.\' ",#\x1c\x1c(7),01444\x1f\'9=82<.342\xff\xc0\x00'
            b'\x0b\x08\x00\x01\x00\x01\x01\x01\x11\x00\xff\xc4\x00\x1f'
            b'\x00\x00\x01\x05\x01\x01\x01\x01\x01\x01\x00\x00\x00'
            b'\x00\x00\x00\x00\x00\x01\x02\x03\x04\x05\x06\x07\x08'
            b'\t\n\x0b\xff\xc4\x00\xb5\x10\x00\x02\x01\x03\x03\x02'
            b'\x04\x03\x05\x05\x04\x04\x00\x00\x01}\x01\x02\x03\x00'
            b'\x04\x11\x05\x12!1A\x06\x13Qa\x07"q\x142\x81\x91\xa1\x08'
            b'#B\xb2\xc2\r\x0f\x15\x16\x17\x18\x19\x1a\x82\x83\x84\x85'
            b'\x86\x87\x88\x89\x8a\x92\x93\x94\x95\x96\x97\x98\x99\x9a'
            b'\xa2\xa3\xa4\xa5\xa6\xa7\xa8\xa9\xaa\xb3\xb4\xb5\xb6\xb7'
            b'\xb8\xb9\xba\xc3\xc4\xc5\xc6\xc7\xc8\xc9\xca\xd3\xd4\xd5'
            b'\xd6\xd7\xd8\xd9\xda\xe1\xe2\xe3\xe4\xe5\xe6\xe7\xe8\xe9'
            b'\xea\xf1\xf2\xf3\xf4\xf5\xf6\xf7\xf8\xf9\xfa\xff\xda\x00'
            b'\x08\x01\x01\x00\x00?\x00\xfb\xa2\xc3\xff\xd9'
        )

# HTTP Handler for MJPEG stream
class MJPEGHandler(BaseHTTPRequestHandler):
    """Handles /stream, /health, /status.json endpoints."""
    
    # Suppress default stderr logging for clean console
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
        frame_gen = app.frame_generator
        
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
            # Simple HTML info page
            html = (
                '<html><head><title>Sztreamerr</title></head>'
                '<body style="font-family:monospace;background:#1a1a2e;color:#eee;padding:2em">'
                '<h2>📹 Sztreamerr v0.4.0</h2>'
                f'<p>Status: <b>{"✅ Running" if app.distributor.subscriber_count > 0 else "⏳ Idle"}</b></p>'
                f'<p>Subscribers: {app.distributor.subscriber_count}</p>'
                '<hr />'
                '<p><a href="/stream">MJPEG Stream</a></p>'
                '<p><a href="/status.json">JSON Status</a></p>'
                '<p><a href="/health">Health Check</a></p>'
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
        self.frame_generator = TestBarGenerator() if HAS_PIL else None
    
    def _capture_loop(self):
        """Background thread: capture frames and broadcast."""
        interval = 1.0 / FPS_TARGET
        while True:
            t0 = time.monotonic()
            try:
                frame_bytes = self.frame_generator.generate()
                if frame_bytes:
                    self.distributor.broadcast(frame_bytes)
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
            # Start MJPEG streamer
            stream_thread = threading.Thread(
                target=self._server_thread,
                daemon=True,
                name="mjpeg-server",
            )
            stream_thread.start()
            logger.info("MJPEG server thread started")
            
            # Start capture loop
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
            
            # Title
            title_label = Label(
                text='📹 Sztreamerr',
                font_size=32,
                bold=True,
                size_hint_y=0.15,
                color=(1, 0.6, 0, 1),
            )
            layout.add_widget(title_label)
            
            # Status
            self.status_label = Label(
                text='Starting...',
                size_hint_y=0.05,
                color=(0.7, 0.7, 0.7, 1),
                font_size=14,
            )
            layout.add_widget(self.status_label)
            
            # Info
            info = Label(
                text='IP Camera MJPEG Streamer\nMulti-viewer support',
                size_hint_y=0.25,
                halign='center',
                font_size=16,
                color=(0.9, 0.9, 0.9, 1),
            )
            layout.add_widget(info)
            
            # Server status (live)
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
                
                # Start the streamer
                streamer = SztreamerrApp()
                app = streamer  # Make it globally accessible for handler
                
                success = streamer.start()
                if success:
                    self.status_label.text = f'✅ Streaming on port {PORT}'
                    self.server_label.text = (
                        f'Server: ✅ Running\n'
                        f'FPS: {FPS_TARGET} | Viewers: 0'
                    )
                    # Update viewer count periodically
                    Clock.schedule_interval(self._update_status, 2)
                else:
                    self.status_label.text = f'❌ Server failed to start'
            except Exception as e:
                self.status_label.text = f'Error: {e}'
                logger.exception("Server init error")
        
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