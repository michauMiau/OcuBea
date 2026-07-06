# Plan Implementacji Sztreamerr v0.3.1

## ✅ Zrealizowane w v0.3.0-v0.3.1
- [x] MJPEG streaming przez HTTP (/stream)
- [x] Multi-viewer support (FrameDistributor)
- [x] Status API (/api/status, /status.json, /health)
- [x] Kivy UI z przyciskami Start/Stop
- [x] Logowanie do HOME zamiast /sdcard/ (fix PermissionError)
- [x] Inicjalizacja _last_frame przed capture loop
- [x] Naprawiony HTML na stronie głównej (/)

## 🎯 Brakujące funkcje do implementacji

### 1. Camera2 API Integration (PRIORYTET: HIGH)
**Status:** RealCameraInput dodany, ale wymaga testów

**Implementacja:**
- [ ] Opcjonalna obsługa Camera2 API przez pyjnius
- [ ] Fallback do TestBarGenerator gdy kamera niedostępna
- [ ] Konfiguracja rozdzielczości i FPS z UI
- [ ] Zarządzanie uprawnieniami kamery (Android 6.0+)

**Kod:** `RealCameraInput` klasa w main.py (już dodana)

### 2. Optymalizacja Latencji (PRIORYTET: MEDIUM)
**Cel:** <100ms latency jak w README

**Plan:**
- [ ] Zmniejszenie rozmiaru klatek do 320x240 dla testów
- [ ] Opcja `--low-latency` w CLI
- [ ] Testy z curl -N na live streamie
- [ ] Monitorowanie time-to-first-byte

### 3. Status API Enhancements (PRIORYTET: MEDIUM)
**Dodatkowe metryki:**
- [ ] Average FPS actual vs target
- [ ] Memory usage (Android specific)
- [ ] Camera status (active/idle/error)
- [ ] Uptime tracking z restart count

### 4. UI Improvements (PRIORYTET: LOW)
**Funkcjonalność:**
- [ ] Pokaż IP telefonu na ekranie
- [ ] Timer streamingu w UI
- [ ] Opcja auto-start po uruchomieniu
- [ ] Tryb fullscreen dla podglądu

### 5. Dokumentacja i Konfiguracja (PRIORYTET: LOW)
**Zadania:**
- [ ] README.md z opisem Camera2 API
- [ ] Przykładowe curl commands w docs/
- [ ] Konfiguracja przez env vars (już częściowo zrobione)

## 🧪 Testy przed release v0.3.1

### Unit Tests
- [ ] FrameDistributor thread safety
- [ ] _build_minimal_jpeg valid JPEG output
- [ ] HTTP handler all endpoints

### Integration Tests
- [ ] MJPEG stream przez curl -N
- [ ] Multi-viewer (2+ subskrybenci jednocześnie)
- [ ] Camera2 API na urządzeniu testowym (Xiaomi 21061119DG)
- [ ] Performance: latency <100ms przy 30 FPS

### Android Tests
- [ ] Instalacja przez ADB
- [ ] Logi w logcat po uruchomieniu
- [ ] Brak crashy po 5 minut streamingu
- [ ] Memory leak check (Android Studio Profiler)

## 📊 Metryki sukcesu v0.3.1
- Latencja <100ms przy 720p@30fps
- Zero crashy podczas testów integration
- Kamera działa na Androidzie 8+ (API 26+)
- Multi-viewer obsługuje >=5 klientów jednocześnie

## 🔄 Następne kroki po ukończeniu buildu #60
1. Pobierz artefakt z GitHub Actions
2. Zainstaluj na telefonie testowym
3. Uruchom stream i sprawdź latency
4. Test Camera2 API (jeśli dostępna)
5. Fix any issues found during testing
6. Release v0.3.1 tag

## 📝 Notes
- Camera2 API wymaga pyjnius — może nie być dostępne w Chaquopy
- TestBarGenerator fallback ensures app works even without camera
- FrameDistributor obsługuje max 5 subskrybentów (konfigurowalne)
- Logging do HOME zamiast /sdcard/ fixuje PermissionError na Androidzie
