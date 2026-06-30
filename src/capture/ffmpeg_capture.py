"""FFmpeg-based camera capture — cross-platform (Android + desktop).

Uses FFmpeg subprocess to read frames from a device or RTSP stream.
Works on any platform where FFmpeg is available, including Android via Termux.
"""


from __future__ import annotations

import asyncio
import glob
import logging
import os
import subprocess
import time
from dataclasses import dataclass
from typing import AsyncIterator

from .backend import CameraBackend, VideoFrame

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class Config:
    """Configuration for FFmpeg-based camera capture."""
    device: str = ""  # empty = auto-detect based on platform
    resolution_w: int = 1920
    resolution_h: int = 1080
    framerate: float = 30.0

    def to_ffmpeg_args(self) -> list[str]:
        """Generate FFmpeg command arguments for capture."""
        return [
            "ffmpeg", "-y",
            "-f", self.input_format,
            "-video_size", f"{self.resolution_w}x{self.resolution_h}",
            "-framerate", str(self.framerate),
            "-i", self.device or "",
            "-f", "rawvideo",
            "-pix_fmt", "yuv420p",
        ]

    @property
    def input_format(self) -> str:
        """Return platform-specific FFmpeg input format."""
        if os.name == "posix":
            return "v4l2"  # Linux/Android Termux — try V4L2 first, fall back to camera1
        elif os.name == "nt":
            return "dshow"   # Windows — DirectShow
        else:               # macOS
            return "avfoundation"


