# 🤖 Sztreamerr — Budowanie APK

## Problem z Buildozerem na tym środowisku

Buildozer wymaga pełnej konfiguracji Android SDK, która jest trudna do ustawienia w tym kontenerze. Poniżej znajdują się alternatywne metody budowania APK.

---

## Metoda 1: Użycie Kivy z Buildozer (zalecane)

### Wymagania

```bash
sudo apt-get install openjdk-17-jdk unzip python3-pip
pip install buildozer cython kivy[full]
```

### Konfiguracja Android SDK

```bash
export ANDROID_HOME=~/Android/Sdk
mkdir -p $ANDROID_HOME/cmd-tools/tools/bin
cd /tmp && wget https://dl.google.com/android/repository/commandlinetools-linux-6514223_latest.zip
unzip commandlinetools-linux-*.zip -d cmdtools
mv cmdtools/cmdline-tools $ANDROID_HOME/cmd-tools/
ln -s $ANDROID_HOME/cmd-tools/tools/bin/sdkmanager $ANDROID_HOME/tools/bin/sdkmanager
```

### Akceptacja licencji

```bash
echo "y" | $ANDROID_HOME/tools/bin/sdkmanager --sdk_root=$ANDROID_HOME --licenses
$ANDROID_HOME/tools/bin/sdkmanager --sdk_root=$ANDROID_HOME "platforms;android-34" "build-tools;37.0.0"
```

### Budowanie

```bash
cd /tmp/sztreamerr
buildozer android debug
# APK znajdzie się w: bin/*.apk
```

---

## Metoda 2: Użycie Kivy Direct (prostsze)

### Wymagania

```bash
pip install kivy[full]
```

### Konfiguracja

Utwórz plik `buildozer.spec` z minimalnymi ustawieniami:

```ini
[app]
title = Sztreamerr
package.name = sztreamerr
package.domain = org.michiumiu
source.dir = .
version = 0.3.0
orientation = landscape
fullscreen = 0
android.permissions = CAMERA,INTERNET,WAKE_LOCK
```

### Budowanie

```bash
cd /tmp/sztreamerr
buildozer android debug
```

---

## Metoda 3: Użycie GitHub Actions (automatyczne)

Github Actions jest już ustawione


---

## Struktura projektu Sztreamerr

```text
/tmp/sztreamerr/
├── src/                    # Kod źródłowy
│   ├── main.py             # Entry point + aiohttp server loop
│   ├── kivy_app.py         # Kivy UI wrapper
│   ├── capture/            # Backend kamery (V4L2/Videocapture)
│   │   └── android_camera.py  # Camera2 API via pyjnius
│   ├── encode/             # H.264/H.265 encoding pipeline
│   │   ├── engine.py       # H264Encoder (FFmpeg subprocess)
│   │   └── pipeline.py     # EncodingPipeline + frame queues
│   ├── stream/             # Streaming server (aiohttp + MJPEG)
│   │   ├── server.py       # StreamServer main class
│   │   ├── mjpeg.py        # MJPEG streaming with multi-viewer support
│   │   └── multi_viewer.py  # MultiViewerManager
│   ├── api/                # REST API endpoints
│   │   └── endpoints.py    # ApiEndpoints registry (10 endpoints)
│   └── core/
│       └── __init__.py     # Settings persistence
├── docs/
│   └── PLAN.md             # Implementation plan
├── buildozer.spec          # Build configuration
└── README.md               # Documentation
```

---

## Status implementacji (Phase 1 + Phase 2)

✅ **Phase 1 — Foundation**

- [x] Projekt scaffolding (pyproject.toml, src layout, config management)
- [x] Capture module: CameraBackend, VideoFrame, CameraInfo
- [x] FFmpeg subprocess capture backend (Linux V4L2 + Android fallback)
- [x] MJPEG streaming server with aiohttp and multi-viewer support
- [x] Web UI foundation
- [x] Camera lifecycle management (open/close safety, double-close protection)
- [x] VideoFrame frozen dataclass with auto-generated timestamps
- [x] FrameSource abstraction for stream generation

✅ **Phase 2 — H.264 Encoding + Multi-Viewer Streaming**

- [x] H264Encoder z continuous streaming via FFmpeg subprocess
- [x] EncodingPipeline z producer-consumer pattern i frame queues
- [x] MultiViewerManager z independent frame queue per viewer (max 16)
- [x] Zintegrowane w StreamServer._create_app() — nowy endpoint `/api/mjpeg`
- [x] API endpoints registry (10 endpointów do zdalnego sterowania)

---

## Next Steps (Phase 3)

🚧 **Android Packaging**

- [ ] Kivy wrapper for existing aiohttp server ✅ DONE
- [ ] Buildozer configuration for Android packaging ✅ DONE
- [ ] Android Camera2 API integration (replace V4L2) ✅ DONE
- [x] APK build and testing on emulator/device ⏳ IN PROGRESS

---

## Kontakt i wsparcie

Jeśli napotkasz problemy z budowaniem APK, sprawdź:

1. Czy wszystkie zależności są zainstalowane (Java 17+, unzip, pip)
2. Czy Android SDK jest poprawnie skonfigurowany
3. Czy plik `buildozer.spec` ma prawidłową strukturę

W razie problemów — skontaktuj się z zespołem deweloperskim.
