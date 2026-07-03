#!/usr/bin/env python3
#!/usr/bin/env python3
"""
Sztreamerr - IP Camera Streaming Server
Simple stdlib-based MJPEG streaming for Android (no external deps)
"""
from __future__ import annotations
import io
import logging
import os
import signal
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from queue import Queue, Empty

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
def setup_logging():
    """Log do /sdcard/Sztreamerr.log na Androidzie, stdout w przeciwnym razie."""
    log_dir = "/sdcard/Sztreamerr"
    if os.path.isdir(log_dir):
        log_path = f"{log_dir}/Sztreamerr.log"
    else:
        log_path = None

    fmt = logging.Formatter("%(asctime)s [%(levelname)s] %(message)s")

    handlers = [logging.StreamHandler(sys.stdout)]
    if log_path:
        h = logging.FileHandler(log_path, mode="a")
        handlers.append(h)

    logger = logging.getLogger("Sztreamerr")
    logger.setLevel(logging.INFO)
    for h in handlers:
        h.setFormatter(fmt)
        logger.addHandler(h)

    return logger

logger = setup_logging()

# ---------------------------------------------------------------------------
# Frame generator — testowe color bars (fallback) + opcjonalna kamera
# ---------------------------------------------------------------------------
class FrameGenerator:
    """Generuje ramki MJPEG. Na Androidzie z Camera2 API będzie zastąpiony."""

    def __init__(self, width=640, height=480):
        self.width = width
        self.height = height
        self.fps_target = 15
        self.frame_interval = 1.0 / self.fps_target
        self._frame_count = 0
        self._last_time = time.monotonic()

    def generate_frame(self):
        """Generuje testową ramkę z kolorowymi paskami + numer klatki."""
        try:
            import numpy as np
        except ImportError:
            # Brak numpy — generujemy prosty RGB przez Pillow
            return self._generate_pil_frame()

        frame = np.zeros((self.height, self.width, 3), dtype=np.uint8)

        # Kolorowe pasy (bar pattern)
        bar_width = self.width // 7
        colors = [
            (255, 0, 0),    # czerwony
            (255, 165, 0),  # pomarańczowy
            (255, 255, 0),  # żółty
            (0, 255, 0),    # zielony
            (0, 255, 255),  # cyjan
            (0, 100, 255),  # niebieski
            (128, 0, 255),  # fioletowy
        ]
        for i, color in enumerate(colors):
            x_start = i * bar_width
            x_end = min((i + 1) * bar_width, self.width)
            frame[:, x_start:x_end] = color

        # Numer klatki
        try:
            from PIL import Image, ImageDraw, ImageFont
            pil_frame = Image.fromarray(frame)
            draw = ImageDraw.Draw(pil_frame)
            font = None  # systemowy
            text = f"Frame #{self._frame_count}"
            bbox = draw.textbbox((0, 0), text, font=font)
            tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
            draw.text(
                (10, self.height - th - 15),
                text,
                fill=(255, 255, 255),
                font=font,
            )
            pil_frame = np.array(pil_frame)
        except ImportError:
            pass

        self._frame_count += 1
        return pil_frame if 'pil_frame' in dir() else frame

    def _generate_pil_frame(self):
        """Generuje ramkę tylko z Pillow."""
        try:
            from PIL import Image, ImageDraw
        except ImportError:
            # Brak Pillow — zwracamy pustą ramkę 1x1 (JPEG)
            return self._empty_jpeg()

        frame = Image.new("RGB", (self.width, self.height), "black")
        draw = ImageDraw.Draw(frame)

        # Kolorowe pasy
        bar_width = self.width // 7
        colors = [
            (255, 0, 0), (255, 165, 0), (255, 255, 0),
            (0, 255, 0), (0, 255, 255), (0, 100, 255), (128, 0, 255)
        ]
        for i, color in enumerate(colors):
            x_start = i * bar_width
            x_end = min((i + 1) * bar_width, self.width)
            draw.rectangle([x_start, 0, x_end - 1, self.height - 30], fill=color)

        # Numer klatki
        text = f"Frame #{self._frame_count}"
        bbox = draw.textbbox((0, 0), text)
        tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
        draw.text((10, self.height - th - 15), text, fill=(255, 255, 255))

        self._frame_count += 1

        buf = io.BytesIO()
        frame.save(buf, format="JPEG", quality=85)
        return buf.getvalue()

    def _empty_jpeg(self):
        """Generuje minimalny poprawny JPEG 1x1 przez Pillow."""
        try:
            from PIL import Image
            img = Image.new("RGB", (1, 1), (0, 0, 0))
            buf = io.BytesIO()
            img.save(buf, format="JPEG")
            return buf.getvalue()
        except ImportError:
            # Brak Pillow — zwracamy minimalny poprawny JPEG 1x1 (black)
            return (
                b'\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00'
                b'\x00\x01\x00\x01\x00\x00\xff\xdb\x00C\x00\x08\x06\x06'
                b'\x07\x06\x05\x08\x07\x07\x07\t\t\x08\n\x0c\x14\r\x0c\x0b\x0b'
                b'\x0c\x19\x12\x13\x0f\x14\x1d\x1a\x1f\x1e\x1d\x1a\x1c\x1c \x1f'
                b'\xff\xc0\x00\x0b\x08\x00\x01\x00\x01\x01\x01\x11\x00\xff'
                b'\xc4\x00\x1f\x00\x00\x01\x05\x01\x01\x01\x01\x01\x01\x00\x00\x00'
                b'\x00\x00\x00\x00\x01\x02\x03\x04\x05\x06\x07\x08\t\n\x0b\xff\xc4'
                b'\x00\xb5\x10\x00\x02\x01\x03\x03\x02\x04\x03\x05\x05\x04\x04\x00'
                b'\x00\x01}\x01\x02\x03\x00\x04\x11\x05\x12!\x1a\x1b\x1c\x1d\x1e\xff'
                b'\xda\x00\x0c\x03\x01\x00\x02\x11\x03\x11\x00?\x00\xfb\xa6\xba\xd5\xff\xc9\xff\xd9'
            )

    def get_frame(self):
        """Zwraca jedną ramkę JPEG."""
        frame = self.generate_frame()
        if isinstance(frame, bytes):  # już JPEG z Pillow
            return frame

        # Konwersja numpy do JPEG przez Pillow
        try:
            from PIL import Image
            pil_frame = Image.fromarray(frame)
            buf = io.BytesIO()
            pil_frame.save(buf, format="JPEG", quality=85)
            return buf.getvalue()
        except ImportError:
            # Brak numpy i Pillow — zwracamy minimalny JPEG
            return self._empty_jpeg()


