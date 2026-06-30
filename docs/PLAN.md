# Sztreamerr — Implementation Plan

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                    Sztreamerr App                     │
├─────────────────────────────────────────────────────┤
│  ┌──────────┐    ┌──────────┐    ┌──────────────┐  │
│  │ Capture  ├──►  │ Encode   ├──►  │   Stream     │  │
│  │ (FFmpeg) │    │  Module  │    │ (aiohttp)    │  │
│  └──────────┘    └──────────┘    └──────────────┘  │
│       ▲                    │             │           │
│       │               ┌─────────┐    ┌──────────┐   │
│       └──────────────  │ Web UI  ├───►│  API     │   │
│                        └─────────┘    └──────────┘   │
├─────────────────────────────────────────────────────┤
│  Platform Layer (Android / Linux / Windows)         │
│  - Briefcase (Kivy → Android APK + native apps)     │
└─────────────────────────────────────────────────────┘
```

#### 1. Capture Module (`src/capture/`)
- **FFmpeg subprocess** — `subprocess.run(['ffmpeg', '-f v4l2 -i /dev/video0'])` for cross-platform capture (Linux V4L2, Android Camera2 via FFmpeg wrapper). Lightweight — no OpenCV ML models bundled
- **Android fallback**: native Camera2 API via Java bridge (pyjnius) — planned but not yet implemented
- Frame format: raw frames passed to FFmpeg for encoding
- Backend abstraction: `src/capture/backend.py` ABC with `read_frame()` returning bytes + resolution metadata
- **CameraInfo** class for camera device enumeration and configuration
- **VideoFrame** frozen dataclass (`pixels`, `width`, `height`, auto-generated `timestamp_ms`) — represents a single captured frame
- `ffmpeg_capture.py` — FFmpeg subprocess implementation wrapping raw V4L2 → MJPEG output

### 2. Encode Module (`src/encode/`)
- FFmpeg bindings via `subprocess.run(['ffmpeg', ...], capture_output=True)` for encoding frames to H.264/H.265
- Codec selection by device capability (hardware vs software fallback)
- Two-pass where needed: raw snapshot → encoded stream

### 3. Stream Module (`src/stream/`)
- **Web server**: aiohttp — serves static HTML/CSS/JS frontend and video streams
- **Streaming protocol**: HTTP streaming with multipart/x-mixed-replace (MJPEG) for lowest latency
- **HLS support** (optional, future): ffmpeg live segmenter (~10-30s latency due to 2-6s segments + buffer; acceptable for security camera use, not suitable for robotics feed where MJPEG <200ms is needed)
- Connection management: concurrent viewer count limit, bandwidth throttling

### 4. Web UI (`src/ui/`)
- Single-page HTML/CSS/JS — camera preview + settings panel
- Controls: stream resolution toggle, codec selection, stream start/stop
- API endpoints for remote control (endpoint spec to be finalized by user after research)

## Implementation Phases

### Phase 1 — Foundation (Days 1–4) ✅
- [x] **1.1** Project scaffolding: `pyproject.toml`, src layout, config management (`src/core/__init__.py`)
- [x] **1.2** Capture module with FFmpeg subprocess backend + camera enumeration
- [x] **1.3** Static web UI (camera preview page)
- [x] **1.4** aiohttp server serving the UI and MJPEG stream endpoint

### Phase 2 — Core Features (Days 5–8) ✅
- [x] **2.1** FFmpeg H.264/H.265 encoding integration (`engine.py` + `pipeline.py`) — continuous streaming via subprocess
- [x] **2.2** Multi-viewer streaming support with concurrent connections (`multi_viewer.py`) — independent frame queues per viewer
- [x] **2.3** Settings persistence — per-device defaults, config file save/load (`settings.py`)
- [x] **2.4** Basic API endpoints for remote control (10 endpoints defined in `endpoints.py` registry)

### Phase 3 — Polish (Days 9–10) 🚧
- [ ] **3.1** Motion detection via background pyav frame analysis
- [ ] **3.2** Android packaging with Briefcase + buildozer
- [ ] **3.3** Desktop app builds (Linux AppImage, Windows installer)
- [ ] **3.4** HTTPS support with self-signed certificate generation

## Technology Choices

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Language | Python 3.10+ | User's existing expertise; mature ecosystem for all required capabilities |
| UI Framework | HTML5/CSS3/JS (web) + Kivy wrapper | Web UI is lightweight, responsive on any phone browser. Kivy only as native app shell (not heavy UI framework). User already uses Kivy in xTRAP so it's a familiar tool. |
| Camera Capture | pyav (FFmpeg Python bindings) + native fallbacks | Lightweight — no OpenCV ML models bundled. FFmpeg has hardware acceleration on Linux, Android, Windows |
| Web Server | aiohttp (async) | Low overhead, handles concurrent viewers efficiently |
| Android Deploy | buildozer (Kivy) → APK | One toolchain covers both desktop and mobile from one codebase |

## Key Risks & Mitigations

1. **pyav on Android**: pyav packages FFmpeg binaries — check if the bundled version includes Camera2 support or if we need a separate native camera module.
2. **Hardware acceleration varies by device**: Detect encoder availability at runtime; fall back to software encoding when needed (document in settings).
3. **MJPEG latency under load**: Implement connection pooling and bandwidth throttling per viewer.

## Dependencies (pyproject.toml)
```toml
[tool.poetry.dependencies]
python = ">=3.10,<4.0"
pyav = "^12.0.0"
aiohttp = "^3.9.0"
pydantic-settings = "^2.1.0"

[tool.poetry.group.dev.dependencies]
pytest = "^7.4.0"
ruff = "^0.1.6"

[project.optional-dependencies]
desktop = ["kivy", "briefcase"]
android = ["buildozer"]
```

## Source Layout
```
src/
├── main.py              # Entry point — app lifecycle + aiohttp server loop
├── core/__init__.py     # Settings, CameraConfig, ServerConfig (pydantic)
├── capture/
│   ├── __init__.py
│   ├── backend.py       # ABC for camera backends + VideoFrame frozen dataclass + CameraInfo
│   └── ffmpeg_capture.py  # FFmpeg subprocess implementation (V4L2 → MJPEG)
├── encode/
│   ├── __init__.py
│   ├── engine.py        # H.264/H.265 encoding via FFmpeg subprocess
│   └── pipeline.py      # Frame processing pipeline
├── stream/
│   ├── __init__.py
│   ├── server.py        # aiohttp HTTP/MJPEG streaming server + FrameSource
│   └── mjpeg.py         # MJPEG boundary/header generation utilities
├── api/
│   ├── __init__.py
│   ├── config.py        # API configuration endpoints
│   └── endpoints.py     # Camera control & status API endpoints
└── ui/
    ├── index.html       # Web UI (camera preview + settings)
    ├── style.css        # Stylesheet
    └── static/
        ├── app.js       # Main application logic
        └── feed.js      # MJPEG video feed handling
```