class Camera(CameraBackend):
    """FFmpeg subprocess camera capture.

    Works on any platform with FFmpeg installed.
    For Android: run via Termux or similar environment where ffmpeg binary is available.
    For Linux: uses V4L2 devices (/dev/video*).
    For macOS: uses avfoundation (built-in cameras).
    For Windows: uses dshow (DirectShow).
    """

    def __init__(self, device: str | None = None, config: Config | None = None) -> None:
        self._device = device or ""
        # Auto-detect device if not specified
        if not self._device:
            self._device = self._auto_detect_device()
        self.config = config or Config(device=self._device)
        self._ffmpeg_proc: asyncio.subprocess.Process | None = None
        self._capture_task: asyncio.Task[None] | None = None

    def _auto_detect_device(self) -> str:
        """Auto-detect the camera device based on platform."""
        if os.name == "posix":
            # Linux — scan V4L2 devices
            v4l2_devices = sorted(glob.glob("/dev/video*"))
            for dev in v4l2_devices:
                try:
                    with open(dev, "rb", buffering=0) as f:
                        pass  # Try opening to verify it's a valid device
                    return dev
                except (OSError, PermissionError):
                    continue
        elif os.name == "nt":
            # Windows — enumerate via FFmpeg dshow
            try:
                result = asyncio.get_event_loop().run_until_complete(
                    asyncio.create_subprocess_exec(
                        "ffmpeg", "-list_devices", "true", "-f", "dshow",
                        "-i", "dummy", stdout=asyncio.subprocess.PIPE, stderr=subprocess.STDOUT,
                    ).communicate()
                )
                output = result.stdout.decode(errors="replace") if result.stdout else ""
                for line in output.splitlines():
                    if ": Camera" in line:
                        device_name = line.strip().split(":")[1].strip()
                        return f"video={device_name}"
            except Exception as e:
                logger.warning(f"dshow enumeration failed: {e}")
        else:  # macOS — avfoundation
            try:
                result = asyncio.get_event_loop().run_until_complete(
                    asyncio.create_subprocess_exec(
                        "ffmpeg", "-list_devices", "true", "-f", "avfoundation",
                        "-i", "dummy", stdout=asyncio.subprocess.PIPE, stderr=subprocess.STDOUT,
                    ).communicate()
                )
                output = result.stdout.decode(errors="replace") if result.stdout else ""
                for line in output.splitlines():
                    if ": Camera" in line:
                        device_name = line.strip().split(":")[1].strip()
                        return f"0:{device_name}"
            except Exception as e:
                logger.warning(f"avfoundation enumeration failed: {e}")

        # Default fallback — assume first available device
        if os.name == "posix":
            v4l2_devices = sorted(glob.glob("/dev/video*"))
            return v4l2_devices[0] if v4l2_devices else "/dev/video0"
        elif os.name == "nt":
            return "video=HD Pro Webcam C920"  # common device name — user can override
        else:
            return "0:"  # first avfoundation camera

    async def open(self, index: int = 0) -> None:
        """Open the camera via FFmpeg subprocess — single process for MJPEG output.

        Uses ONE ffmpeg subprocess that directly captures from the device AND encodes to MJPEG.
        This is MUCH faster than capturing YUV420p and encoding each frame separately.
        
        Args:
            index: Camera device index (used only for macOS avfoundation)
        """
        # Single FFmpeg process — capture + encode in one step
        mjpeg_args = [
            "ffmpeg", "-y",
            "-f", self.config.input_format,
            "-video_size", f"{self.config.resolution_w}x{self.config.resolution_h}",
            "-framerate", str(self.config.framerate),
            "-i", self._device or "",  # camera device URL
            "-c:v", "mjpeg",           # MJPEG output for low latency streaming
            "-q:v", "8",               # Quality level (1-31, lower = better)
            "-f", "image2pipe",        # Pipe MJPEG frames to stdout
        ]

        logger.info(f"Starting single-process FFmpeg MJPEG capture: {' '.join(mjpeg_args)}")
        
        try:
            self._ffmpeg_proc = await asyncio.create_subprocess_exec(
                *mjpeg_args,
                stdout=asyncio.subprocess.PIPE,
                stderr=subprocess.STDOUT,  # Capture errors too
            )

            # Verify FFmpeg started successfully (wait for first frame to confirm)
            # First byte of MJPEG is SOI marker (0xFF), then D8 (0xD8) = start of image
            soi_bytes = await asyncio.wait_for(
                self._ffmpeg_proc.stdout.readexactly(2), timeout=5.0,
            )

            if not soi_bytes or soi_bytes[0] != 0xFF:
                raise RuntimeError("FFmpeg subprocess failed to output MJPEG (bad SOI marker)")

        except asyncio.TimeoutError:
            logger.error(f"FFmpeg capture timed out for device {self._device}")
            self.close()
            raise
        except Exception as e:
            logger.error(f"Failed to open FFmpeg camera: {e}")
            self.close()
            raise

    async def read_frame(self) -> VideoFrame | None:
        """Read a single MJPEG frame from the persistent FFmpeg subprocess output.

        FFmpeg MJPEG output is a continuous stream of JPEG frames delimited by SOI/EOI markers.
        We scan for these to extract individual complete frames — no fixed-size reads needed!
        Returns None if capture fails or times out.
        """
        return await self._read_mjpeg_frame()

    async def _read_mjpeg_frame(self) -> VideoFrame | None:
        """Read a single MJPEG frame by scanning for JPEG SOI/EOI markers in the stream."""
        if not self._ffmpeg_proc or not self._ffmpeg_proc.stdout:
            return None

        # Read small chunks (4KB) and look for complete JPEG frames
        buffer = b""
        chunk_size = 4096
        
        while True:
            try:
                chunk = await asyncio.wait_for(
                    self._ffmpeg_proc.stdout.read(chunk_size), timeout=2.0,
                )
            except (asyncio.TimeoutError, OSError):
                return None

            if not chunk:
                return None  # EOF or broken pipe

            buffer += chunk
            
            # Scan for SOI marker (0xFFD8) and EOI marker (0xFFD9) in the buffer
            soi_pos = Camera._find_soi(buffer)
            
            if soi_pos >= 1:  # Found SOI after first byte — previous frame ended here
                # Extract complete frame from previous SOI to this SOI's start
                eoi_pos = self._find_eoi_from_position(buffer, soi_pos - 4)
                
                if eoi_pos is not None and eoi_pos > soi_pos:
                    jpeg_data = buffer[eoi_pos + 2:soi_pos]  # From EOI to SOI (exclusive of markers)
                    timestamp_ms = time.time() * 1000.0
                    
                    # Re-add the JPEG frame boundaries for a valid JPEG
                    return VideoFrame(
                        pixels=jpeg_data,
                        width=self.config.resolution_w,
                        height=self.config.resolution_h,
                        timestamp_ms=timestamp_ms,
                    )

            elif soi_pos == 0:  # First byte IS the SOI marker
                eoi_pos = self._find_eoi(buffer)
                
                if eoi_pos is not None:
                    jpeg_data = buffer[2:eoi_pos + 2]  # From SOI+2 to EOI+2 (include markers)
                    timestamp_ms = time.time() * 1000.0
                    
                    return VideoFrame(
                        pixels=jpeg_data,
                        width=self.config.resolution_w,
                        height=self.config.resolution_h,
                        timestamp_ms=timestamp_ms,
                    )

            # If buffer is getting too large without finding a frame, clear it
            if len(buffer) > 1_048_576:  # 1MB — likely stuck, clear and start fresh
                logger.warning("MJPEG buffer overflow — clearing")
                soi_pos = Camera._find_soi(buffer)
                if soi_pos >= 0:
                    buffer = buffer[soi_pos:]  # Keep from the last SOI found

        return None

    @staticmethod
    def _find_soi(data: bytes, start_pos=0) -> int:
        """Find the SOI (Start of Image) marker in data. Returns position or -1."""
        pos = start_pos
        while True:
            idx = data.find(b"\xff\xd8", pos)
            if idx == -1:
                return -1
            pos = idx + 2

    @staticmethod
    def _find_eoi(data: bytes, start_pos=0) -> int | None:
        """Find the EOI (End of Image) marker after a given position. Returns position or None."""
        eoi_end = data.find(b"\xff\xd9", start_pos)
        return eoi_end

    @staticmethod
    def _find_eoi_from_position(data: bytes, search_start=0) -> int | None:
        """Find the EOI marker between search_start and a SOI that follows."""
        soi_pos = Camera._find_soi(data, search_start)
        if soi_pos >= 0:
            return Camera._find_eoi(data, search_start)
        return None

    async def close(self) -> None:
        """Close the FFmpeg subprocess."""
        if self._ffmpeg_proc and self._ffmpeg_proc.returncode is None:
            self._ffmpeg_proc.terminate()
            try:
                await asyncio.wait_for(
                    self._ffmpeg_proc.wait(), timeout=2.0,
                )
            except asyncio.TimeoutError:
                logger.warning("FFmpeg subprocess didn't terminate gracefully, killing")
                self._ffmpeg_proc.kill()
        self._ffmpeg_proc = None
        logger.info(f"FFmpeg camera capture stopped for {self.device}")

    @property
    def device(self) -> str:
        """Return the current camera device."""
        return self._device