class AndroidFrameGenerator(FrameGenerator):
    """
    FrameGenerator z obsługą Android Camera2 API.
    Na desktopie fallbackuje do testowych ramek (jak rodzic).
    """

    def __init__(self, width=640, height=480):
        super().__init__(width, height)
        self.camera = None
        try:
            self._try_init_camera()
        except Exception as e:
            logger.warning(f"Camera2 nie dostępny (fallback): {e}")

    def _try_init_camera(self):
        """Próbuje zainicjować kamerę Android."""
        # Próba importu pyjnius — dostępne tylko na Androidzie
        try:
            from jnius import autoclass, cast
            Camera2 = autoclass('org.kivy.android.camera.Camera2')
            logger.info("Camera2 API dostępny")
        except (ImportError, Exception) as e:
            # Desktop — fallback do rodzica
            logger.debug(f"Nie na Androidzie: {e}")
            raise

    def generate_frame(self):
        """Generuje ramkę z kamery lub fallback."""
        if self.camera and hasattr(self.camera, 'capture_frame'):
            try:
                return self.camera.capture_frame()
            except Exception as e:
                logger.error(f"Błąd capture: {e}")
        return super().generate_frame()

    def start_capture(self):
        """Startuje capture z kamery."""
        try:
            if self.camera:
                self.camera.start()
                logger.info("Capture started from camera")
        except Exception as e:
            logger.warning(f"Nie udało się wystartować kamery: {e}")

    def stop_capture(self):
        """Zatrzymuje capture."""
        try:
            if self.camera and hasattr(self.camera, 'stop'):
                self.camera.stop()
                logger.info("Capture stopped")
        except Exception as e:
            logger.warning(f"Nie udało się zatrzymać kamery: {e}")


