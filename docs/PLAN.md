# Sztreamerr — Implementation Plan

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                    Sztreamerr App                     │
├─────────────────────────────────────────────────────┤
│  ┌──────────┐    ┌──────────┐    ┌──────────────┐  │
│  │ Capture  ├──►  │ Encode   ├──►  │   Stream     │  │
│  │  Module  │    │  Module  │    │   Module     │  │
│  └──────────┘    └──────────┘    └──────────────┘  │
│       ▲                    │             │           │
│       │                    │             ▼           │
│       │               ┌─────────┐    ┌──────────┐   │
│       └──────────────  │ Web UI  ├───►│  API     │   │
│                        └─────────┘    └──────────┘   │
├─────────────────────────────────────────────────────┤
│  Platform Layer (Android / Linux / Windows)         │
│  - Briefcase (Kivy → Android APK + native apps)     │
└─────────────────────────────────────────────────────┘
```

## Module Design

### 1. Capture Module (`src/capture/`)
- **pyav (FFmpeg Python bindings)** — `av` package for camera capture and frame decoding. Lightweight, no ML overhead unlike OpenCV which bundles heavy vision models
- **Native fallback**: Android Camera2 API via Java bridge (pyjnius), Linux v4l2 via ctypes
- Frame format: raw frames passed to FFmpeg for encoding
- Backend abstraction: `src/capture/backend.py` ABC with `read_frame()` returning bytes + resolution metadata

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

### Phase 1 — Foundation (Days 1–4)
- [ ] **1.1** Project scaffolding: `pyproject.toml`, src layout, config management (`src/core/config.py`)
- [ ] **1.2** Capture module with pyav backend + camera enumeration
- [ ] **1.3** Static web UI (camera preview page)
- [ ] **1.4** aiohttp server serving the UI and MJPEG stream endpoint

### Phase 2 — Core Features (Days 5–8)
- [ ] **2.1** FFmpeg H.264/H.265 encoding integration
- [ ] **2.2** Multi-viewer streaming support with concurrent connections
- [ ] **2.3** Settings management: persist preferences, per-device defaults
- [ ] **2.4** Basic API endpoints for remote control

### Phase 3 — Polish (Days 9–10)
- [ ] **3.1** Motion detection via background pyav frame analysis
- [ ] **3.2** Android packaging with Briefcase + buildozer
- [ ] **3.3** Desktop app builds (Linux AppImage, Windows installer)

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
4. **OpenMeow confusion**: Removed all references — OpenMeow is a LEGO robotics framework unrelated to this project.

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
├── main.py              # Entry point — app lifecycle (Kivy) + aioserver loop
├── config.py            # App configuration (pydantic-settings)
├── capture/
│   ├── __init__.py
│   └── backend.py       # ABC for camera backends
├── encode/
│   ├── __init__.py
│   └── ffmpeg.py        # FFmpeg encoding pipeline
├── stream/
│   ├── __init__.py
│   └── server.py        # aiohttp HTTP/MJPEG streaming server
├── ui/
│   ├── html/index.html  # Web UI (camera preview + settings)
│   ├── static/style.css
│   └── components.js
└── api/
    └── endpoints.py     # Control API endpoints
```