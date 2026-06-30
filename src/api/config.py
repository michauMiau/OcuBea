"""API configuration for Sztreamerr."""

from dataclasses import dataclass, field


@dataclass(frozen=True)
class CameraConfig:
    """Camera configuration for API endpoints."""
    resolution_w: int = 1920
    resolution_h: int = 1080
    framerate: float = 30.0
    brightness: int = 50
    contrast: int = 50
    saturation: int = 50
