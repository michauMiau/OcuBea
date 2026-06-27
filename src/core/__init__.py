"""Sztreamerr application configuration."""

from dataclasses import dataclass, field


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