# ---------------------------------------------------------------------------
# Frame Distributor — multi-viewer support via threading.Queue
# ---------------------------------------------------------------------------
class FrameDistributor:
    """
    Rozdziela ramki między wielu subskrybentów (multi-viewer).
    Używa threading.Queue zamiast asyncio.Queue (dostępne w stdlib).
    """

    def __init__(self):
        self._queue: Queue = None  # typ: Optional[Queue[bytes]]
        self._lock = threading.Lock()
        self._subscribers: dict[str, Queue] = {}
        self._last_frame = None
        self._last_time = 0.0

    def publish(self, frame_bytes):
        """
Pobiera ramkę z generatora i rozsyła do subskrybentów."""
        with self._lock:
            self._last_frame = frame_bytes
            self._last_time = time.monotonic()

            for sub_id, queue in list(self._subscribers.items()):
                try:
                    # Usuń przestarzałe ramki (max 10 w kolejce)
                    if queue.qsize() >= 10:
                        try:
                            queue.get_nowait()
                        except Empty:
                            pass
                    queue.put(frame_bytes, block=False)
                except Exception as e:
                    logger.debug(f"Subskrybent {sub_id} nie mógł pobrać ramki: {e}")

    def subscribe(self) -> str:
        """
        Zwraca unikalne ID subskrybenta i jego kolejkę.
        Subskryptanci pobierają ramki przez queue.get(timeout=1.0).
        """
        import uuid
        sub_id = f"viewer_{uuid.uuid4().hex[:8]}"
        queue: Queue[bytes] = Queue(maxsize=15)
        with self._lock:
            self._subscribers[sub_id] = queue
        logger.info(f"Subskrybent dołączony: {sub_id}")
        return sub_id, queue

    def unsubscribe(self, sub_id):
        """Usuwaja subskrybenta."""
        with self._lock:
            if sub_id in self._subscribers:
                del self._subscribers[sub_id]
                logger.info(f"Subskrybent wyszedł: {sub_id}")

    def get_stats(self) -> dict:
        """Zwraca statystyki dystrybucji."""
        with self._lock:
            return {
                "subscribers": len(self._subscribers),
                "last_frame_time_ago": time.monotonic() - self._last_time if self._last_time else 0,
                "subscriber_ids": list(self._subscribers.keys()),
            }

    def cleanup_stale_subscribers(self, max_idle=15.0):
        """
        Usuwa subskrybentów którzy nie pobierali ramek od >max_idle sekund.
        Wywoływane co kilka sekund przez wątek czyszczenia.
        """
        now = time.monotonic()
        with self._lock:
            stale = []
            for sub_id, queue in list(self._subscribers.items()):
                # Sprawdzamy czy kolejka jest pusta i dawno nie była używana
                if queue.qsize() == 0 and (now - self._last_time) > max_idle:
                    stale.append(sub_id)

            for sub_id in stale:
                del self._subscribers[sub_id]
                logger.debug(f"Subskrybent usunięty (idle): {sub_id}")


