"""Settings persistence for Sztreamerr.

Saves and loads camera/streaming configuration from JSON file.
Supports per-device defaults and runtime overrides.
"""

from __future__ import annotations

import json
import logging
import os
from dataclasses import asdict, field, replace
from pathlib import Path
from typing import Any, ClassVar

logger = logging.getLogger(__name__)


class SettingsPersistence:
    """Manages persistent camera and streaming settings.
    
    Loads from JSON file on init, saves automatically after changes.
    Supports runtime overrides that don't persist across restarts.
    """
    
    def __init__(self, config_path: str | None = None) -> None:
        self._config_dir = Path("~/.config/sztreamerr").expanduser()
        if not self._config_dir.exists():
            self._config_dir.mkdir(parents=True, exist_ok=True)
        
        self._config_path = (
            Path(config_path) if config_path else 
            self._config_dir / "settings.json"
        )
        
        # Default settings
        self._camera_config: dict[str, Any] = {
            "resolution_w": 1920,
            "resolution_h": 1080,
            "framerate": 30.0,
        }
        
        self._server_config: dict[str, Any] = {
            "host": "0.0.0.0",
            "port": 8080,
            "concurrent_limit": 16,
        }
        
        self._runtime_overrides: dict[str, Any] = {}
        
        # Load persisted settings
        self.load()
    
    def load(self) -> None:
        """Load settings from JSON file."""
        if not self._config_path.exists():
            logger.info("No existing config found — using defaults")
            return
        
        try:
            with open(self._config_path, 'r') as f:
                data = json.load(f)
            
            # Merge with defaults (file values override defaults)
            self._camera_config.update(data.get("camera", {}))
            self._server_config.update(data.get("server", {}))
            
            logger.info(
                f"Loaded settings from {self._config_path}: "
                f"{self._camera_config['resolution_w']}x{self._camera_config['resolution_h']} @ "
                f"{self._camera_config['framerate']:.1f}fps"
            )
        
        except json.JSONDecodeError as e:
            logger.error(f"Invalid JSON in config file: {e}")
        except Exception as e:
            logger.warning(f"Failed to load settings from {self._config_path}: {e}")
    
    def save(self) -> None:
        """Save current settings to JSON file."""
        try:
            # Merge runtime overrides
            merged_camera = {**self._camera_config, **self._runtime_overrides.get("camera", {})}
            merged_server = {**self._server_config, **self._runtime_overrides.get("server", {})}
            
            data = {
                "camera": merged_camera,
                "server": merged_server,
            }
            
            with open(self._config_path, 'w') as f:
                json.dump(data, f, indent=2)
            
            logger.info(f"Settings saved to {self._config_path}")
        except Exception as e:
            logger.error(f"Failed to save settings: {e}")
    
    def get_camera_config(self) -> dict[str, Any]:
        """Get camera configuration."""
        merged = {**self._camera_config, **self._runtime_overrides.get("camera", {})}
        return merged
    
    def get_server_config(self) -> dict[str, Any]:
        """Get server configuration."""
        merged = {**self._server_config, **self._runtime_overrides.get("server", {})}
        return merged
    
    def set_camera_setting(self, key: str, value: Any) -> None:
        """Set a camera setting (persisted)."""
        self._camera_config[key] = value
        self.save()
    
    def set_server_setting(self, key: str, value: Any) -> None:
        """Set a server setting (persisted)."""
        self._server_config[key] = value
        self.save()
    
    def override_camera_setting(self, key: str, value: Any) -> None:
        """Override a camera setting at runtime (not persisted)."""
        if "camera" not in self._runtime_overrides:
            self._runtime_overrides["camera"] = {}
        self._runtime_overrides["camera"][key] = value
    
    def override_server_setting(self, key: str, value: Any) -> None:
        """Override a server setting at runtime (not persisted)."""
        if "server" not in self._runtime_overrides:
            self._runtime_overrides["server"] = {}
        self._runtime_overrides["server"][key] = value
    
    def reset_runtime_overrides(self) -> None:
        """Reset all runtime overrides."""
        self._runtime_overrides.clear()
        logger.info("Runtime overrides cleared")
    
    @property
    def config_path(self) -> Path:
        """Get the configuration file path."""
        return self._config_path
