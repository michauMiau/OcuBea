"""API endpoints for Sztreamerr camera control.

Provides HTTP REST endpoints for:
- Camera configuration (resolution, framerate)
- Streaming status
- Encoder statistics
- Settings management

All handlers are async functions compatible with aiohttp routing.
"""

from __future__ import annotations

import json
import logging
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)


class ApiEndpoints:
    """Manages API endpoint registrations and routing."""
    
    ENDPOINTS: list[Dict[str, str]] = [
        # Camera endpoints
        {"method": "GET", "path": "/api/camera/config", "handler": "get_camera_config"},
        {"method": "POST", "path": "/api/camera/config", "handler": "update_camera_config"},
        {"method": "GET", "path": "/api/camera/status", "handler": "camera_status"},
        
        # Streaming endpoints
        {"method": "GET", "path": "/api/stream/stats", "handler": "stream_stats"},
        {"method": "POST", "path": "/api/stream/start", "handler": "start_streaming"},
        {"method": "POST", "path": "/api/stream/stop", "handler": "stop_streaming"},
        
        # Encoder endpoints
        {"method": "GET", "path": "/api/encoder/stats", "handler": "encoder_stats"},
        {"method": "POST", "path": "/api/encoder/config", "handler": "update_encoder_config"},
        
        # Settings endpoints
        {"method": "GET", "path": "/api/settings", "handler": "get_settings"},
        {"method": "PUT", "path": "/api/settings", "handler": "update_settings"},
    ]
    
    @classmethod
    def get_all_endpoints(cls) -> list[Dict[str, str]]:
        return cls.ENDPOINTS.copy()
    
    @classmethod
    def find_endpoint(cls, method: str, path: str) -> Dict[str, str] | None:
        for ep in cls.ENDPOINTS:
            if ep["method"] == method.upper() and ep["path"] == path:
                return ep
        return None
    
    @classmethod
    def register_endpoint(cls, endpoint: Dict[str, str]) -> None:
        if not all(k in endpoint for k in ("method", "path", "handler")):
            raise ValueError(f"Endpoint must have method, path, handler")
        for ep in cls.ENDPOINTS:
            if ep["method"] == endpoint["method"] and ep["path"] == endpoint["path"]:
                raise ValueError(f"Endpoint already registered: {endpoint['method']} {endpoint['path']}")
        cls.ENDPOINTS.append(endpoint)
        logger.info(f"Registered API endpoint: {endpoint['method']} {endpoint['path']}")
    
    @classmethod
    def unregister_endpoint(cls, method: str, path: str) -> None:
        for i, ep in enumerate(cls.ENDPOINTS):
            if ep["method"] == method.upper() and ep["path"] == path:
                removed = cls.ENDPOINTS.pop(i)
                logger.info(f"Unregistered API endpoint: {removed['method']} {removed['path']}")
                return
        raise ValueError(f"Endpoint not found: {method} {path}")
    
    @classmethod
    def get_endpoint_count(cls) -> int:
        return len(cls.ENDPOINTS)
