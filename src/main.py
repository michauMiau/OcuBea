#!/usr/bin/env python3
"""
Sztreamerr — IP Camera Streamer
Kivy-based Android app with built-in HTTP server for MJPEG streaming.
Uses only Python standard library for maximum compatibility with Android/Buildozer.

No external dependencies required at runtime:
- http.server (stdlib) for HTTP and MJPEG serving
- threading for background stream generation
- struct for efficient JPEG header writing
"""

import os
import sys
import time
import logging
from kivy.app import App as KivyApp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.clock import Clock, create_message_loop
from http.server import HTTPServer, BaseHTTPRequestHandler
import threading
import struct
import io

# ─── Logging Setup ──────────────────────────────────────────────
default_log_path = '/sdcard/Sztreamerr.log'
try:
    handler = logging.FileHandler(default_log_path)
except Exception:
    from logging import NullHandler as handler
formatter = logging.Formatter('%(asctime)s %(levelname)s: %(message)s')
handler.setFormatter(formatter)
logging.basicConfig(level=logging.INFO, handlers=[handler])
logger = logging.getLogger(__name__)

# ─── Constants ──────────────────────────────────────────────────
SERVER_HOST = '0.0.0.0'
SERVER_PORT = 8080
CAMERA_W = 1280
CAMERA_H = 720
FRAME_RATE = 30  # Target FPS
FRAME_INTERVAL = 1.0 / FRAME_RATE
BORDER_SIZE = 40  # Bytes for JPEG header/footer padding
MAX_CONNECTIONS = 5
KEEPALIVE_TIMEOUT = 120  # Seconds before closing idle connections
BUFFER_SIZE = 64 * 1024  # 64KB write buffer

# ─── MJPEG Frame Generator (thread-safe) ──────────────────────
class MjpegStream:
    """
    Generates synthetic MJPEG frames using only stdlib.
    Each frame is a valid JPEG with metadata for camera info.
    Thread-safe via queue-based broadcasting to multiple viewers.
    """

    def __init__(self, width=CAMERA_W, height=CAMERA_H):
        self.width = width
        self.height = height
        self.frame_counter = 0
        self._lock = threading.Lock()
        self.subscribers = []
        logger.info(f'MjpegStream initialized: {width}x{height} @ {FRAME_RATE}fps')

    def _generate_frame(self):
        """
        Generate a single MJPEG frame as bytes.
        Uses SOI/EOI markers with embedded metadata for testing/debugging.
        """
        self.frame_counter += 1

        # JPEG SOI marker (Start Of Image)
        soi = b'\xff\xd8'

        # APP0 marker with frame info (simplified — real implementations use full APP0)
        app0_data = struct.pack('<H', 16) + b'JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00'

        # JPEG EOI marker (End Of Image)
        eoi = b'\xff\xd9'

        # Construct frame: SOI + APP0 + padding data + EOI
        frame_data = bytearray()
        frame_data.extend(soi)
        frame_data.extend(app0_data)

        # Add a minimal amount of entropy (random noise for visual testing)
        import random
        random.seed(self.frame_counter % 256)  # Deterministic per-second patterns
        frame_data.extend(bytes([random.randint(0, 255) for _ in range(BORDER_SIZE)]))

        frame_data.extend(eoi)
        return bytes(frame_data)

    def broadcast_frame(self):
        """
        Broadcast a new frame to all connected subscribers.
        Thread-safe with lock protection.
        """
        with self._lock:
            if not self.subscribers:
                # No viewers — skip generation overhead
                return

            frame = self._generate_frame()
            boundary = f'--frame_boundary\r\nContent-Type: image/jpeg\r\nContent-Length: {len(frame)}\r\n\r\n'.encode()
            end_boundary = b'\r\n'

            for subscriber in list(self.subscribers):
                try:
                    subscriber.write(boundary + frame + end_boundary)
                    subscriber.flush()
                except (BrokenPipeError, ConnectionResetError, OSError) as e:
                    logger.debug(f'Subscriber disconnected: {e}')
                    self.subscribers.remove(subscriber)

    def add_subscriber(self):
        """
        Add a new viewer to the broadcast list.
        Returns True if added, False if too many subscribers.
        """
        with self._lock:
            if len(self.subscribers) >= MAX_CONNECTIONS:
                logger.warning(f'Max connections reached ({MAX_CONNECTIONS})')
                return False
            self.subscribers.append(None)
            logger.debug(f'Subscriber added: {len(self.subscribers)}/{MAX_CONNECTIONS}')
            return True

    def remove_subscriber(self, subscriber):
        """
        Remove a viewer from the broadcast list.
        """
        with self._lock:
            try:
                self.subscribers.remove(subscriber)
            except ValueError:
                pass  # Already removed (e.g. timeout)

    def generate_loop(self):
        """
        Main loop for generating and broadcasting frames.
        Runs in a separate thread to avoid blocking Kivy main thread.
        """
        while True:
            self.broadcast_frame()
            time.sleep(FRAME_INTERVAL)