# ---------------------------------------------------------------------------
# HTTP Server — MJPEG streaming endpoint
# ---------------------------------------------------------------------------
class MjpegRequestHandler(BaseHTTPRequestHandler):
    """Obsługuje żądania streamu MJPEG z FrameDistributor."""

    # Statyczne pola klasy — dostęp do globalnego stanu
    distributor: FrameDistributor = None  # type: ignore
    generator: FrameGenerator = None      # type: ignore
    fps_target: int = 15
    _last_request_time: float = 0.0

    def log_message(self, format, *args):
        """Zamiast stdout — logujemy do loggera."""
        logger.info(f"HTTP {self.client_address[0]}: {format % args}")

    def _send_jpeg_header(self):
        """Wysyła nagłówki HTTP dla streamu MJPEG."""
        self.send_response(200)
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
        self.send_header("Connection", "close")  # Zamykamy po każdym żądaniu
        self.end_headers()

    def do_GET(self):
        if not self.do_GET_stream():
            return

        sub_id = None
        try:
            # Parse URL: /stream?fps=15&sub=myid
            from urllib.parse import urlparse, parse_qs
            parsed = urlparse(self.path)
            params = parse_qs(parsed.query)
            fps_str = params.get("fps", [str(MjpegRequestHandler.fps_target)])[0]
            try:
                fps = int(fps_str)
                MjpegRequestHandler.fps_target = fps
            except ValueError:
                fps = MjpegRequestHandler.fps_target

            # Subskrybent — jeśli podany, używamy istniejącej kolejki
            sub_param = params.get("sub", [None])[0]
            if sub_param and sub_param in MjpegRequestHandler.distributor._subscribers:
                queue = MjpegRequestHandler.distributor._subscribers[sub_param]
            else:
                # Nowy subskryptent
                sub_id, queue = MjpegRequestHandler.distributor.subscribe()

            self._send_jpeg_header()

            while True:
                try:
                    frame_bytes = queue.get(timeout=1.0 / fps)
                    header = f"\r\n--frame\r\nContent-Type: image/jpeg\r\n"
                    content_len = len(frame_bytes)

                    self.wfile.write(header.encode())
                    self.wfile.write(f"Content-Length: {content_len}\r\n\r\n".encode())
                    self.wfile.write(frame_bytes)
                    MjpegRequestHandler._last_request_time = time.monotonic()

                except Empty:
                    # Brak ramek — spróbujmy wygenerować nową
                    if isinstance(MjpegRequestHandler.generator, FrameGenerator):
                        frame_bytes = MjpegRequestHandler.generator.get_frame()
                        if frame_bytes and len(frame_bytes) > 50:  # valid JPEG
                            header = f"\r\n--frame\r\nContent-Type: image/jpeg\r\n"
                            content_len = len(frame_bytes)
                            self.wfile.write(header.encode())
                            self.wfile.write(f"Content-Length: {content_len}\r\n\r\n".encode())
                            self.wfile.write(frame_bytes)
                    else:
                        time.sleep(1.0 / max(fps, 1))

                except (BrokenPipeError, ConnectionResetError):
                    break

        finally:
            if sub_id:
                MjpegRequestHandler.distributor.unsubscribe(sub_id)

    def do_GET_health(self):
        """Health check endpoint."""
        stats = MjpegRequestHandler.distributor.get_stats()
        response = {
            "status": "ok",
            "subscribers": stats["subscribers"],
            "fps_target": MjpegRequestHandler.fps_target,
            "generator_type": type(MjpegRequestHandler.generator).__name__,
        }
        body = str(response).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", len(body))
        self.end_headers()
        self.wfile.write(body)

    def do_GET_status(self):
        """Status endpoint."""
        stats = MjpegRequestHandler.distributor.get_stats()
        generator = MjpegRequestHandler.generator
        response = {
            "status": "streaming",
            "subscribers": stats["subscribers"],
            "fps_target": MjpegRequestHandler.fps_target,
            "last_frame_ago_sec": round(stats.get("last_frame_time_ago", 0), 2),
            "generator_type": type(generator).__name__,
            "total_frames_generated": getattr(generator, '_frame_count', 0),
        }
        body = str(response).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", len(body))
        self.end_headers()
        self.wfile.write(body)

    def do_GET_info(self):
        """Info endpoint."""
        response = {
            "app": "Sztreamerr",
            "version": "0.3.0",
            "python_version": sys.version,
            "platform": sys.platform,
            "endpoints": [
                "/stream — MJPEG stream (default /stream)",
                "/stream?fps=15 — MJPEG stream z custom FPS",
                "/health — JSON health check",
                "/status — Detailed status",
                "/info — App info",
            ],
        }
        body = str(response).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", len(body))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        """Routing — dispatch po endpointach."""
        from urllib.parse import urlparse, parse_qs
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")

        if path == "/health":
            return self.do_GET_health()
        elif path == "/status":
            return self.do_GET_status()
        elif path == "/info":
            return self.do_GET_info()
        else:
            # Default: stream
            self._last_request_time = time.monotonic()
            return self.do_GET_stream()

    def do_GET_stream(self):
        """Streamuj MJPEG (używany przez do_GET)."""
        sub_id = None
        try:
            from urllib.parse import urlparse, parse_qs
            parsed = urlparse(self.path)
            params = parse_qs(parsed.query)
            fps_str = params.get("fps", [str(MjpegRequestHandler.fps_target)])[0]
            try:
                fps = int(fps_str)
                MjpegRequestHandler.fps_target = fps
            except ValueError:
                fps = MjpegRequestHandler.fps_target

            sub_param = params.get("sub", [None])[0]
            if sub_param and sub_param in MjpegRequestHandler.distributor._subscribers:
                queue = MjpegRequestHandler.distributor._subscribers[sub_param]
            else:
                sub_id, queue = MjpegRequestHandler.distributor.subscribe()

            self._send_jpeg_header()

            while True:
                try:
                    frame_bytes = queue.get(timeout=1.0 / fps)
                    header = f"\r\n--frame\r\nContent-Type: image/jpeg\r\n"
                    content_len = len(frame_bytes)

                    self.wfile.write(header.encode())
                    self.wfile.write(f"Content-Length: {content_len}\r\n\r\n".encode())
                    self.wfile.write(frame_bytes)
                    MjpegRequestHandler._last_request_time = time.monotonic()

                except Empty:
                    # Brak ramek — wygeneruj nową z generatora
                    if isinstance(MjpegRequestHandler.generator, FrameGenerator):
                        frame_bytes = MjpegRequestHandler.generator.get_frame()
                        if frame_bytes and len(frame_bytes) > 50:
                            header = f"\r\n--frame\r\nContent-Type: image/jpeg\r\n"
                            content_len = len(frame_bytes)
                            self.wfile.write(header.encode())
                            self.wfile.write(f"Content-Length: {content_len}\r\n\r\n".encode())
                            self.wfile.write(frame_bytes)
                    else:
                        time.sleep(1.0 / max(fps, 1))

                except (BrokenPipeError, ConnectionResetError):
                    break

        finally:
            if sub_id:
                MjpegRequestHandler.distributor.unsubscribe(sub_id)


# ---------------------------------------------------------------------------
# Capture Loop — osobny wątek generujący ramki
# ---------------------------------------------------------------------------
class CaptureLoop:
    """
    Wątek który stale generuje ramki i publish-uje je do FrameDistributor.
    Obsługuje też czyszczenie starych subskrybentów.
    """

    def __init__(self, generator: FrameGenerator, distributor: FrameDistributor):
        self.generator = generator
        self.distributor = distributor
        self._thread: threading.Thread | None = None
        self._stop_event = threading.Event()
        self._cleanup_thread: threading.Thread | None = None
        self._stop_cleanup = threading.Event()

    def start(self):
        """Startuje capture loop i cleanup thread."""
        if hasattr(self.generator, 'start_capture'):
            try:
                self.generator.start_capture()
            except Exception as e:
                logger.warning(f"Nie udało się wystartować kamery: {e}")

        self._thread = threading.Thread(
            target=self._capture_loop,
            name="Sztreamerr-Capture",
            daemon=True,
        )
        self._cleanup_thread = threading.Thread(
            target=self._cleanup_loop,
            name="Sztreamerr-Cleanup",
            daemon=True,
        )

        self._thread.start()
        self._cleanup_thread.start()
        logger.info("Capture loop started")

    def _capture_loop(self):
        """
        Główna pętla capture — generuje ramki z generatora i publish-uje je.
        Utrzymuje stałe FPS (15) niezależnie od obciążenia HTTP.
        """
        target_interval = 1.0 / MjpegRequestHandler.fps_target

        while not self._stop_event.is_set():
            try:
                frame_bytes = self.generator.get_frame()
                if frame_bytes and len(frame_bytes) > 50:  # valid JPEG
                    self.distributor.publish(frame_bytes)

                # Precyzyjny timing — obniżamy CPU usage
                elapsed = time.monotonic() - (self._last_time if hasattr(self, '_last_time') else time.monotonic())
                sleep_time = target_interval - elapsed
                if sleep_time > 0:
                    time.sleep(sleep_time)

            except Exception as e:
                logger.error(f"Błąd capture: {e}", exc_info=True)
                self._stop_event.wait(timeout=1.0)  # czekaj na restart

    def _cleanup_loop(self):
        """
