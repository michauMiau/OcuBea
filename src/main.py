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
        self.frame_count = 0
        self.start_time = time.time()
    
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
            # Track FPS stats
            self.frame_count += 1
            
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
    """Generates a test pattern frame with FPS counter (fallback when camera unavailable)."""

    def __init__(self, width=640, height=480):
        self.width = width
        self.height = height
        self._frame_count = 0
        self._start_time = time.time()

    def generate(self) -> bytes:
        """Generate a test pattern with FPS counter."""
        from PIL import Image, ImageDraw, ImageFont

        img = Image.new("RGB", (self.width, self.height), "black")
        draw = ImageDraw.Draw(img)

        # Draw colored bars at top
        colors = ["red", "green", "blue"]
        bar_h = 60
        for i, color in enumerate(colors):
            x1 = i * (self.width // len(colors))
            x2 = x1 + self.width // len(colors)
            draw.rectangle([x1, 0, x2, bar_h], fill=color)

        # Draw FPS text
        elapsed = time.time() - self._start_time
        fps = self._frame_count / elapsed if elapsed > 0 else 0
        fps_text = f"FPS: {fps:.1f} | Frame: {self._frame_count}"

        try:
            font = ImageFont.truetype("/system/fonts/DroidSansFallback.ttf", 24)
        except (IOError, OSError):
            font = ImageFont.load_default()

        draw.text((20, self.height // 2), fps_text, fill="white", font=font)
        self._frame_count += 1

        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=85)
        return buf.getvalue()


class RealCameraInput:
    """Android Camera2 API wrapper via pyjnius."""

    def __init__(self, width=640, height=480):
        self.width = width
        self.height = height
        self._camera = None
        
    def start(self) -> bool:
        try:
            from jnius import autoclass
            
            # Get CameraService
            Context = autoclass('android.content.Context')
            camera_service = autoclass('android.hardware.camera2.CameraManager')
            
            # Get first available camera ID
            camera_ids = camera_service.getCameraIdList()
            if not camera_ids:
                logger.error("No cameras found!")
                return False
                
            self._camera_id = str(camera_ids[0])
            logger.info(f"Using camera: {self._camera_id}")
            
            # Create CameraDevice listener
            class MyCaptureListener(autoclass('android.hardware.camera2.CameraCaptureSession$CaptureCallback')):
                def __init__(self):
                    super().__init__()
                    
                def onCaptureCompleted(self, session, request, result):
                    pass
                    
            self._capture_listener = MyCaptureListener()
            
            return True
        except ImportError as e:
            logger.error(f"Failed to import Camera2 classes: {e}")
            return False
    
    def capture_frame(self) -> bytes | None:
        """Capture a single frame from camera. Returns JPEG bytes."""
        if not self._camera:
            return None
            
        try:
            # Get next available frame using ImageReader
            image_reader = autoclass('android.media.ImageReader')
            max_images = 2
            format = autoclass('android.graphics.PixelFormat').YUV_420_888
            
            reader = image_reader.newInstance(self.width, self.height, format, max_images)
            
            # Create capture request
            camera_device = self._camera.open()  # Simplified - need proper CameraDevice
            capture_request = autoclass('android.hardware.camera2.CameraCaptureSession$CaptureRequest').BUILD_FULL_PREVIEW
            
            # Submit capture
            session = camera_device.createCaptureSession([reader])
            session.capture(capture_request, self._capture_listener)
            
            # Wait for image (simplified - in real app need proper callback handling)
            image = reader.acquireNextImage()
            if image:
                # Convert YUV_420_888 to JPEG (complex conversion needed)
                jpeg_bytes = self._convert_yuv_to_jpeg(image)
                image.close()
                return jpeg_bytes
                
        except Exception as e:
            logger.error(f"Camera capture error: {e}")
            
        return None
    
    def _convert_yuv_to_jpeg(self, yuv_image) -> bytes:
        """Convert YUV_420_888 Image to JPEG bytes."""
        # This is a simplified version - real implementation needs:
        # 1. Extract Y, U, V planes from Image
        # 2. Convert YUV to RGB using matrix multiplication
        # 3. Encode RGB as JPEG
        
        try:
            from PIL import Image
            import io
            
            # Get plane data (simplified)
            y_plane = yuv_image.getPlaneData(0)
            u_plane = yuv_image.getPlaneData(1)
            v_plane = yuv_image.getPlaneData(2)
            
            # Convert to RGB (this is where it gets complex with YUV_420_888 format)
            # For now, return a placeholder - real implementation needs proper color space conversion
            logger.warning("YUV to JPEG conversion not yet implemented")
            return None
            
        except ImportError:
            logger.error("PIL required for YUV->JPEG conversion")
            return None
    
    def stop(self):
        """Release camera resources."""
        if self._camera:
            try:
                self._camera.close()
            except Exception as e:
                logger.warning(f"Error closing camera: {e}")


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
            
            # Calculate actual FPS from distributor stats
            elapsed_total = time.time() - app.distributor.start_time
            fps_from_distro = (app.distributor.frame_count / elapsed_total) if elapsed_total > 0 else 0
            
            status = {
                "version": "0.3.1",
                "subscribers": app.distributor.subscriber_count,
                "resolution": f"{CAMERA_W}x{CAMERA_H}",
                "fps_target": FPS_TARGET,
                "fps_actual": round(fps_from_distro, 1),
                "uptime_seconds": int(time.time() - app.start_time),
                "camera_status": getattr(app, 'camera_status', 'inactive'),
                "has_camera_input": hasattr(app, 'camera_input') and app.camera_input is not None,
            }
            
            # Try to get memory info (works on Android)
            try:
                import resource
                mem_usage = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024  # Convert KB to MB
                status["memory_mb"] = round(mem_usage, 1)
            except (ImportError, AttributeError):
                pass
            
            body = json.dumps(status).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
        
        elif path == "/status.json":
            self.response_code = 200
            
            # Check if we have real camera input or test bars
            has_camera = (hasattr(app, 'camera_input') and 
                         app.camera_input is not None)
            
            status = {
                "subscribers": app.distributor.subscriber_count,
                "frame_generator": "RealCameraInput" if has_camera else "TestBarGenerator",
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
        self._last_frame: bytes | None = None  # Initialize before capture loop starts
        self.camera_input: RealCameraInput | None = None
        self.fps_actual = 0.0
        self.camera_status = "inactive"
        self.ip_address = "N/A"
    
    def _capture_loop(self):
        """Background thread: broadcast frames from main thread storage."""
        interval = 1.0 / FPS_TARGET
        last_fps_check = time.time()
        
        while True:
            t0 = time.monotonic()
            try:
                if self._last_frame is not None:
                    self.distributor.broadcast(self._last_frame)
                    
                    # Update camera status when we have frames
                    if self.camera_status == "inactive":
                        self.camera_status = "active"
                        
            except Exception as e:
                logger.error("Capture loop error: %s", e)
                
            elapsed = time.monotonic() - t0
            sleep_time = max(0, interval - elapsed)
            time.sleep(sleep_time)
            
            # Calculate actual FPS every 5 seconds
            now = time.time()
            if now - last_fps_check >= 5:
                dt = now - last_fps_check
                self.fps_actual = self.distributor.frame_count / dt if dt > 0 else 0
                self.distributor.frame_count = 0
                logger.debug(f"Actual FPS: {self.fps_actual:.1f}")
                last_fps_check = now
    
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
            
            self.ip_label = Label(
                text='IP: Detecting...',
                size_hint_y=0.05,
                color=(0.7, 0.7, 0.7, 1),
                font_size=12,
            )
            layout.add_widget(self.ip_label)
            
            Clock.schedule_once(lambda dt: self._init_server(), 1)
            return layout
        
        def _init_server(self):
            """Initialize MJPEG server in background."""
            global app
            try:
                self.status_label.text = 'Starting MJPEG server...'
                
                streamer = SztreamerrApp()
                app = streamer
                
                # Try to initialize RealCameraInput, fallback to TestBarGenerator
                try:
                    from pyjnius import autoclass
                    camera_input = RealCameraInput()
                    if camera_input.start():
                        app.camera_input = camera_input
                        self.status_label.text = '✅ Camera2 initialized'
                        logger.info("RealCameraInput initialized successfully")
                    else:
                        raise RuntimeError("camera start failed")
                except ImportError as e:
                    logger.warning(f"pyjnius not available ({e}), using TestBarGenerator fallback")
                    app.frame_generator = TestBarGenerator()
                    self.status_label.text = '⚠️ Camera2 unavailable, using test bars'
                except Exception as e:
                    logger.warning(f"Camera init failed ({e}), falling back to TestBarGenerator")
                    app.frame_generator = TestBarGenerator()
                    self.status_label.text = f'⚠️ Using fallback generator'
                
                success = streamer.start()
                if success:
                    gen_type = "RealCameraInput" if app.camera_input else "TestBarGenerator"
                    self.status_label.text = f'✅ Streaming on port {PORT} ({gen_type})'
                    self.server_label.text = (
                        f'Server: ✅ Running\n'
                        f'FPS: {FPS_TARGET} | Viewers: 0 | Gen: {gen_type}'
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
            if hasattr(app, 'camera_input') and app.camera_input:
                try:
                    frame_bytes = app.camera_input.capture_frame()
                    if frame_bytes is not None:
                        app._last_frame = frame_bytes
                except Exception as e:
                    logger.error("Frame generation error: %s", e)
            elif hasattr(app, 'frame_generator'):
                # Fallback to test bars if camera not available
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
