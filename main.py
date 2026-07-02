"""Sztreamerr — lightweight IP camera streamer for phones."""

import asyncio
import logging
import os
from dataclasses import dataclass, field
from typing import Any, Dict, Optional

# ─── Configuration ────────────────────────────────────────
@dataclass(frozen=True)
class CameraConfig:
    resolution_w: int = 1920
    resolution_h: int = 1080
    framerate: float = 30.0
    brightness: int = 50
    contrast: int = 50
    saturation: int = 50

@dataclass(frozen=True)
class ServerConfig:
    host: str = "0.0.0.0"
    port: int = 8080
    concurrent_limit: int = 16

@dataclass(frozen=True)
class Settings:
    camera: CameraConfig = field(default_factory=CameraConfig)
    server: ServerConfig = field(default_factory=ServerConfig)

# ─── App ──────────────────────────────────────────────────
class SztreamerrApp:
    """Main application — captures, encodes, and streams."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._camera_closed = False
        from capture.ffmpeg_capture import Camera as FFmpegCamera, Config as CaptureConfig
        try:
            if os.name == "posix":
                self.camera: Any | None = FFmpegCamera(
                    config=CaptureConfig(
                        device="/dev/video0",
                        resolution_w=settings.camera.resolution_w,
                        resolution_h=settings.camera.resolution_h,
                        framerate=settings.camera.framerate,
                    ),
                )
            else:
                self.camera = None
        except Exception as e:
            logger.warning(f"Camera init failed: {e}")
            self.camera = None

    async def run(self) -> None:
        from stream.server import StreamServer, AppSettings as ServerSettings
        if self.camera:
            try:
                await self.camera.open()
                frame_source = await self._create_frame_source()
            except Exception as e:
                logger.error(f"Camera stream failed: {e}")
                from stream.server import FrameSource
                frame_source = await FrameSource.blank_stream(
                    width=self.settings.camera.resolution_w,
                    height=self.settings.camera.resolution_h,
                )
        else:
            from stream.server import StreamServer, AppSettings as ServerSettings
            server = StreamServer(settings=ServerSettings())
            await server.start()
            logger.info(f"No camera — running on {settings.host}:{settings.port}")
            try:
                await asyncio.sleep(60 * 60 * 24)
            finally:
                await server.stop()
            return
        server = StreamServer(frame_source=frame_source, settings=ServerSettings(
            host=self.settings.server.host,
            port=self.settings.server.port,
        ))
        await server.start()
        logger.info(f"Streaming on {self.settings.server.host}:{self.settings.server.port}")
        try:
            await asyncio.sleep(60 * 60 * 24)
        except asyncio.CancelledError:
            pass
        finally:
            await self.shutdown()
            await server.stop()

    async def _create_frame_source(self):
        from stream.server import FrameSource as HttpFrameSource
        queue: asyncio.Queue[bytes] = asyncio.Queue(maxsize=2)

        async def _capture_loop():
            try:
                while True:
                    frame = await self.camera.read_frame()
                    if frame is None:
                        break
                    try:
                        queue.put_nowait(frame.pixels)
                    except asyncio.QueueFull:
                        logger.error("Frame queue full — dropping")
            finally:
                await self._close_camera_safely()

        capture_task = asyncio.create_task(_capture_loop())

        async def _mjpeg_generator():
            try:
                while True:
                    yield await queue.get()
            finally:
                pass

        return HttpFrameSource(
            frames=_mjpeg_generator(),
            width=self.camera.config.resolution_w,
            height=self.camera.config.resolution_h,
        )

    async def _close_camera_safely(self) -> None:
        if self._camera_closed or not hasattr(self, 'camera') or self.camera is None:
            return
        try:
            await asyncio.wait_for(self.camera.close(), timeout=3.0)
        except Exception as e:
            logger.warning(f"Camera close error: {e}")
        finally:
            self._camera_closed = True

    async def shutdown(self) -> None:
        if self.camera and not self._camera_closed:
            try:
                await asyncio.wait_for(self.camera.close(), timeout=3.0)
                self._camera_closed = True
            except Exception as e:
                logger.warning(f"Camera close timed out: {e}")

logger = logging.getLogger(__name__)
settings = Settings()
