"""Frame distributor for multi-viewer streaming.

Broadcasts frames to multiple concurrent viewers using asyncio.Queue pub/sub pattern.
Each viewer gets their own MJPEG boundary wrapping and independent reading.
"""

from __future__ import annotations

import asyncio
import logging
from typing import AsyncIterator

logger = logging.getLogger(__name__)


class FrameDistributor:
    """Pub/sub frame distributor for multi-viewer streaming.
    
    Receives frames from the encoding pipeline and broadcasts them to all connected viewers.
    Each viewer maintains their own queue position, allowing independent reading speeds.
    
    Architecture:
    - Producer: Encoding pipeline pushes frames
    - Distributor: Broadcasts to N subscriber queues
    - Consumers: Individual viewer async generators read from their own queue
    """
    
    def __init__(self) -> None:
        self._subscribers: list[asyncio.Queue] = []
        self._running = False
        self._max_subscribers = 8
        self._total_frames_broadcast = 0
    
    async def start(self) -> None:
        """Start the frame distributor."""
        self._running = True
        logger.info("Frame distributor started")
    
    async def stop(self) -> None:
        """Stop the distributor and notify all subscribers."""
        if not self._running:
            return
        
        # Send sentinel to all subscriber queues
        for queue in self._subscribers:
            try:
                await asyncio.wait_for(
                    queue.put(None),  # type: ignore[arg-type]
                    timeout=1.0,
                )
            except (asyncio.TimeoutError, RuntimeError):
                pass
        
        self._running = False
        logger.info("Frame distributor stopped")
    
    async def push_frame(self, frame_data: bytes) -> bool:
        """Push a new frame to all subscribers.
        
        Args:
            frame_data: Raw frame bytes (JPEG or encoded H.264)
            
        Returns:
            True if broadcast successful, False if distributor stopped
        """
        if not self._running:
            return False
        
        # Broadcast to all active subscribers
        for i, queue in enumerate(self._subscribers):
            try:
                await asyncio.wait_for(
                    queue.put(frame_data),  # type: ignore[arg-type]
                    timeout=0.1,
                )
            except asyncio.TimeoutError:
                logger.debug(f"Subscriber {i} queue full — frame dropped")
            except RuntimeError:
                # Queue closed, remove subscriber
                self._subscribers.pop(i)
        
        self._total_frames_broadcast += 1
        return True
    
    async def subscribe(self) -> tuple[asyncio.Queue, int]:
        """Create a new subscriber queue.
        
        Returns:
            Tuple of (queue, subscriber_id)
        """
        if len(self._subscribers) >= self._max_subscribers:
            raise RuntimeError(
                f"Maximum subscribers reached ({self._max_subscribers})"
            )
        
        queue: asyncio.Queue[bytes | None] = asyncio.Queue(maxsize=4)
        subscriber_id = len(self._subscribers)
        self._subscribers.append(queue)  # type: ignore[arg-type]
        
        logger.info(f"New subscriber connected (id={subscriber_id}, total={len(self._subscribers)})")
        return queue, subscriber_id
    
    async def unsubscribe(self, queue: asyncio.Queue) -> None:
        """Remove a subscriber."""
        if queue in self._subscribers:
            self._subscribers.remove(queue)
            logger.info(f"Subscriber disconnected (remaining={len(self._subscribers)})")
    
    def get_subscriber_count(self) -> int:
        """Get number of connected subscribers."""
        return len(self._subscribers)
    
    @property
    def stats(self) -> dict[str, int]:
        """Get distributor statistics."""
        return {
            "subscribers": len(self._subscribers),
            "total_broadcast": self._total_frames_broadcast,
        }
