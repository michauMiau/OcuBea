# Sztreamerr v0.3.0

**Lightweight IP Camera Streamer for Android**

Sztreamerr streamuje wideo z kamery telefonu jako MJPEG (Motion JPEG) przez HTTP — idealne do podglądu na żywo w przeglądarce lub odtwarzaczu sieciowym.

## ✨ Features

- 📹 **MJPEG streaming** — strumieniowanie wideo z kamery w formacie MJPEG
- 🌐 **HTTP server** — dostęp przez przeglądarkę lub dowolny klient sieciowy
- 📱 **Android-first** — zoptymalizowane pod Androida (stdlib, brak zależności zewnętrznych)
- ⚡ **Niskie opóźnienie** — <100ms latency dzięki direct socket writes
- 🔒 **Brak zależności** — tylko Python standard library (`http.server`, `threading`)
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

## 🏗️ Architektura

```
┌───────────────┐     ┌──────────────────┐     ┌──────────────┐
│   Kamera      │────▶│  MJPEG Generator │────▶│  HTTP Server │
│  (Android)    │     │  (MjpegStream)   │     │  (stdlib)    │
└───────────────┘     └──────────────────┘     └──────────────┘
                                              │
                    ┌─────────────────────────┤
                    ▼                         ▼
            ┌───────────────┐        ┌───────────────┐
            │  Subskrybenci │        │   UI (HTML)   │
            │  (/stream)    │        │   (/:8080/)   │
            └───────────────┘        └───────────────┘
```

### Kluczowe moduły:
- **`src/main.py`** — Entry point: Kivy App + stdlib HTTPServer
- **`MjpegStream`** — Generowanie klatek MJPEG z cache i broadcastem
- **`SztreamerrHandler`** — Obsługa żądań HTTP (/, /stream, /api/status)
- **`SztreamerrApp`** — Interfejs Kivy (UI start/stop/streaming status)

## 📡 API Endpoints

| Endpoint | Opis |
|----------|------|
| `GET /` | Strona główna z podglądem wideo |
| `GET /stream` | Stream MJPEG (multipart/x-mixed-replace) |
| `GET /api/status` | Status JSON: wersja, rozdzielczość, subskrybenci |

### Przykład użycia `/stream`:
```bash
curl -N http://<telefon>:8080/stream -o frame.jpg
```

## 🛠️ Development

### Local testing (desktop)
```bash
cd src/
python main.py  # Startuje na porcie 8080
```

### Build lokalnie (Buildozer)
```bash
buildozer android debug
# APK w: bin/sztreamerr-*.apk
```

## ⚙️ Configuration

### buildozer.spec
```ini
requirements = python3,kivy,pyjnius,jnius,sdl2,pillow,requests
p4a.bootstrap = sdl2
android.ndk = 25b
android.minapi = 26
```

### Opcje streamingu (w kodzie)
- `CAMERA_W` / `CAMERA_H` — rozdzielczość klatek (domyślnie 1280×720)
- `FRAME_RATE` — FPS (domyślnie 30)
- `MAX_CONNECTIONS` — max subskrybenci (domyślnie 5)

## 🐛 Known Issues

### Android NDK r25b compatibility
- Biblioteki CPython extensions (`aiohttp`, `pydantic`) nie kompilują się z NDK r25b
- **Solution**: Użycie stdlib `http.server` zamiast aiohttp

## 📄 License
MIT — see LICENSE file for details.

---
*Zbudowane przez Garfi 🐾 z pomocą Michała (michauMiau)*