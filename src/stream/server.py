"""MJPEG streaming server with aiohttp and multi-viewer support."""

from __future__ import annotations

import asyncio
import io
import json
import logging
import mimetypes
import os
from dataclasses import dataclass, field
from typing import AsyncIterator

import aiohttp.web
from pydantic_settings import BaseSettings
from stream.multi_viewer import MultiViewerManager

logger = logging.getLogger(__name__)


class AppSettings(BaseSettings):
    host: str = "0.0.0.0"
    port: int = 8080
    web_root: str = field(default="src/ui")
    max_viewers: int = field(default=16)

    class Config:
        env_prefix = "SZTREAMERR_"


@dataclass(frozen=True)
class FrameSource:
    """Async generator yielding video frame bytes."""
    frames: AsyncIterator[bytes] | None = field(default=None)
    width: int = 1920
    height: int = 1080

    @classmethod
    async def blank_stream(cls, width: int = 1920, height: int = 1080) -> "FrameSource":
        """Create a FrameSource that yields no frames (blank MJPEG)."""
        # Create an empty generator — serves the stream but with no frame data
        async def _blank_generator() -> AsyncIterator[bytes]:
            while True:
                await asyncio.sleep(30)  # Very low refresh when idle
        
        return cls(frames=_blank_generator(), width=width, height=height)

    async def stream(self, request: aiohttp.web.Request) -> aiohttp.web.StreamResponse:
        """Serve the live camera feed as multipart/x-mixed-replace MJPEG."""
        boundary = b"frame_boundary"
        
        if self.frames is None:
            # No frames source — send a simple JPEG (blank frame)
            
            stream_response = aiohttp.web.StreamResponse(
                headers={
                    "Content-Type": f'multipart/x-mixed-replace; boundary="{boundary.decode()}"',
                }
            )
            await stream_response.prepare()
            return stream_response

        async def _mjpeg_generator() -> AsyncIterator[bytes]:
            async for frame_bytes in self.frames:
                yield (b"--" + boundary + b"\r\n"
                       b"Content-Type: image/jpeg\r\n"
                       b"Content-Length: " + str(len(frame_bytes)).encode() + b"\r\n\r\n"
                       + frame_bytes + b"\r\n")
            # Final boundary
            yield b"--" + boundary + b"--\r\n"
        
        stream_response = aiohttp.web.StreamResponse(
            headers={
                "Content-Type": f'multipart/x-mixed-replace; boundary="{boundary.decode()}"',
            }
        )
        await stream_response.prepare()
        
        try:
            async for chunk in _mjpeg_generator():
                await stream_response.write(chunk)
        except (aiohttp.web.HTTPException, OSError):
            logger.warning("Client disconnected during MJPEG streaming")
        
        return stream_response


