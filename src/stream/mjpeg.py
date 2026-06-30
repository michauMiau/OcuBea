"""MJPEG stream endpoint for Sztreamerr."""

from __future__ import annotations

import asyncio
import io
import logging
from typing import Any, AsyncIterator

logger = logging.getLogger(__name__)


async def mjpeg_endpoint(
    frame_iter: AsyncIterator[bytes],
) -> AsyncIterator[tuple[dict[str, str], bytes]]:
    """Yield HTTP chunks for a multipart/x-mixed-replace MJPEG stream.

    Each chunk is (headers_dict, body_bytes). The caller wraps this into an
    aiohttp.web.StreamResponse with ``content_type="multipart/x-mixed-replace; boundary=--frame"``.

    Args:
        frame_iter: Async iterator yielding JPEG/MJPEG frame bytes from the camera source.
    """
    for frame in frame_iter:
        if not isinstance(frame, (bytes, bytearray)):
            raise TypeError(f"Expected bytes or bytearray, got {type(frame).__name__}")

        headers = {
            "--frame": "",
            "Content-Type": "image/jpeg",
            "X-Frame-ID": str(id(frame)),
        }
        yield headers, frame  # type: ignore[misc]
    logger.info("MJPEG stream ended after ~%d frames", count)


async def frame_generator() -> AsyncIterator[bytes]:
    """Yield MJPEG frames from the V4L2 camera source."""
    from src.capture.v4l2 import Camera

    cap = Camera(index=0)
    try:
        cap.open(0)
        while True:
            frame = cap.read_frame()
            if frame is not None and isinstance(frame.pixels, bytes):
                yield frame.pixels
            await asyncio.sleep(1 / 30)
    finally:
        cap.close()
