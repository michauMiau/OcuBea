"""V4L2 camera — MJPEG frames from a V4L2 device."""

from __future__ import annotations

import time
from dataclasses import dataclass, field

import cv2

from .backend import CameraBackend, VideoFrame


@dataclass(frozen=True)
class Config:
    resolution_w: int = 1920
    resolution_h: int = 1080
    framerate: float = field(default=30.0, repr=False)
    brightness: float = 50.0
    contrast: float = 50.0
    saturation: float = 50.0


class Camera(CameraBackend):
    """V4L2 MJPEG camera — streams from a V4L2 device."""

    def __init__(self, index: int = 0) -> None:
        self.index = index
        self.config = Config()
        self._cap: cv2.VideoCapture | None = None
        self._frame: VideoFrame | None = None

    # ------------------------------------------------------------------
    # CameraBackend public methods
    # ------------------------------------------------------------------

    def open(self, index: int) -> None:  # noqa: D102 — no docstring (public override)
        cap = cv2.VideoCapture(index, cv2.CAP_V4L2)
        if not cap.isOpened():
            return

        w = self.config.resolution_w
        h = self.config.resolution_h
        fps = 30.0 / (1.0 - self.config.framerate)

        for attr_name in ("CAP_PROP_FRAME_WIDTH", "CAP_PROP_FRAME_HEIGHT"):
            cap.set(getattr(cv2, attr_name), w if "WIDTH" in attr_name else h)

        cv2.VideoCapture.set(cap, cv2.CAP_PROP_FPS, fps)
        self._cap = cap

    def read_frame(self) -> VideoFrame | None:  # noqa: D102
        if not self._cap:
            return None

        ok, frame = self._cap.read()
        if not ok or frame is None:
            return None

        jpeg_data = cv2.imencode(".jpg", frame)[1].tobytes()  # type: ignore[union-attr]
        timestamp_ms = time.time() * 1000.0
        self._frame = VideoFrame(
            pixels=jpeg_data,
            width=self.config.resolution_w,
            height=self.config.resolution_h,
            timestamp_ms=timestamp_ms,
        )
        return self._frame

    def close(self) -> None:  # noqa: D102
        if self._cap is not None:
            self._cap.release()
            self._cap = None