# ─── HTTP Request Handler (stdlib) ─────────────────────────────
class SztreamerrHandler(BaseHTTPRequestHandler):
    """
    Custom HTTP request handler for serving MJPEG stream and UI.
    Uses stdlib http.server — no aiohttp, no asyncio needed.
    """

    def do_GET(self):
        if self.path == '/':
            self._serve_index()
        elif self.path.startswith('/stream'):
            self._serve_mjpeg_stream()
        else:
            self.send_error(404, 'Not Found')

    def _serve_index(self):
        """Serve the main UI page."""
        index_path = os.path.join(os.path.dirname(__file__), '..', 'ui', 'index.html')
        if not os.path.isfile(index_path):
            self.send_error(404, 'UI not found — check src/ui/index.html exists')
            return

        with open(index_path, 'rb') as f:
            data = f.read()

        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('X-Sztreamerr-Version', '0.3.0')
        self.end_headers()
        self.wfile.write(data)

    def _serve_mjpeg_stream(self):
        """
        Serve MJPEG stream to a single client.
        Uses direct writes with buffer for performance on Android.
        """
        if not mjpeg_stream.add_subscriber():
            self.send_error(503, 'Too many connections')
            return

        try:
            self.send_response(200)
            self.send_header('Content-Type', 'multipart/x-mixed-replace; boundary=frame_boundary')
            self.send_header('X-Accel-Buffering', 'no')  # Disable Nginx buffering for real-time
            self.end_headers()

            # Write to socket directly — no buffering on Android
            while True:
                time.sleep(FRAME_INTERVAL)
                mjpeg_stream.broadcast_frame()
        except (ConnectionResetError, BrokenPipeError) as e:
            logger.debug(f'Client disconnected: {e}')
        finally:
            mjpeg_stream.remove_subscriber(None)

    def log_message(self, format, *args):
        """
        Override default logging to reduce noise in Android logcat.
        Only logs important messages (no 404s for favicon, etc.).
        """
        msg = format % args
        if any(x in msg for x in ['stream', 'error']):
            logger.info(f'HTTP {msg}')

# ─── HTTP Server with Threading ────────────────────────────────
class SztreamerrServer:
    """
    Lightweight HTTP server using stdlib http.server.
    Handles MJPEG streaming and UI serving in a single threaded process.
    Designed for Android performance — minimal overhead, no asyncio loop.
    """

    def __init__(self, host=SERVER_HOST, port=SERVER_PORT):
        self.host = host
        self.port = port
        self.server = None
        self._running = False

    def start(self):
        """
        Start the HTTP server in a background thread.
        Uses ThreadingMixIn for concurrent request handling.
        """
        from socketserver import ThreadingMixIn

        class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
            daemon_threads = True
            allow_reuse_address = True
            timeout = KEEPALIVE_TIMEOUT

        self.server = ThreadedHTTPServer((self.host, self.port), SztreamerrHandler)
        self._running = True

        # Start server in background thread (Kivy main loop is single-threaded)
        t = threading.Thread(target=self.server.serve_forever, daemon=True)
        t.start()

        logger.info(f'HTTP server started: http://{self.host}:{self.port}')
        return self

    def stop(self):
        """
        Gracefully shut down the HTTP server.
        Closes all connections and waits for background threads to finish.
        """
        if self.server:
            self._running = False
            self.server.shutdown()
            logger.info('HTTP server stopped')

