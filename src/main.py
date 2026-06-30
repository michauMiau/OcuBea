"""Sztreamerr — Main entry point.

Launches the camera capture, encoding pipeline, and streaming server.
"""


from __future__ import annotations

import asyncio
import logging
import os
import signal
import sys
from dataclasses import dataclass, field
from typing import AsyncIterator

# Add src to path so imports work from main.py
src_dir = os.path.dirname(os.path.abspath(__file__))
if src_dir not in sys.path:
    sys.path.insert(0, src_dir)

from capture.ffmpeg_capture import Camera as FFmpegCamera, Config as CaptureConfig
from core import Settings


logger = logging.getLogger(__name__)


class SztreamerrApp:
    """Main application — captures, encodes, and streams."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._camera_closed = False  # Track to prevent double-close
        
        # Use FFmpeg subprocess for cross-platform capture (works on Android + desktop)
        if os.name == "posix":  # Linux/Android
            try:
                from capture.ffmpeg_capture import Camera as FFmpegCamera
                from capture.ffmpeg_capture import Config as CaptureConfig
                
                self.camera = FFmpegCamera(
                    config=CaptureConfig(
                        device="/dev/video0",
                        resolution_w=settings.camera.resolution_w,
                        resolution_h=settings.camera.resolution_h,
                        framerate=settings.camera.framerate,
                    ),
                )
            except Exception as e:
                logger.warning(f"FFmpeg camera not available: {e}, falling back")
                self.camera = None  # Will use MJPEG stream instead
        else:  # Windows/macOS — no V4L2
            logger.info("No V4L2 on this platform, will try FFmpeg subprocess fallback")
            self.camera = None

    async def run(self) -> None:
        """Run the app — start streaming."""
        
        # Create frame source for MJPEG streaming (always serve something, even if no camera)
        from stream.server import FrameSource as HttpFrameSource
        
        if self.camera:
            logger.info("Starting MJPEG capture with FFmpeg subprocess")
            try:
                await self.camera.open()  # No index arg — MJPEG output now
                frame_source = await self._create_frame_source()
            except Exception as e:
                logger.error(f"Failed to create MJPEG stream from camera: {e}")
                # Fallback: serve blank MJPEG when camera fails
                frame_source = await HttpFrameSource.blank_stream(
                    width=self.settings.camera.resolution_w,
                    height=self.settings.camera.resolution_h,
                )
        else:
            logger.warning("No camera — starting server without live feed, serving static UI only")
            # Serve blank MJPEG when no camera available
            frame_source = await HttpFrameSource.blank_stream(
                width=self.settings.camera.resolution_w,
                height=self.settings.camera.resolution_h,
            )

        from stream.server import StreamServer as HttpStreamServer, AppSettings as ServerSettings
        
        settings = ServerSettings(
            host=self.settings.server.host,
            port=self.settings.server.port,
        )
        
        server = await self._start_server(frame_source, settings)
        
        try:
            await asyncio.sleep(60 * 60 * 24)  # Run forever until shutdown signal
        except asyncio.CancelledError:
            pass
        finally:
            await self.shutdown()
            await server.stop()

    async def _create_frame_source(self):
        """Create a FrameSource with MJPEG streaming from camera.
        
        Camera is already opened by run(). This method only creates the generator pipeline.
        The MJPEG generator yields raw JPEG bytes — server.py's stream() will add
        MJPEG multipart boundaries and headers.
        """
        queue: asyncio.Queue[bytes] = asyncio.Queue(maxsize=2)  # small queue to avoid buffer bloat
        
        async def _capture_loop():
            """Capture MJPEG frames and push them to the queue."""
            try:
                while True:
                    frame = await self.camera.read_frame()
                    if frame is None:
                        logger.warning("Camera returned None — stopping capture")
                        break
                    
                    # push raw JPEG bytes to queue (no re-encoding needed!)
                    try:
                        queue.put_nowait(frame.pixels)  # Just the JPEG data, no headers
                    except asyncio.QueueFull:
                        logger.error("Frame queue full — dropping frame, buffer bloat detected!")

            except Exception as e:
                logger.error(f"Capture loop failed: {e}")
            finally:
                # Close camera exactly once - use _close_camera_safely to prevent double-close
                await self._close_camera_safely()

        # Start capture in background - will close camera when done (error or normal shutdown)
        capture_task = asyncio.create_task(_capture_loop())

        async def _mjpeg_generator():
            """Yield raw JPEG frame bytes only — server adds MJPEG boundaries/headers."""
            try:
                while True:
                    jpeg_bytes = await queue.get()
                    if not jpeg_bytes:
                        break
                    
                    yield jpeg_bytes  # Just the raw JPEG, no MJPEG headers

            finally:
                # This task should NOT close the camera - it's opened by run() and closed during shutdown via app.shutdown()
                pass

        from stream.server import FrameSource as HttpFrameSource
        return HttpFrameSource(
            frames=_mjpeg_generator(),
            width=self.camera.config.resolution_w,
            height=self.camera.config.resolution_h,
        )

    async def _close_camera_safely(self) -> None:
        """Close the camera exactly once, tracking state."""
        if self._camera_closed:
            logger.debug("Camera already closed — skipping")
            return
        
        try:
            await asyncio.wait_for(
                self.camera.close(), timeout=3.0,
            )
            self._camera_closed = True
            logger.info("Camera closed safely")
        except asyncio.TimeoutError:
            logger.warning("Camera close timed out — marking as closed anyway")
            self._camera_closed = True

    async def _start_server(self, frame_source: "HttpFrameSource | None", settings):
        """Start the HTTP server."""
        from stream.server import StreamServer as HttpStreamServer
        
        server = HttpStreamServer(frame_source=frame_source, settings=settings)
        
        await server.start()
        logger.info(f"Streaming started on {settings.host}:{settings.port}")

    async def shutdown(self) -> None:
        """Graceful shutdown."""
        if self.camera is not None and not self._camera_closed:
            try:
                await asyncio.wait_for(
                    self.camera.close(), timeout=3.0,
                )
                self._camera_closed = True
            except asyncio.TimeoutError:
                logger.warning("Camera close timed out")