Czyści starych subskrybentów co 5 sekund."""
        while not self._stop_cleanup.is_set():
            try:
                self.distributor.cleanup_stale_subscribers(max_idle=30.0)
            except Exception as e:
                logger.debug(f"Błąd cleanup: {e}")

            self._stop_cleanup.wait(timeout=5.0)

    def stop(self):
        """
        Zatrzymuje capture i cleanup.
        """
        self._stop_event.set()
        self._stop_cleanup.set()

        if hasattr(self.generator, 'stop_capture'):
            try:
                self.generator.stop_capture()
            except Exception as e:
                logger.warning(f"Nie udało się zatrzymać kamery: {e}")

        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=3.0)
        if self._cleanup_thread and self._cleanup_thread.is_alive():
            self._cleanup_thread.join(timeout=3.0)

        logger.info("Capture loop stopped")


# ---------------------------------------------------------------------------
# Application class (opcjonalnie — Kivy App wrapper bez crasha)
# ---------------------------------------------------------------------------
class SztreamerrApp:
    """
    Aplikacja Sztreamerr.
    Na desktopie: tylko HTTP server (testowanie).
    Na Androidzie: opcjonalny Kivy UI (nie crashuje bo nie wymaga display na desktopie).
    """

    def __init__(self):
        self.server = None
        self.capture_loop: CaptureLoop | None = None
        self.distributor: FrameDistributor = None  # type: ignore
        self.generator: FrameGenerator = None      # type: ignore
        self._running = False

    def start(self, host="0.0.0.0", port=8080):
        """Startuje serwer i capture loop."""
        logger.info(f"Starting Sztreamerr on {host}:{port}")

        # Inicjalizacja generatora ramki (Camera2 na Androidzie)
        try:
            from jnius import autoclass  # tylko na Androidzie
            self.generator = AndroidFrameGenerator()
            logger.info("Using Android Camera2 frame generator")
        except ImportError:
            self.generator = FrameGenerator()
            logger.info("Using desktop frame generator (test bars)")

        # Frame distributor
        self.distributor = FrameDistributor()

        # Konfiguracja handlera HTTP
        MjpegRequestHandler.distributor = self.distributor  # type: ignore
        MjpegRequestHandler.generator = self.generator      # type: ignore
        MjpegRequestHandler.fps_target = 15                  # type: ignore

        # Capture loop
        self.capture_loop = CaptureLoop(self.generator, self.distributor)

        # Start serwera HTTP w osobnym wątku
        try:
            self.server = HTTPServer((host, port), MjpegRequestHandler)
            threading.Thread(
                target=self._serve_forever,
                name="Sztreamerr-HTTP",
                daemon=True,
            ).start()

            # Start capture loop
            self.capture_loop.start()
            self._running = True

            logger.info(f"✅ Sztreamerr running at http://{host}:{port}/stream")
            logger.info("   Endpoints: /stream, /health, /status, /info")

        except OSError as e:
            logger.error(f"Nie udało się uruchomić serwera: {e}")
            return False

        return True

    def _serve_forever(self):
        """Główna pętla serwera HTTP."""
        try:
            self.server.serve_forever()
        except Exception as e:
            logger.error(f"Błąd serwera: {e}")

    def stop(self):
        """Zatrzymuje serwer i capture loop."""
        logger.info("Stopping Sztreamerr...")
        self._running = False

        if self.capture_loop:
            self.capture_loop.stop()

        if self.server:
            try:
                self.server.shutdown()
            except Exception as e:
                logger.debug(f"Shutdown error: {e}")

        logger.info("Sztreamerr stopped")

    def wait(self):
        """