class CameraInfo:
    """Camera information for enumeration."""

    def __init__(self, index: int, name: str, default_width: int = 1920, 
                 default_height: int = 1080, default_fps: float = 30.0) -> None:
        self.index = index
        self.name = name
        self.default_width = default_width
        self.default_height = default_height
        self.default_fps = default_fps

    @staticmethod
    async def enumerate_cameras() -> list[CameraInfo]:
        """Enumerate available cameras using FFmpeg."""
        cam_infos = []

        if os.name == "posix":
            # Linux — scan V4L2 devices
            v4l2_devices = sorted(glob.glob("/dev/video*"))
            for idx, dev in enumerate(v4l2_devices):
                try:
                    with open(dev, "rb", buffering=0) as f:
                        pass  # Verify device is accessible
                    cam_infos.append(CameraInfo(
                        index=idx,
                        name=f"V4L2 Camera {dev}",
                        default_width=1920,
                        default_height=1080,
                        default_fps=30.0,
                    ))
                except (OSError, PermissionError):
                    continue
        elif os.name == "nt":  # Windows — use dshow enumeration via FFmpeg
            try:
                result = await asyncio.create_subprocess_exec(
                    "ffmpeg", "-list_devices", "true", "-f", "dshow",
                    "-i", "dummy", stdout=asyncio.subprocess.PIPE, stderr=subprocess.STDOUT,
                ).communicate()

                output = result.stdout.decode(errors="replace") if result.stdout else ""
                # Parse device names from FFmpeg dshow output
                for line in output.splitlines():
                    if ": Camera" in line:
                        name = line.strip().split(":")[1].strip()
                        cam_infos.append(CameraInfo(
                            index=len(cam_infos),
                            name=name,
                            default_width=1920,
                            default_height=1080,
                            default_fps=30.0,
                        ))
            except Exception as e:
                logger.warning(f"dshow enumeration failed: {e}")
        else:  # macOS — use avfoundation (macOS) or dshow (Windows)
            try:
                result = await asyncio.create_subprocess_exec(
                    "ffmpeg", "-list_devices", "true", "-f", "avfoundation" if os.name != "nt" else "dshow",
                    "-i", "dummy", stdout=asyncio.subprocess.PIPE, stderr=subprocess.STDOUT,
                ).communicate()

                output = result.stdout.decode(errors="replace") if result.stdout else ""
                # Parse device names from FFmpeg output
                for line in output.splitlines():
                    if ": Camera" in line:
                        name = line.strip().split(":")[1].strip()
                        cam_infos.append(CameraInfo(
                            index=len(cam_infos),
                            name=name,
                            default_width=1920,
                            default_height=1080,
                            default_fps=30.0,
                        ))
            except Exception as e:
                logger.warning(f"Camera enumeration failed: {e}")

        return cam_infos if cam_infos else [
            CameraInfo(
                index=0,
                name="Default Camera",
                default_width=1920,
                default_height=1080,
                default_fps=30.0,
            )
        ]
