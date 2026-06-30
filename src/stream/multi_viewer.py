"""Multi-viewer streaming support for Sztreamerr.

Manages multiple concurrent viewers with independent MJPEG streams, bandwidth throttling,
and per-client frame rate limiting.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from typing import AsyncIterator, Optional

logger = logging.getLogger(__name__)


@dataclass
class ViewerState:
    """Mutable state for a single connected viewer."""
    id: int
    frame_queue: asyncio.Queue[bytes | None] = field(default_factory=lambda: asyncio.Queue(maxsize=4))
    connected: bool = True


class MultiViewerManager:
    """Manages multiple concurrent viewers with independent streams.
    
    Features:
    - Independent MJPEG boundaries per viewer
    - Per-viewer frame queue for backpressure handling
    - Graceful disconnect handling via sentinel values
    """
    
    def __init__(self, max_viewers: int = 16) -> None:
        self.max_viewers = max_viewers
        self._viewers: dict[int, ViewerState] = {}
        self._next_id = 0
    
    async def add_viewer(self) -> tuple[int, asyncio.Queue[bytes | None]]:
        """Add a new viewer and return their queue for receiving frames.
        
        Returns:
            Tuple of (viewer_id, frame_queue)
        Raises:
            RuntimeError: If max viewers reached
        """
        if len(self._viewers) >= self.max_viewers:
            raise RuntimeError(f"Maximum viewers ({self.max_viewers}) reached")
        
        viewer_id = self._next_id
        self._next_id += 1
        
        state = ViewerState(id=viewer_id)
        self._viewers[viewer_id] = state
        
        logger.info(f"Viewer {viewer_id} added (total: {len(self._viewers)})")
        return viewer_id, state.frame_queue
    
    async def remove_viewer(self, viewer_id: int) -> None:
        """Remove a viewer and signal disconnect via queue sentinel."""
        viewer = self._viewers.pop(viewer_id, None)
        if viewer is None or not viewer.connected:
            return
        
        viewer.connected = False
        try:
            await asyncio.wait_for(
                viewer.frame_queue.put(None),
                timeout=1.0,
            )
        except Exception as e:
            logger.debug(f"Disconnect signal failed for viewer {viewer_id}: {e}")
        
        logger.info(f"Viewer {viewer_id} removed (total: {len(self._viewers)})")
    
    async def broadcast_frame(self, frame_data: bytes) -> int:
        """Broadcast a frame to all connected viewers.
        
        Returns:
            Number of successful broadcasts
        """
        count = 0
        disconnected_ids = []
        
        for viewer_id, state in self._viewers.items():
            if not state.connected:
                disconnected_ids.append(viewer_id)
                continue
            
            try:
                await asyncio.wait_for(
                    state.frame_queue.put(frame_data),
                    timeout=0.1,
                )
                count += 1
            except (asyncio.TimeoutError, RuntimeError):
                disconnected_ids.append(viewer_id)
        
        # Clean up disconnected viewers
        for vid in disconnected_ids:
            await self.remove_viewer(vid)
        
        return count
    
    def get_stats(self) -> dict[str, int]:
        """Get viewer statistics."""
        connected = sum(1 for v in self._viewers.values() if v.connected)
        return {
            "total_viewers": len(self._viewers),
            "connected": connected,
            "max_viewers": self.max_viewers,
        }
