# Zarządzanie klipami — API, WebUI i aplikacja

Status: **częściowo wdrożone.** Powstało 2026-09-27 na żądanie: „daj do aplikacji i
webui jakieś zarządzanie tymi klipami, odtwarzanie, nagrywanie ondemand i itd".
Nagłówek orzekał „zaplanowane" jeszcze po wdrożeniu pierwszych punktów —
poniższa tabela jest stanem faktycznym, zweryfikowanym w kodzie, nie planem.

## Co już istnieje (nie pisać drugiego systemu)

Poniższa tabela to **stan wyjściowy z 2026-09-27**, sprzed zamiany `AviWriter`
na fMP4. Została tu jako punkt odniesienia („co było, zanim zrobiliśmy to
po raz drugi") — nie opisuje bieżącego stanu. Aktualny stan jest w sekcji
„Kolejność" na końcu dokumentu; klipy fMP4 i retencja są wdrożone.

| Element | Gdzie | Stan wtedy |
|---|---|---|
| `MotionDetector` 32×24 | `security/SecurityCamera.kt:16` | działa, próg z czułości |
| `MotionRecorder` | `security/SecurityCamera.kt:140` | pre-buffer + post-motion, zapisywał **AVI** przez `AviWriter` |
| `AviWriter` (MJPEG w AVI) | `security/AviWriter.kt` | 184 linie, nie fMP4 — wciąż używany przez `/recordings` |
| `/recordings` GET/DELETE | `server/StreamServer.kt:175` (handler `:936`) | listuje, kasuje, serwuje `video/x-msvideo` |
| `/startvideo` `/stopvideo` | `StreamServer.kt:130-131` | uzbraja nagrywanie ruchu; plik otwiera dopiero przy zdarzeniu ruchu (`handleStartVideo`, `StreamServer.kt:1165`) |
| `/list_videos` | `StreamServer.kt:132` | istnieje, nie koliduje z `/clips` |
| pola config | `model/OcuBeaConfig.kt:105-121` | `securityEnabled`, `motionSensitivity`, `motionRecord`, `preRecordSeconds`, `maxClipSeconds` |

**Wniosek:** detektor ruchu i maszyna stanowa nagrywania są gotowe. Brakuje
zapisu w dobrym formacie, retencji i UI. Najmniejsza poprawka to **podmiana
`AviWriter` na istniejący `Fmp4Writer`** — nie nowy enkoder, nie nowy muxer.

## Decyzje

### Format
fMP4 tym samym `Fmp4Writer`, co HLS. Klip = `init.mp4` + `moof+mdat...`.
Powód: `AviWriter` to MJPEG w kontenerze AVI, czyli 60 klatek × 40 KB = 2.4 MB
za sekundę wideo. fMP4 z H.264 ma ~200 kB/s. To 12× mniej miejsca na dysku,
a `recordings` i tak trzeba przenieść.

### Endpointy — nowe, nie kolidujące z IP Webcam

    GET    /clips                  lista JSON (nazwa, rozmiar, czas, długość, ruch)
    GET    /clips/<nazwa>         strumień z obsługą Range (przewijanie!)
    GET    /clips/<nazwa>/download  Content-Disposition
    DELETE /clips/<nazwa>         usuń jeden
    POST   /clips/delete           {"names":[...]}  usuń wiele
    POST   /clips/clear           usuń wszystkie
    POST   /clips/record          {"seconds":30} nagrywanie ondemand
    POST   /clips/record/stop     zakończ ondemand
    GET    /clips/recording        stan nagrywania + zapisane bajty

Trzymamy też `/recordings` działające — to kompatybilność IP Webcam, nie
ruszamy. Nowe `/clips` daje więcej.

### `Range` jest obowiązkowy
Bez `Accept-Ranges: bytes` przeglądarka nie przewija `<video>` i nie pokaże
paski czasu. NanoHTTPD ma wbudowany `ChunkedInputStream`, ale `Range` trzeba
obsłużyć ręcznie: `newFixedLengthResponse` nie obsługuje 206. Trzeba napisać
własny `Response` z `Status.PARTIAL_CONTENT`.

### Bezpieczeństwo ścieżki
Istniejący kod ma dziurę: `handleRecordings` sprawdza `rest.endsWith(".avi")`
i `contains("..")`, ale `File(dir, rest).canonicalPath.startsWith(dir.canonicalPath)`
działa dopiero **po** `file.exists()`. Nowy kod musi sprawdzić kanonizację
przed istnieniem i dopuścić tylko `klip_*.mp4` (nie `.mp4` — nazwa może
zostać ręcznie zmieniona w menedżerze plików, a `..` wystarczy).

### WebUI — zakładka „Klipy"
Sekcja w `index.html` (499 linii, więc wyciągnąć do osobnego `clips.html`):

- siatka kafelków z miniaturką (`<img src="/clips/<n>/thumb">` — klatka z
  `moof`, dekodowana po stronie serwera raz na klip i cache'owana)
- klik → modal z `<video controls>` + play/pause + „pobierz"
- przycisk „Nagrywaj 30 s" z odliczaniem
- checkboxy przy kafelkach + „Usuń zaznaczone"
- „Wyczyść wszystko" z potwierdzeniem
- auto-odświeżanie listy co 5 s tylko gdy zakładka widoczna (`document.hidden`)

### Aplikacja — to samo w native
`MainActivity` dostaje zakładkę „Klipy" z tym samym `RecyclerView` co reszta,
miniaturką dekodowaną przez `MediaMetadataRetriever` (nie własny dekoder),
odtwarzacz `VideoView` + `MediaController`. Usuwanie przez `AlertDialog`
z potwierdzeniem.

## Pułapki

* **Zapis nie może być na wątku kamery.** `ImageAnalysis` ma priorytet;
  `flush()` na dysku zabija fps. Osobny `ExecutorService`, kolejka
  `ArrayDeque` z limitem, `drop` przy przepełnieniu — nie blokować.
* **Zapis + HLS + JPEG to trzy konsumenci jednej klatki.** Trzeba policzyć,
  czy realnie się da. Priorytet: HLS > klip > JPEG? Albo klip tylko gdy HLS
  nieaktywny. Zmierzyć, nie zgadywać.
* **Odmount karty SD** → `IOException` w `flush`. `catch`, zamknąć klip,
  nie zabić wątku. Zgłosić w `status.json`.
* **`duration` w `mvhd`** = 0 przy fMP4. Galeria MIUI pokaże „0:00".
  Uzupełnić prawdziwą wartością przy zamykaniu klipu (opisane w
  `SECURITY_CAMERA.md`).
* **Retencja nie może kasować aktywnego klipu.** Sprawdzać `activeFile`.
* **Klipy znikają przy odinstalowaniu** tylko w fallbacku `filesDir` —
  `status.json` ma to raportować.

## Kolejność — stan na 2026-09-27

1. `ClipStorage` + `Fmp4Writer` jako backend klipu — **zrobione**
2. `/clips` API z `Range` — **zrobione**, `206` potwierdzone na urządzeniu
3. WebUI: lista + odtwarzanie + usuwanie — **zrobione i przetestowane
   w Chromium** (siatka, miniatura `readyState=4`, modal, download, usuwanie)
4. Nagrywanie ondemand — **zrobione**, zamknięcie po `seconds` potwierdzone
5. Retencja: czas → miejsce → liczba plików — **zrobione**, kryteria
   niezależne, `ClipRetentionScheduler` sprząta automatycznie
6. Natywny ekran w aplikacji — **zrobione i sprawdzone na urządzeniu**
   (2026-09-28, Redmi Note 10 Pro / Android 13: siatka renderuje klipy z datą
   i rozmiarem, `DELETE /clips/<nazwa>` działa, `MediaMetadataRetriever`
   dekoduje klatkę, miniatury pokazują realną treść).

## Natywny ekran — co jest i czego nie wiadomo

`ClipActivity` (siatka 2-kol., swipe-refresh, polling 4 s, ExoPlayer przez
`127.0.0.1`), `ClipAdapter` (long-press → zaznaczanie, `notifyDataSetChanged`),
`ClipApi` (OkHttp, ten sam endpoint co WebUI).

Celowo jedna ścieżka odczytu plików: odtwarzacz w aplikacji idzie przez HTTP
Range, dokładnie tak jak przeglądarka. Wada: dwie ścieżki można naprawić
rozłącznie.

**Sprawdzone na urządzeniu 2026-09-28:** siatka rysuje się, przycisk „Klipy"
działa, `DELETE /clips/<nazwa>` kasuje, `MediaMetadataRetriever` dekoduje klatkę
(`frame=1920x1080`), a miniatury w siatce pokazują realną treść.
**Nadal niesprawdzone:** samo odtworzenie w natywnym odtwarzaczu (odtwarzac
w aplikacji idzie przez HTTP Range, dokładnie tak jak przeglądarka) oraz
long-press → zaznaczanie → „USUŃ ZAZNACZONE".

## Kryterium sukcesu

* `curl -r 1000-2000 /clips/klip_x.mp4` → `206` i dokładnie 1001 B
  — **spełnione**
* klip otwiera się w przeglądarce z paskiem czasu i przewijaniem
  — **spełnione**, `readyState=4`
* `POST /clips/record seconds=5` → plik po 5 s — **spełnione**
* klip 60 s zajmuje < 20 MB — 4 s = 3,7 MB, czyli ~55 MB/min, **nie spełnione**
  przy 1080p; jakość/bitrate do obniżenia
* `status.json.pipeline.dropped` nie rośnie przy aktywnym zapisie
  — **niezmierzone**
* natywny ekran — **częściowo zmierzone** (siatka, usuwanie, miniatury tak;
  samo odtwarzanie i long-press nie)

Uwaga do kryteriów powyżej: dwa z nich („`duration` w `mvhd` = 0" w sekcji
Pułapki oraz „klip 60 s zajmuje < 20 MB") dotyczą stanu z 2026-09-27.
`duration` **zostało naprawione** — przy zamykaniu klipu init jest przebudowywany
z prawdziwym `mvhd.duration` (patrz `SECURITY_CAMERA.md`, sekcja „Prawdziwy czas
trwania"), potwierdzone `ffprobe` i Chromium. Rozmiar klipu nadal przekracza
limit: zmierzone 55 MB/min przy 1080p.
