"""Camera backend abstraction for Sztreamerr."""

from __future__ import annotations

import io
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True)
class VideoFrame:
    """A single raw video frame from the camera."""

    pixels: bytes
    width: int
    height: int
    timestamp_ms: float = 0.0

    def __post_init__(self) -> None:
        if self.timestamp_ms == 0.0:
            object.__setattr__(self, "timestamp_ms", time.time() * 1000)


@dataclass(frozen=True)
class CameraInfo:
    """Information about a camera device."""

    index: int
    name: str
    default_width: int = 1920
    default_height: int = 1080
    default_fps: float = 30.0


class CameraBackend(ABC):
    """Abstract base class for camera capture backends."""

    @abstractmethod
    def open(self, index: int) -> None:
        pass

    @abstractmethod
    def read_frame(self) -> VideoFrame | None:
        pass

    @abstractmethod
    def close(self) -> None:
        pass
