# Sztreamerr v0.3.x

**Lightweight IP Camera Streamer for Android**

Sztreamerr streamuje wideo z kamery telefonu jako MJPEG (Motion JPEG) przez HTTP — idealne do podglądu na żywo w przeglądarce lub odtwarzaczu sieciowym.

## ✨ Features

- 📹 **MJPEG streaming** — strumieniowanie wideo z kamery w formacie MJPEG
- 🌐 **HTTP server** — dostęp przez przeglądarkę (`/`) lub `/stream` endpoint
- 📱 **Android-first** — zoptymalizowane pod Androida (stdlib, brak zależności zewnętrznych)
- ⚡ **Niskie opóźnienie** — <100ms latency dzięki direct socket writes
- 🔒 **HTTPS support** — samopodpisany certyfikat TLS generowany automatycznie przy starcie
- 🎯 **Motion detection** — wykrywanie ruchu w tle (background frame differencing)
- 📊 **Status API** — endpoint `/api/status` z metrykami streamingu

## 🚀 Quick Start

### Build APK (GitHub Actions)
1. Push commit na gałąź `main` lub `master`
2. Workflow automatycznie buduje APK
3. Pobierz artefakt z sekcji **Actions** → najnowszy run → **Artifacts**

### Instalacja
```bash
adb install sztreamerr-*.apk
```

### Uruchomienie
1. Otwórz aplikację Sztreamerr na telefonie
2. Kliknij **Start Streaming**
3. Połącz się przez przeglądarkę: `http://<adres-ip>:8080` lub `/stream`
4. (HTTPS) — jeśli certyfikat został wygenerowany, spróbuj `https://`

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────┐
│                    main.py                          │
├─────────────────────────────────────────────────────┤
│  ┌──────────────┐    ┌──────────────┐              │
│  │ RealCamera   ├───▶│ FrameDistri- │              │
│  │ Input        │    │ butor        │              │
│  │ (pyjnius)    │    │ (thread-safe)│              │
│  └──────────────┘    └──────┬───────┘              │
│                              │ broadcast            │
│  ┌──────────────┐            ▼                      │
│  │ Motion       │   ┌─────────────────┐           │
│  │ Detector     │   │ MJPEGHandler    │           │
│  │ (frame diff) │   │ ThreadingHTTP-  │           │
│  └──────────────┘   │ Server          │           │
│                      ├─────────────────┤            │
│                      │ /             │              │
│                      │ /stream       │              │
│                      │ /api/status   │              │
│                      └─────────────────┘            │
├─────────────────────────────────────────────────────┤
│  ssl_helper.py — self-signed cert generation        │
└─────────────────────────────────────────────────────┘
```

### Source Layout (v0.3.x)

| File | Purpose | Status |
|------|---------|--------|
| `src/main.py` | **Entry point** — Camera2 + MJPEG + HTTP server + motion detection | ✅ Production code (~884 lines, single file) |
| `src/ssl_helper.py` | Self-signed cert generation for HTTPS | ✅ Standalone module |
| `src/detection/motion.py` | MotionDetector with pixel differencing | ✅ Optional background analysis |
| `src/core/settings.py` | Settings persistence (camera resolution, server port) | ✅ Used by main.py |
| `capture/android_camera.py` | Camera2 wrapper stub (NOT used — real impl is in main.py) | ⚠️ Dead code / reference |
| `stream/server.py` | aiohttp server (legacy — NOT used) | ❌ Dead code |
| `encode/engine.py`, `pipeline.py` | FFmpeg H.264 encoder (planned, NOT implemented) | ❌ Stub only |
| `kivy_app.py` | Kivy UI wrapper (references old architecture) | ❌ Broken / outdated |

### Key Classes in `main.py`

- **`RealCameraInput`** — Android Camera2 API wrapper via pyjnius (line 203)
  - Captures JPEG frames from camera hardware with hardware acceleration
  - Uses `ImageReader` target for low-latency capture
- **`FrameDistributor`** — Thread-safe frame broadcaster (line 54)
  - Per-viewer queues with stale-cleanup (drops frames older than N seconds)
  - Limits pending frames to 5 per viewer to prevent memory overflow
- **`MJPEGHandler`** — stdlib HTTP request handler (line 473)
  - Implements `/`, `/stream`, `/api/status` endpoints
  - Uses `ThreadingHTTPServer` for concurrent connections
- **`TestBarGenerator`** — Pattern generator for testing without camera (line 161)

## 📡 API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | HTML page with embedded MJPEG stream preview |
| `/stream` | GET | Raw MJPEG stream (`multipart/x-mixed-replace`) — works in browser `<img>` tag |
| `/api/status` | GET | JSON status: `{"version": "0.3.x", "resolution_w": 1280, "resolution_h": 720, "subscribers": N}` |

### Example usage:
```bash
# View stream in browser
open http://<telefon>:8080/