Czeka na zamknięcie (Ctrl+C)."""
        try:
            signal.signal(signal.SIGINT, self._signal_handler)
            signal.signal(signal.SIGTERM, self._signal_handler)
            while self._running:
                time.sleep(1.0)
        except KeyboardInterrupt:
            logger.info("KeyboardInterrupt — stopping...")
            self.stop()

    def _signal_handler(self, signum, frame):
        """Obsługa sygnałów (Ctrl+C)."""
        logger.info(f"Received signal {signum}")
        self._running = False


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
def main():
    """Główny entry point aplikacji."""
    import argparse
    parser = argparse.ArgumentParser(description="Sztreamerr IP Camera Streamer")
    parser.add_argument("--host", default="0.0.0.0", help="Host do nasłuchiwania (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8080, help="Port HTTP (default: 8080)")
    parser.add_argument("--fps", type=int, default=15, help="FPS streamu (default: 15)")

    args = parser.parse_args()

    # Ustawienie FPS globalnie dla handlera
    MjpegRequestHandler.fps_target = args.fps

    app = SztreamerrApp()
    if not app.start(host=args.host, port=args.port):
        sys.exit(1)

    try:
        app.wait()
    except KeyboardInterrupt:
        pass
    finally:
        app.stop()


if __name__ == "__main__":
    main()
