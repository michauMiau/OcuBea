"""FFmpeg encoding pipeline for Sztreamerr — manages continuous encoding."""

from __future__ import annotations

import asyncio
import logging
from typing import AsyncIterator

from encode.engine import EncodedFrame, H264Encoder

logger = logging.getLogger(__name__)


class EncodingPipeline:
    """Manages continuous encoding from raw frames to encoded stream.
    
    Takes raw YUV420 frames from camera and encodes them into H.264 format
    suitable for browser playback via MSE (Media Source Extensions).
    
    Pipeline architecture:
    - Producer: Camera capture pushes raw frames to internal queue
    - Consumer: Background task reads from queue, calls encoder.encode_frame()
    - Output: Encoded frames pushed to output queue for streaming layer
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
        
        # Internal frame queues (producer → consumer)
        self._frame_queue: asyncio.Queue[bytes | None] = asyncio.Queue(maxsize=4)
        
        # Output queue for encoded frames
        self._encoded_frame_queue: asyncio.Queue[EncodedFrame | None] = asyncio.Queue(maxsize=8)
        
        # Background encoding task
        self._encoding_task: asyncio.Task | None = None
        
        # H.264 encoder instance
        self._encoder: H264Encoder | None = None
        
        # Statistics
        self._frames_encoded = 0
        self._encode_errors = 0

    async def start(self) -> bool:
        """Start the encoding pipeline.
        
        Initializes encoder, starts background task, and returns True on success.
        """
        # Create H.264 encoder instance
        self._encoder = H264Encoder(
            width=self.width,
            height=self.height,
            framerate=self.framerate,
            bitrate=self.bitrate,
        )
        
        if not await self._encoder.start():
            logger.error("Failed to start encoder — pipeline will not work")
            return False
        
        # Start background encoding task
        self._encoding_task = asyncio.create_task(
            self._run_encoding_loop(),
            name="encoding-pipeline",
        )
        
        logger.info(f"Encoding pipeline started: {self.width}x{self.height} @ {self.framerate:.1f}fps")
        return True
    
    async def _run_encoding_loop(self) -> None:
        """Background task that continuously encodes frames.
        
        Reads from input queue, calls encoder.encode_frame(), and pushes results to output queue.
        Exits when a None sentinel is received or on error.
        """
        if self._encoder is None:
            return
        
        try:
            while True:
                # Read next raw frame (blocks until available)
                raw_frame = await self._frame_queue.get()
                if raw_frame is None:  # Sentinel — stop signal
                    break
                
                encoded = await self._encoder.encode_frame(raw_frame)
                
                if encoded is not None:
                    try:
                        self._encoded_frame_queue.put_nowait(encoded)
                        self._frames_encoded += 1
                    except asyncio.QueueFull:
                        logger.warning("Encoded frame queue full — dropping frame")
                else:
                    self._encode_errors += 1
        
        except Exception as e:
            logger.exception(f"Encoding loop error: {e}")
            self._encode_errors += 1
    
    async def stop(self) -> None:
        """Stop the encoding pipeline gracefully."""
        # Send sentinel to signal encoding loop to exit
        if self._frame_queue is not None:
            try:
                self._frame_queue.put_nowait(None)
            except asyncio.QueueFull:
                pass
        
        # Wait for background task (with timeout)
        if self._encoding_task is not None:
            try:
                await asyncio.wait_for(self._encoding_task, timeout=3.0)
            except asyncio.TimeoutError:
                logger.warning("Encoding task didn't finish in time — cancelling")
                self._encoding_task.cancel()
        
        # Stop encoder
        if self._encoder is not None:
            await self._encoder.stop()
        
        logger.info(
            f"Encoding pipeline stopped: {self._frames_encoded} frames encoded, "
            f"{self._encode_errors} errors"
        )
    
    def push_frame(self, raw_frame: bytes) -> bool:
        """Push a raw YUV420 frame into the input queue.
        
        Returns True if the frame was queued successfully, False otherwise.
        This is called by the camera capture layer to feed frames to the encoder.
        """
        try:
            self._frame_queue.put_nowait(raw_frame)
            return True
        except asyncio.QueueFull:
            logger.debug("Frame queue full — dropping frame (encoder too slow)")
            return False
    
    async def get_encoded_frames(self) -> AsyncIterator[EncodedFrame]:
        """Async iterator over encoded frames from the output queue.
        
        Yields each encoded frame as it becomes available. Exits when:
        - Pipeline is stopped (None sentinel pushed)
        - An exception occurs
        """
        if self._encoded_frame_queue is None:
            return
        
        try:
            while True:
                frame = await self._encoded_frame_queue.get()
                if frame is None:  # Sentinel — pipeline stopped
                    break
                yield frame
        except asyncio.CancelledError:
            pass
    
    @property
    def stats(self) -> dict[str, int | float]:
        """Get pipeline statistics for monitoring."""
        return {
            "frames_encoded": self._frames_encoded,
            "encode_errors": self._encode_errors,
            "width": self.width,
            "height": self.height,
            "framerate": self.framerate,
            "bitrate_kbps": self.bitrate // 1000,
        }