class StreamServer:

    def __init__(self, frame_source: FrameSource | None = None, settings: AppSettings | None = None) -> None:
        self.frame_source = frame_source or FrameSource()
        self.settings = settings or AppSettings()
        self.viewer_manager = MultiViewerManager(max_viewers=self.settings.max_viewers)
        self._app: aiohttp.web.Application | None = None
        self._runner: aiohttp.web.AppRunner | None = None

    async def start(self) -> None:
        """Start the HTTP server."""
        # Create app first
        if self._app is None:
            self._app = self._create_app()

        # Then start AppRunner with the app
        self._runner = aiohttp.web.AppRunner(self._app)
        await self._runner.start()

        # Finally bind TCP site to the runner
        site = aiohttp.web.TCPSite(
            self._runner,
            host=self.settings.host,
            port=self.settings.port,
        )
        await site.start()

    async def stop(self) -> None:
        """Stop the HTTP server."""
        if self._app:
            # Clean up any pending tasks in the app
            for task in asyncio.all_tasks():
                if task is not asyncio.current_task():
                    task.cancel()
                    try:
                        await task
                    except asyncio.CancelledError:
                        pass

        if self._runner:
            await self._runner.cleanup()
            self._runner = None

    def _create_app(self) -> aiohttp.web.Application:
        """Create and configure the aiohttp application with multi-viewer support."""
        static_root = os.path.join(os.path.dirname(__file__), "..", "ui")
        app = aiohttp.web.Application(middlewares=[self._error_middleware])

        # Static files — web UI and assets
        app.router.add_static("/static/", static_root, name="static")
        app.router.add_get("/", self._serve_ui)
        
        # Multi-viewer MJPEG streaming endpoint
        if self.frame_source.frames is not None:
            async def mjpeg_multi_viewer_handler(request: aiohttp.web.Request) -> aiohttp.web.StreamResponse:
                """Serve the live camera feed as multipart/x-mixed-replace for multiple viewers."""
                viewer_id, frame_queue = await self.viewer_manager.add_viewer()
                
                boundary = b"frame_boundary"
                stream_response = aiohttp.web.StreamResponse(
                    headers={
                        "Content-Type": f'multipart/x-mixed-replace; boundary="{boundary.decode()}"',
                    }
                )
                await stream_response.prepare()
                
                try:
                    # Broadcast loop: push frames from queue to client
                    async def _mjpeg_generator() -> AsyncIterator[bytes]:
                        while True:
                            try:
                                frame_bytes = await asyncio.wait_for(
                                    frame_queue.get(), timeout=30.0
                                )
                                if frame_bytes is None:  # Disconnect sentinel
                                    break
                                yield (b"--" + boundary + b"\r\n"
                                       b"Content-Type: image/jpeg\r\n"
                                       b"Content-Length: " + str(len(frame_bytes)).encode() + b"\r\n\r\n"
                                       + frame_bytes + b"\r\n")
                            except asyncio.TimeoutError:
                                # Client disconnected — break loop
                                break
                        yield b"--" + boundary + b"--\r\n"
                    
                    async for chunk in _mjpeg_generator():
                        await stream_response.write(chunk)
                except (aiohttp.web.HTTPException, OSError):
                    logger.warning(f"Viewer {viewer_id} disconnected during MJPEG streaming")
                finally:
                    await self.viewer_manager.remove_viewer(viewer_id)
            
            app.router.add_get("/api/mjpeg", mjpeg_multi_viewer_handler)
        
        # API endpoints for camera configuration
        app.router.add_post("/api/camera", self._camera_api_handler)
        app.router.add_get("/api/frame-size", self._frame_size_handler)
        
        return app

    async def _error_middleware(
        handler: aiohttp.web.RequestHandler,
    ) -> aiohttp.web.Middleware:
        async def middleware(request: aiohttp.web.Request):
            try:
                response = await handler(request)
                if isinstance(response, aiohttp.web.StreamResponse) and response.status == 404:
                    # Serve index.html for SPA-style navigation
                    index_path = os.path.join(os.path.dirname(__file__), "..", "ui", "index.html")
                    if os.path.isfile(index_path):
                        return aiohttp.web.FileResponse(index_path)
                return response
            except (aiohttp.web.HTTPException, OSError):
                raise

        return middleware  # type: ignore[return-value]

    async def _serve_ui(
        self, request: aiohttp.web.Request,
    ) -> aiohttp.web.Response:
        """Serve the web UI index page."""
        index = os.path.join(os.path.dirname(__file__), "..", "ui", "index.html")
        if not os.path.isfile(index):
            raise aiohttp.web.HTTPNotFound(text="UI not found — check src/ui/index.html exists")
        return aiohttp.web.FileResponse(index)

    async def _camera_api_handler(
        self, request: aiohttp.web.Request,
    ) -> aiohttp.web.Response:
        """API endpoint for camera configuration."""
        try:
            data = await request.json()
            resp_data = {"status": "ok", "received": data}
        except Exception as exc:
            return aiohttp.web.json_response(
                {"status": "error", "message": str(exc)}, status=400,
            )
        return aiohttp.web.json_response(resp_data)

    async def _frame_size_handler(
        self, request: aiohttp.web.Request,
    ) -> aiohttp.web.Response:
        """API endpoint for frame size info."""
        return aiohttp.web.json_response({
            "width": self.frame_source.width,
            "height": self.frame_source.height,
            "status": "ok",
        })