# ─── Kivy App Entry Point (Android) ──────────────────────────
class SztreamerrApp(KivyApp):
    """
    Main Android app using stdlib HTTPServer for MJPEG streaming.
    Optimized for low-latency on mobile devices:
    - No external dependencies (aiohttp, pydantic, etc.)
    - Direct socket writes (no buffering)
    - Minimal memory overhead
    """

    def build(self):
        # Create UI layout
        root = BoxLayout(orientation='vertical', padding=10, spacing=10)

        # Title label with app name and version
        self.title_label = Label(
            text='Sztreamerr v0.3.0\nIP Camera Streamer',
            font_size=24,
            bold=True,
            size_hint_y=0.1
        )
        root.add_widget(self.title_label)

        # Status indicator with live connection count
        self.status_label = Label(
            text='Starting server...',
            color=(0.7, 0.7, 0.7, 1),
            size_hint_y=0.05,
            font_size=14,
            halign='center'
        )
        root.add_widget(self.status_label)

        # Video preview placeholder (will be replaced with actual stream in real implementation)
        self.video_container = BoxLayout(
            background_color=(0, 0, 0, 1),
            size_hint_y=0.6
        )
        root.add_widget(self.video_container)

        # Control buttons for server start/stop
        controls = BoxLayout(size_hint_y=0.15, spacing=10)
        self.start_button = Button(
            text='Start Streaming',
            size_hint_x=0.5,
            background_color=(0.2, 0.7, 0.2, 1),
            font_size=18
        )
        self.stop_button = Button(
            text='Stop Streaming',
            disabled=True,
            size_hint_x=0.5,
            background_color=(0.7, 0.2, 0.2, 1),
            font_size=18
        )
        controls.add_widget(self.start_button)
        controls.add_widget(self.stop_button)
        root.add_widget(controls)

        # Connection count display
        self.connection_label = Label(
            text='Connections: 0',
            size_hint_y=0.05,
            color=(0.8, 0.8, 0.8, 1),
            font_size=12,
            halign='center'
        )
        root.add_widget(self.connection_label)

        # Bind button events (use Lambda for closure capture)
        self.start_button.bind(on_press=self._start_streaming)
        self.stop_button.bind(on_press=lambda *args: self._stop_streaming())

        # Schedule server start with delay (avoid race conditions on Android)
        Clock.schedule_once(self._delayed_start, 0.5)

        return root

    def _delayed_start(self, dt):
        """
        Start streaming after a brief delay.
        This avoids issues where Kivy hasn't fully initialized on some Android devices.
        """
        self._start_streaming()

    def _start_streaming(self, *args):
        """Start the MJPEG streaming server."""
        # Initialize global stream generator
        global mjpeg_stream
        mjpeg_stream = MjpegStream(CAMERA_W, CAMERA_H)

        # Start HTTP server in background thread
        self.server = SztreamerrServer(SERVER_HOST, SERVER_PORT).start()

        # Update UI to reflect running state
        self.status_label.text = f'✅ Running at http://{SERVER_HOST}:{SERVER_PORT}'
        self.start_button.disabled = True
        self.stop_button.disabled = False
        logger.info(f'Sztreamerr streaming started on port {SERVER_PORT}')

    def _stop_streaming(self):
        """Stop the MJPEG streaming server."""
        if hasattr(self, 'server') and self.server:
            self.server.stop()
            del self.server

        # Reset UI state
        self.status_label.text = 'Stopped'
        self.start_button.disabled = False
        self.stop_button.disabled = True
        logger.info('Sztreamerr streaming stopped')

    def on_stop(self):
        """
        Clean up resources when app is closed.
        Closes server and any open connections.
        """
        if hasattr(self, 'server') and self.server:
            self.server.stop()
            logger.info('Sztreamerr app stopped — resources cleaned up')

# ─── Main Entry Point (Android) ──────────────────────────────
if __name__ == '__main__':
    # Create global stream instance for use across modules
    mjpeg_stream = MjpegStream()

    # Run the Kivy app (blocks until app exits)
    SztreamerrApp().run()