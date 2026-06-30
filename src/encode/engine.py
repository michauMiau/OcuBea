"""FFmpeg encoding engine for Sztreamerr — continuous H.264/H.265 streaming encoder."""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import AsyncIterator

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class EncodedFrame:
    """A single encoded video frame (H.264/H.265/MJPEG)."""

    payload: bytes
    timestamp_ms: float
    codec: str = "mjpeg"
    width: int = 0
    height: int = 0


class H264Encoder:
    """Continuous H.264 encoder using FFmpeg subprocess.
    
    Takes raw YUV420 frames from camera and produces a continuous H.264 stream
    suitable for browser playback via MSE (Media Source Extensions).
    
    Architecture:
    - Single FFmpeg process handles all encoding
    - Frames are pushed to stdin as they arrive
    - Encoded NAL units are read from stdout continuously
    """

    def __init__(
        self,
        width: int = 1920,
        height: int = 1080,
        framerate: float = 30.0,
        bitrate: int = 2_500_000,
    ) -> None:
        self.width = width
        self.height = height
        self.framerate = framerate
        self.bitrate = bitrate
        
        # FFmpeg process state
        self._process: asyncio.subprocess.Process | None = None
        self._running = False
        
        # Statistics
        self._frames_in = 0
        self._bytes_encoded = 0
        self._start_time = time.monotonic()
    
    async def start(self) -> bool:
        """Start the FFmpeg encoder process.
        
        Returns True if started successfully, False otherwise.
        """
        cmd = [
            "ffmpeg",
            "-y",
            "-f", "rawvideo",
            "-vcodec", "rawvideo",
            "-s", f"{self.width}x{self.height}",
            "-pix_fmt", "yuv420p",
            "-r", str(self.framerate),
            "-i", "-",
            
            "-c:v", "libx264",
            "-b:v", f"{self.bitrate}k",
            "-preset", "ultrafast",
            "-tune", "zerolatency",
            
            "-f", "h264",
            "pipe:1",
        ]
        
        try:
            self._process = await asyncio.create_subprocess_exec(
                *cmd,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
            )
            
            self._running = True
            self._start_time = time.monotonic()
            logger.info(
                f"H.264 encoder started: {self.width}x{self.height} @ "
                f"{self.framerate:.1f}fps, bitrate={self.bitrate // 1000}kbps"
            )
            return True
        
        except FileNotFoundError:
            logger.error("FFmpeg not found — H.264 encoding unavailable")
            return False
        except Exception as e:
            logger.error(f"Failed to start FFmpeg encoder: {e}")
            self._process = None
            return False
    
    async def encode_frame(self, raw_frame: bytes) -> EncodedFrame | None:
        """Encode a single YUV420 frame.
        
        Args:
            raw_frame: Raw YUV420 frame data from camera
        
        Returns:
            EncodedFrame if successful, None if encoding failed or encoder stopped
        """
        if not self._running or self._process is None:
            return None

        stdin = self._process.stdin
        stdout = self._process.stdout
        if stdin is None or stdout is None:
            return None

        try:
            stdin.write(raw_frame)
            await stdin.drain()

            self._frames_in += 1

            encoded = await asyncio.wait_for(
                stdout.read(50 * 1024),
                timeout=1.0,
            )
        
            if not encoded:
                self._running = False
                return None
            
            self._bytes_encoded += len(encoded)
            ts = time.monotonic() * 1000
            
            return EncodedFrame(
                payload=encoded,
                timestamp_ms=ts,
                codec="h264",
                width=self.width,
                height=self.height,
            )
        
        except asyncio.TimeoutError:
            logger.warning("FFmpeg stdout read timeout — stream may be stalled")
            return None
        except BrokenPipeError:
            logger.error("FFmpeg encoder pipe broken — stopping")
            self._running = False
            return None
        except Exception as e:
            logger.exception(f"Encoding error: {e}")
            return None
    
    async def stop(self) -> None:
        """Stop the FFmpeg encoder process."""
        if not self._running or self._process is None:
            return
        
        try:
            if self._process.stdin and not self._process.stdin.is_closing():
                self._process.stdin.close()
                await self._process.stdin.wait_closed()
            
            try:
                await asyncio.wait_for(
                    self._process.wait(),
                    timeout=3.0,
                )
            except asyncio.TimeoutError:
                logger.warning("FFmpeg didn't exit gracefully — killing")
                if self._process.returncode is None:
                    self._process.kill()
        
        except Exception as e:
            logger.warning(f"Error stopping FFmpeg encoder: {e}")
        finally:
            self._running = False
            self._process = None
    
    @property
    def is_running(self) -> bool:
        """Check if encoder is currently running."""
        return self._running and self._process is not None
    
    async def get_encoded_frames(self) -> AsyncIterator[EncodedFrame]:
        """Continuous iterator over encoded frames.
        
        This method blocks until the next encoded frame is available,
        then yields it. Returns when the encoder stops or an error occurs.
        """
        while self._running and self._process is not None:
            # Send a dummy empty frame to trigger read from stdout
            result = await self.encode_frame(b"")
            if result is not None:
                yield result
    
    @property
    def stats(self) -> dict[str, int | float]:
        """Get encoder statistics."""
        uptime = time.monotonic() - self._start_time
        fps = self._frames_in / max(uptime, 0.001)
        
        return {
            "running": self._running,
            "frames_in": self._frames_in,
            "bytes_encoded": self._bytes_encoded,
            "width": self.width,
            "height": self.height,
            "bitrate_kbps": self.bitrate // 1000,
            "uptime_s": uptime,
            "real_fps": fps,
        }


async def detect_hardware_encoder() -> str | None:
    """Detect available hardware encoders via FFmpeg.
    
    Returns the preferred encoder name (e.g., "h264_vaapi", "hevc_nvenc"),
    or None if only software fallback is available.
    """
    try:
        proc = await asyncio.create_subprocess_exec(
            "ffmpeg", "-hwaccels",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
        )
        stdout, _ = await proc.communicate()
        hwaccels = stdout.decode(errors="replace").lower()
        
        for preferred in ("vaapi", "nvenc", "videotoolbox"):
            if preferred in hwaccels:
                return preferred
    except Exception as e:
        logger.debug(f"Could not detect hardware encoder: {e}")
    
    return None


async def get_available_codecs() -> list[str]:
    """Get list of available codecs from FFmpeg.
    
    Returns: List of codec names that can be used for encoding.
    """
    try:
        proc = await asyncio.create_subprocess_exec(
            "ffmpeg", "-encoders",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
        )
        stdout, _ = await proc.communicate()
        
        codecs = []
        for line in stdout.decode(errors="replace").split('\n'):
            if 'libx264' in line or 'libx265' in line or 'h264' in line.lower():
                codec_name = line.strip().split()[-1] if line.strip() else ''
                if codec_name and codec_name not in codecs:
                    codecs.append(codec_name)
        return codecs
    except Exception:
        pass
    
    # Always have software fallback available
    return ["libx264"]
