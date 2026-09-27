# Zarządzanie klipami — API, WebUI i aplikacja

Status: **zaplanowane.** Powstało 2026-09-27 na żądanie: „daj do aplikacji i
webui jakieś zarządzanie tymi klipami, odtwarzanie, nagrywanie ondemand i itd".

## Co już istnieje (nie pisać drugiego systemu)

| Element | Gdzie | Stan |
|---|---|---|
| `MotionDetector` 32×24 | `security/SecurityCamera.kt:15` | działa, próg z czułości |
| `MotionRecorder` | `security/SecurityCamera.kt:90` | pre-buffer + post-motion, ale zapisuje **AVI** przez `AviWriter` |
| `AviWriter` (MJPEG w AVI) | `security/AviWriter.kt` | 146 linii, nie fMP4 |
| `/recordings` GET/DELETE | `server/StreamServer.kt:825` | listuje, kasuje, serwuje `video/x-msvideo` |
| `/startvideo` `/stopvideo` | `StreamServer.kt:80-81` | stuby, nie nagrywają niczego |
| `/list_videos` | `StreamServer.kt:82` | istnieje, trzeba sprawdzić czy nie koliduje |
| pola config | `model/OcuBeaConfig.kt:62-78` | `securityEnabled`, `motionSensitivity`, `motionRecord`, `preRecordSeconds`, `maxClipSeconds` |

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

## Kolejność

1. `ClipStorage` + `Fmp4Writer` jako backend klipu (bez UI) — największa wartość
2. `/clips` API z `Range`
3. WebUI: lista + odtwarzanie + usuwanie
4. Nagrywanie ondemand
5. Retencja: czas → miejsce → ruch
6. Natywny ekran w aplikacji

## Kryterium sukcesu

* `curl -r 1000-2000 /clips/klip_x.mp4` → `206` i dokładnie 1001 B
* klip otwiera się w przeglądarce z paskiem czasu i przewijaniem
* `POST /clips/record {"seconds":5}` → plik po 5 s, `/clips/recording` pokazuje `active:false`
* klip 60 s zajmuje < 20 MB
* `status.json.pipeline.dropped` nie rośnie przy aktywnym zapisie