# Direct MJPEG feed (works in <img src="...">)
open http://<telefon>:8080/stream

# Check status
curl -s http://<telefon>:8080/api/status | python3 -m json.tool
```

## 🔒 HTTPS Setup

HTTPS is **automatic** — `ssl_helper.py` generates a self-signed certificate at first startup:

1. App starts → checks for existing `cert.pem` / `key.pem` in working directory
2. If missing → generates 2048-bit RSA key + self-signed cert (valid 365 days)
3. Server wraps socket with SSL context if certificates are available
4. On subsequent runs, reuses existing certificate

> ⚠️ Self-signed certs show browser warnings — fine for LAN use but not production.

## 🎯 Motion Detection

Motion detection runs in a background thread alongside capture:

1. Each captured frame is passed to `MotionDetector.analyze_frame()`
2. Simple pixel differencing compares against previous frame
3. When motion detected → logs event (threshold configurable)
4. Falls back gracefully if module fails to import

Configuration defaults: `threshold=50, area_threshold=200`

## 🛠️ Development

### Local testing (desktop — with test pattern generator)
```bash
cd /Sztreamerr/src/
python main.py  # Startuje na porcie 8080 z testowym wzorem (bez kamery)
```

### Build APK (Buildozer + Chaquopy)
```bash
# From project root:
buildozer android debug
# APK w: bin/sztreamerr-*.apk
```

**Requirements:**
- Python 3.10+
- pyjnius (via Chaquopy on Android — no manual install needed)
- PIL/Pillow for YUV→JPEG conversion (optional, graceful fallback)

### Configuration (in code constants)
| Constant | Default | Description |
|----------|---------|-------------|
| `HOST` | `"0.0.0.0"` | HTTP bind address |
| `PORT` | `8080` | HTTP port |
| `CAMERA_WIDTH` | `1280` | Camera capture width |
| `CAMERA_HEIGHT` | `720` | Camera capture height |
| `MAX_SUBSCRIBERS` | `5` | Max concurrent MJPEG viewers |

## ⚠️ Architecture Notes

### Single-file design decision
The entire application lives in `src/main.py` (~884 lines). This was a deliberate choice:
- **Chaquopy compatibility** — fewer import paths to worry about on Android
- **No aiohttp needed** — stdlib `http.server` works perfectly and avoids C-extension build issues with NDK r25b
- **Faster iteration** — one file to edit, compile-check, and deploy

### What's NOT implemented (but has stubs)
- ❌ FFmpeg H.264/H.265 encoding (`encode/` directory)
- ❌ Kivy UI (`kivy_app.py` references old architecture)
- ❌ Desktop builds (AppImage / Windows exe scripts)
- ❌ Separate Camera backend module (`capture/android_camera.py` is a reference stub)

## 🐛 Known Issues

### ADB keepalive required
After screen off, phone exits WiFi ADB sleep mode. Requires manual re-enable:
1. Open **Settings → Developer Options**
2. Toggle **Wireless Debugging** off/on (or tap the device entry to reconnect)

### Camera crash fix applied
Fixed pyjnius `CameraDevice` class hierarchy mismatch in `_CameraStateCb.onOpened()`.

## 📄 License
MIT — see LICENSE file for details.

---
*Zbudowane przez Garfi 🐾 z pomocą Michała (michauMiau)*
