# Security camera — automatyczne usuwanie i retencja

Status: **zaplanowane, nie zaimplementowane.** Powstało 2026-09-27 podczas
debugowania HLS. Poniżej decyzje projektowe; implementacja osobnym commitem.

## Gdzie zapisywać — ustalone 2026-09-27

**`/sdcard/Android/media/com.ocubea/klipy/`** przez `context.getExternalMediaDir("klipy")`.

Nie `Pictures/OcuBea/`, nie `Movies/OcuBea/`, nie `getExternalFilesDir()`:

| Miejsce | Problem |
|---|---|
| `/sdcard/Pictures/...` | **Permission denied** — potwierdzone na telefonie. Wymaga `WRITE_EXTERNAL_STORAGE`, a na API 33 to `neverForLocation` i użytkownik musi ręcznie przyznać, a po odinstalowaniu i tak zniknie. |
| `/sdcard/Movies/...` | To samo, tylko przez `MediaStore`. Na API 33 własne pliki wstawia się bez uprawnienia, ale trzeba pamiętać o `IS_PENDING` i zawiera dodatkową maszynerię. |
| `getExternalFilesDir()` | **Już używane** w `StreamService.kt:90` i `StreamServer.kt:860` dla `recordings`. Od Androida 11 katalog `Android/data/` **nie jest widoczny w galerii ani w większości menedżerów plików** — klipy są, ale użytkownik ich nie znajdzie. |
| `getExternalMediaDir()` | Bez uprawnień, działa od API 21, **indeksowany przez MediaStore** i widoczny w galerii jako folder „OcuBea". Zero dodatkowego kodu. |

Fallback przy `null` (brak pamięci zewnętrznej): `filesDir/media/klipy` — wtedy
klipy znikają przy odinstalowaniu, więc `status.json` musi to zgłaszać, żeby
użytkownik nie szukał ich bez powodu.

### Konsolidacja

`recordings` w `Android/data/` przenosimy do `Android/media/com.ocubea/klipy/`,
żeby nie było dwóch katalogów na jeden cel. Logi (`CrashLogger`) zostają w
`getExternalFilesDir()` — nie są dla użytkownika.

Ścieżkę ustawia **jedna** funkcja, nie litery w trzech miejscach:

```kotlin
// media/ClipStorage.kt
object ClipStorage {
    fun root(context: Context): File {
        val dir = context.getExternalMediaDir("klipy")
            ?: File(context.filesDir, "media/klipy")
        dir.mkdirs()
        return dir
    }
}
```

### Nazwy plików

`klip_2026-09-27_14-31-08.mp4` — prefiks zgodny z katalogiem, czas lokalny.
Sortowanie po nazwie działa, bo timestamp jest zawsze tej samej długości.

## Co użytkownik opisał

System automatycznego usuwania klipów oparty o trzy niezależne kryteria:

1. **Miejsce zajęte** — twardy limit, klipy kasowane od najstarszych.
2. **Wykrywanie ruchu** — zapis tylko wtedy, gdy w kadrze jest ruch.
3. **Wiek klipów** — retencja czasowa, niezależna od zajętości.

## Ustalenia

### Nazewnictwo
`Klipy`, nie `nagrania`. `kamera_YYYY-MM-DD_HH-mm-ss.mp4` w katalogu
`/sdcard/OcuBea/clips/`. Bez `v2`, `test`, `nowy` — nazwa ma opisywać
zawartość, nie historię zmian.

### Kryteria są AND, nie OR
Trzy niezależne reguły w jednej funkcji `shouldDelete(klip)`:

```kotlin
data class ClipInfo(
    val path: File,
    val startedAtMs: Long,
    val durationMs: Long,
    val bytes: Long,
    val motion: Boolean,     // czy w klipe był ruch
)
```

Kasujemy gdy **którykolwiek** warunek jest prawdziwy:
- `bytes` w sumie przekroczyło limit (najstarsze pierwsze)
- `startedAtMs` starsze niż retencja
- brak ruchu i klip nie ma jeszcze minimalnego czasu

Trzeci warunek jest najmniej oczywisty: **brak ruchu nie znaczy „natychmiast
usuń”**. Krótki klip bez ruchu jest szumem, ale długi klip „bez ruchu” może
zawierać zdarzenie, którego detektor nie zobaczył. Proponuję minimalne okno
(np. 10 s) po którym klip bez ruchu odchodzi, konfigurowalne.

### Domyślne wartości (do potwierdzenia)
| Parametr | Domyślnie | Uwagi |
|---|---|---|
| Limit miejsca | 4 GB | `StatFs` na `/sdcard` — procent jest zgubiony przy reserved |
| Retencja | 7 dni | |
| Minimalna długość klipu | 10 s | krótsze odrzucamy przy zapisie |
| Interwał detektora | 500 ms | nie co klatkę, to za drogie |
| Próg ruchu | do zmierzenia | patrz niżej |
| Rotacja pliku | co 60 s albo 32 MB | |

### Miejsce na dysku nie jest znane z góry
`File("/sdcard/OcuBea/clips").usableSpace` — nie liczymy procentem z
`getFreeSpace()`, bo partycja ma rezerwę systemową i limit procentowy daje
mylący wynik.

### Detekcja ruchu
Najtańsza sensowna metoda na CPU: downscalowana do 32×18 grayscale, porównanie
z poprzednią klatką, `abs(a-b) > 8`, liczba zmienionych pikseli > 1% z
progu. Zero zależności, ~0.2 ms na klatkę. MotionBoost MLKit byłby dokładniejszy
ale wnosi zależność i model do APK — nie warto dla progu, który i tak trzeba
stroić per scena.

Bufor detektora to kolejka o stałej pojemności (RingBuffer), żeby nie trzymać
pełnych klatek. Zapis startuje po N kolejnych klatkach z ruchem (np. 2 s), żeby
nie łapać pojedynczych szpilek.

### Format klipów
Konsistentny z resztą aplikacji: fMP4, ten sam hardwarowy enkoder H.264 co HLS,
ten sam `Fmp4Writer`. Klip = `init.mp4` + kolejne `moof+mdat`, konkatenowane
do `.mp4`. Reuse istniejącego muxer zamiast pisać drugi.

**Uwaga o `Duration`:** ręcznie pisany `mvhd` ma `duration = 0` (przepływ
fragmentowany, `mehd` w init). Niektóre odtwarzacze — w tym galeria MIUI —
otwierają plik i traktują `duration = 0` jako „plik niekompletny", pokazując
zero długości. Klipy dostaną prawdziwy `mvhd.duration` z licznika próbek, a HLS
zostawi jak jest (bo tam liczy go playlista).

### Rotacja
Plik rośnie, `moof` doklejany co 250 ms. Pilnować dwóch rzeczy:
- `tfdt` musi rosnąć monotonicznie wewnątrz pliku (rebase od 0 na początku klipu)
- przy zamknięciu dopisać `mfra`/`mfro`, żeby klip dał się przewijać

### Wątek
Detekcja, zapis i retencja w jednym wątku z `ExecutorService` — nie na
wątku kamery. Kamera ma priorytet i nie może czekać na dysk. Synchronizacja z
`FrameHub` przez istniejący mechanizm, nie przez dostep do `ImageProxy`
z innego wątku.

## Ryzyka

- **Zapis na dysk zabija fps.** Retencja I/O konkuruje z enkoderem. Test:
  `status.json.pipeline.dropped` przy aktywnym zapisie, porównane z bez.
- **Brak miejsca w trakcie zapisu** → `IOException` przy `flush`. Trzeba
  obsłużyć w `catch` i zamknąć klip, nie zabić wątku.
- **Zgubiony klip przy kill procesu** → plik bez `mfra`. Na starcie: usuń
  pliki bez `mfro` starsze niż 1 h.
- **Odmount karty SD** → te same `IOException`, plus `mkdirs` na starcie.

## Kolejność implementacji — stan na 2026-09-27

Wszystkie cztery pozycje są zrobione:

1. **Rotacja klipów + `ClipInfo` + retencja po czasie** — `ClipWriter`,
   `ClipStorage`, `ClipRetention`. Kryteria wieku, rozmiaru i liczby plików
   są niezależne (OR, nie AND): wystarczy przekroczyć jedno.
2. **Limit miejsca** — `clip_max_space_mb` w `OcuBeaConfig`.
3. **Ruch jako gate zapisu** — `StreamService` steruje `ClipWriter` przez
   `CameraManager.startClipRecording(sekundy, onDemand = false)`. Koniec
   klip po `MOTION_POST_FRAMES` nieruchomych klatek (30).
4. **Sprzątanie** — `mfra`/`mfro` świadomie pominięte: klipy są krótkie,
   `moof` na końcu daje pełną nawigację, a dopisywanie `mfra` po zamknięciu
   wymagałoby przepisania rozmiarów w `moov`. Pliki po crashu łapi `startup`
   w `ClipRetentionScheduler`.

### Prawdziwy czas trwania

`mvhd`, `tkhd` i `mdhd` są **zawsze** w wersji 1 z 64-bitowymi polami.
Wersja 0 ma `duration` 32-bitowe, czyli inną długość — a przy zamknięciu
klipu init segment jest przebudowywany w miejscu, więc oba muszą mieć
identyczny rozmiar. Wariantowanie według tego, czy plik jest otwarty,
uniemożliwiałoby tę operację: `rebuildInitWithDuration` po prostu zwracałby
`null` i czas trwania nigdy nie zostałby dopisany.

`durationUs <= 0` jest odrzucane — zamknięty plik z zerowym czasem to dokładnie
ten błąd, który naprawialiśmy.

Zmierzone na urządzeniu po 4-sekundowym nagraniu: `ffprobe` dał
`duration=3.265`, Chromium `video.duration=3.265` przy `readyState=4`.

### Brandy `ftyp`

`major_brand = isom`, `minor_version = 0x0200`, `compatible_brands = isom, iso2,
iso5, iso6, mp41, avc1, cmfc`.

To nie jest kosmetyka. Klip z samymi `iso6` i `cmfc` był **poprawny** — `ffprobe`
czytał go bez słowa, WebUI go odtwarzał, struktura pudełek była zgodna
(24-mołowy `moov` z `mvex`, `moof`/`mdat` co 250 ms) — a
`MediaMetadataRetriever` na Androidzie 13 odrzucał go z
`setDataSource failed: status = 0x80000000`. Najwęższy dekoder w stosie po
prostu nie rozpoznał pliku po zadeklarowanych brandach.

Nie da się tego wykryć bez telefonu: `ffprobe` patrzy na zawartość, a Stagefright
na deklarację.

### Zdarzenie: prawdziwy bug `tkhd`

Między `duration` a macierzą w `tkhd` jest `reserved(8)`, `layer(2)`,
`alternate_group(2)`, `volume(2)`, `reserved(2)` — czyli **16 bajtów**. Run
był pisany parami `int()`, czyli 4 bajty szeroko, więc wychodziło 24. Macierz
lądowała na offsecie 60 zamiast 52, `w/h` na 96 zamiast 88, a cały box miał
**112 bajtów zamiast 104**.

`ffprobe` tego nie widzi, bo bierze rozmiar z `stsd`, nie z `tkhd`. Plik
wyglądał poprawnie i był zły.

Wykrył to dopiero `testy JVM`: `assertEquals("tkhd body is 96 bytes", 96, size)`.
Offsety zweryfikowane cross-checkiem na pliku wyprodukowanym przez ffmpeg, gdzie
`tkhd` jest w wersji 0 i ta sama macierz zaczyna się na offsecie 40
(52 − 12, bo wersja 0 ma 4 bajty krótsze `creation`, `modification` i `duration`).

## Stan na 2026-09-28, po teście na Redmi Note 10 Pro (Android 13)

Sprawdzone **na urządzeniu**, nie wywnioskowane:

- `ClipActivity` otwiera się z `MainActivity`, siatka 2-kolumnowa renderuje klipy
  z datą i rozmiarem, nagłówek pokazuje `4 klip(y) · 16,8 MB · wolne 18,3 GB`
- `POST /clips/record?start&seconds=4` nagrywa, klip pojawia się na liście w
  trakcie nagrywania (polling co 4 s)
- `DELETE /clips/<nazwa>` kasuje pojedynczy klip — potwierdzone na 4 plikach
- `mvhd`/`tkhd`/`mdhd` w wersji 1, `tkhd` 96 bajtów ciała, macierz tożsamościowa
  na offsecie 52, `w/h = 1920×1080` jako 16.16
- `ffprobe` na nagranym z telefonu pliku: `duration=3.604925`, H.264 1920×1080
- `MediaMetadataRetriever` dekoduje klatkę: `frame=1920x1080`, miniatury w siatce
  pokazują realną treść (220 unikalnych poziomów jasności na kafelku, wobec 26
  dla gradientu tła)
- Stagefright **odrzuca** klipy zapisane przed poprawką brandów `ftyp` — dlatego
  cztery stare klipy zostały usunięte, a nie odtworzone

### Nadal niesprawdzone

- Odtwarzanie w natywnym `ExoPlayer` (kafelek otwiera odtwarzacz, ale nie
  obejrzałem klatki wideo — `PlayerView` i tak nie raportuje stanu do logu)
- Long-press → zaznaczanie → „USUŃ ZAZNACZONE"
- Retencja na urządzeniu (testy JVM pokrywają reguły, nie integrację z
  harmonogramem)
- Klip w galerii MIUI (katalog `Android/media/com.ocubea/klipy` jest poprawny,
  ale nie otwierałem go w galerii)
- Zachowanie termiczne przy dłuższym nagrywaniu

## Testy

`app/src/test/java/com/ocubea/stream/Fmp4WriterBoxTest.kt` — 14 testów JVM
bez telefonu. `Fmp4Writer` nie importuje nic z Androida, więc logika pudełek
da się sprawdzić na maszynie.

`app/src/test/java/com/ocubea/security/ClipRetentionTest.kt` — 9 testów na
prawdziwych plikach w katalogu tymczasowym. Retencja była zaplana jako
`ClipRetention.prune(context, …)`, czyli zależna od `Context` i niemożliwa do
przetestowania bez telefonu. Rozdzielono ją na `prune(context, …)`, które
wybiera pliki, oraz `plan(candidates, …)`, które podejmuje decyzję — druga
część nie widzi Androida i jest testowalna.

Testy pilnują rzeczy, których `ffprobe` nie zauważy: wersji `mvhd`, 64-bitowego
zaokrąglenia czasu, zgodności `mdhd` z `mvhd`, identycznego rozmiaru init
przed i po przebudowie, oraz poziomu AVC w `avcC`.

Retencja pilnuje, że limity są **OR-owane**, nie AND-owane, i że plik usunięty
przez wiek nie zostaje policzony drugi raz w gałęzi rozmiaru. Każdy limit ma
osobny test z pozostałymi na luzie — test, w którym limity na siebie wpadają,
przeszedłby nawet z martwą gałęzią.

Fixture to **prawdziwe** SPS/PPS wyciągnięte z klipu nagranego na urządzeniu
(`67 64 00 0a ac 1b …` / `68 ea 43 cb`), a nie syntetyczny. Zsyntetyczny
SPS wygląda sensownie, ale testuje założenia testu zamiast parsera.

Sama wersja poprzednia tego kodu **nie przechodziła** tych testów: `rebuild`
zwracał `null`, bo rozmiar różnił się o 8 bajtów na każdym z `mvhd`/`tkhd`.
Telefon pokazałby to jako „klip bez czasu trwania" dopiero po nagraniu —
test wykrył to natychmiast.

### Znane dziury

`sweepUnfinished()` jest napisany, ale **nigdzie nie jest wywoływany** i nie
powinien być, dopóki `ClipWriter` nie zapisze znacznika zamkniętego muxerа.
Sweep zgadywałby z `mtime` i kasował długie, dobre nagrania.

## Wydajność — pomiar na Redmi Note 10 Pro, 2026-09-28

Wszystkie liczby poniżej są zmierzone na urządzeniu, nie oszacowane. Pomiar
procesu: `top -b -n 1`, PSS: `dumpsys meminfo`, klatki: walidacja
multipart (SOI/EOI + zgodność `Content-Length`).

### Detekcja ruchu dekodowała pełną klatkę

`StreamService` dekodował **cały JPEG 1920×1080** (`BitmapFactory.decodeByteArray`),
a `MotionDetector.process` skalował go potem do siatki 32×24. Dwie najdroższe
operacje w aplikacji, żeby porównać kilkaset pikseli.

Naprawione: `MotionDetector.processJpeg()` dekoduje z `inSampleSize`, więc
klatka nigdy nie jest rozwijana do 2 Mpx. Bitmapa jest natychmiast
recyklingowana w `finally`, także gdy `inSampleSize` zwróciło 1.

| pomiar | przed | po |
|---|---|---|
| 1 widz MJPEG | **6 fps**, proces 97% CPU | **15 fps**, proces 25% CPU |
| PSS przy 1 widzu | 180 MB | 109 MB |
| PSS bez widza | 135 MB | 135 MB |
| fps przy 1/2/3 widzach | 6 | 15 / 15 / 15 |

Wątek `ocubea-analysis` przed zmianą zajmował **91,3% CPU** (8 rdzeni).

### Bitrate był wyprowadzany z rozmiaru obrazu

Oba enkodery (`HlsSession` i ścieżka klipów w `CameraManager`) miały
`bitrate = w * h * 4`, czyli 8,3 Mbps przy 1080p — **62,4 MB na nagraną
minutę**, czyli ~8,5 GB/h, bez żadnego ustawienia do zmniejszenia.

Teraz `video_bitrate_kbps`, sterowany jednym suwakiem jakości.

### Jeden suwak, dwa enkodery

`QualityScale` mapuje pozycję suwaka (SeekBar 0..60) na JPEG quality
(40..100) i bitrate (800..12000 kbps). Krzywa jest kwadratowa, bo enkoder
ściska dolny koniec znacznie mocniej niż górny.

Zmierzone na urządzeniu, z potwierdzonym `bitrate=` w logu enkodera:

| ustawione | HLS segment | klip |
|---|---|---|
| 800 kbps | 4,39 Mbps | 35,8 MB/min |
| 4 400 kbps | 6,00 Mbps | 40,6 MB/min |
| 12 000 kbps | 12,82 Mbps | 93,6 MB/min |

**Korekta wcześniejszego wniosku:** pisałem, że bitrate nie schodzi poniżej
~4,2–5 Mbps i że to „sufit enkodera". To było **nieprawidłowe** — pomiary
dotyczyły wyłącznie ścieżki klipowej przy bardzo niskich ustawieniach.
HLS honoruje ustawienie niemal liniowo: 12 000 kbps daje 12,82 Mbps. Dolny
koniec jest mocno ściśnięty (800 → 4,4), górny prawie liniowy.

Ścieżka klipowa jest mniej podatna: 800 kbps daje tam 35,8 MB/min przy
5,00 Mbps zmierzonych na pliku, wobec 4,39 Mbps dla HLS przy tym samym
ustawieniu. Ta rozbieżność jest **do zbadania**, nie do zgadywania.

### Fałszywy trop nr 2: „zapis się nie działa"

Pomiar HLS pokazał `bitrate=12000000` przy dwóch różnych ustawieniach, co
wyglądało jak ignorowanie ustawienia. Skrypt wysyłał POST i natychmiast
`force-stop` — `apply()` jest asynchroniczne, więc proces umierał w trakcie
zapisu. Po 4 s przerwy i potwierdzeniu zapisu przed restartem ustawienie
dochodzi. **Ta sama pułapka co wcześniej, w innym miejscu.**

### Fałszywy trop: „gęsty GOP blokuje VBR"

Próba dłuższego GOP dla klipów (`keyFrameIntervalSec = 1` zamiast IDR na
każdej klatce) **nie przyniosła poprawy**: 4,85 Mbps przy 2000 kbps, czyli
tyle samo. Hipoteza była zła, zmiana wycofana, a parametr zostawiony
w `H264Encoder` jako jawny i domyślnie 0. Prawdziwym powodem ściśnięcia
dolnego końca jest po prostu to, że VBR mocno kompresuje przy niskich
celach — nie architektura GOP.

### MJPEG kopiował każdą klatkę bajt po bajcie

`MultipartWriter.read()` przenosił JPEG pętlą po jednym bajcie, a `read()`
dla pojedynczego bajta alokował `ByteArray(1)` **przy każdym bajcie klatki**.
Przy ~87 KB na klatkę i 15 fps to miliony iteracji na sekundę na widza.
Zamienione na `System.arraycopy`; `ByteArray(1)` jest jeden na strumień.

Zweryfikowane walidatorem multipart: 3 kolejne klatki, każda z `SOI=ffd8`,
`EOI=ffd9` i dokładną zgodnością długości z `Content-Length`. Osobno na
pliku: 141 klatek, 141 `SOI`, 141 `EOI`, 141 boundary — zgodne.

`close()` ustawia teraz `closed`, więc `nextPart()` przerywa oczekiwanie
zamiast trzymać widza zalogowanego do pełnego `pollFrame` timeoutu.

### Fałszywy trop: „zapisy się gubią przy restarcie"

Pierwsza wersja pomiaru zgłosiła, że ustawienie bitrate nie przeżywa
`am force-stop`. Powtórzone z 3 s odstępem przed restartem: **3000 przeżywa
bez problemu**. `SharedPreferences.apply()` jest asynchroniczne, a `force-stop`
zabijał proces w trakcie zapisu. Aplikacja była poprawna — wadliwy był pomiar.
Nie naprawiono żadnego kodu, bo nie było czego.

Podobnie: `MultipartWriter` **nie** wyciekał widza — NanoHTTPD wywołuje
`close()`. Wyciek był zmyślony, poprawiony został tylko czas oczekiwania.

### Otwarte

- Ścieżka klipowa reaguje na bitrate słabiej niż HLS: 800 kbps daje 5,00 Mbps
  na pliku wobec 4,39 Mbps w HLS, a 12 000 daje 5,0 → 13,1. Powód nieznany.
- HLS: 71 segmentów / 30 s przy celu ~120 (`TARGET_SEGMENT_MS = 250`).
  Stabilny, ale rotacja nie osiąga celu.
- Android 6 (docelowy telefon) nie był testowany: USB nie jest przekazane do
  kontenera, a ten telefon nie wspiera wireless debugging.

### HLS: playlista kłamała o długości segmentów

`HlsSession.playlist()` pisała `#EXTINF` ze stałego `segmentMs` (0,25 s),
ignorując `Segment.durationMs`, który muxer już znał. Muxer tnie segment
przy każdym IDR, a `KEY_I_FRAME_INTERVAL = 0` oznacza IDR na każdej klatce,
więc segmenty realnie trwają jedną klatkę.

Zmierzone na urządzeniu: playlista mówiła `0.25`, a `tfdt` kolejnych
segmentów różnił się o **176–185 ms**. Teraz `#EXTINF` pochodzi z muxerа,
a `#EXT-X-TARGETDURATION` jest liczone z najdłuższego wpisu.

### Uwaga: przecinek dziesiętny w `#EXTINF`

Pierwsza wersja poprawki użyła `"%.3f".format(...)`, co idzie przez locale.
Na telefonie to dało `#EXTINF:0,183,` — **przecinek jest nieprawidłowy w
HLS**. ffmpeg: `Cannot get correct #EXTINF value of segment ... set to
default value to 1ms`, czyli każdy segment dostawał 1 ms i strumień tracił
sync. Formatowanie jest teraz ręczne (`formatExtInf`), z testem
`HlsPlaylistFormatTest` przełączającym locale pl/DE/TR.

To był błąd ukryty: stare `segmentMs / 1000f` też szło przez locale, więc
playlista **nigdy** nie była poprawna pod tym względem.

### Ring trzyma tylko około 3,5 s wideo

`RING_SIZE = 20` przy ~185 ms na segment. Zrzucenie playlisty na dysk
i odtworzenie po chwili daje 404 na wszystkich segmentach — to nie jest bug,
to projekt. Do weryfikacji użyto skryptu podążającego za żywą playlistą:
**152 segmenty, wszystkie zaczynają się od `moof`**, struktura segmentu to
dokładnie `moof` + `mdat` (bez powtórzonego `ftyp`/`moov`), a ffmpeg
odtwarza H.264 1920×1080 bez błędów dekodowania.

### Wdrożone: profil segmentu jako jedna wartość

`HlsSession.segmentMs` był parametrem, którego nikt nie przekazywał — `CameraManager`
wołał konstruktor bez niego, więc zawsze obowiązywał default 250. A `Fmp4Writer`
dostał `targetSegmentMs` i używał go do cięcia. Dwa miejsca, jedna wartość,
ktoś musi pamiętać — i nikt nie pamiętał.

Teraz `HlsProfile` trzyma obie liczby razem, bo **nie są niezależne**: segment
może zacząć się tylko na IDR, więc GOP dłuższy niż segment oznacza, że
większość segmentów czeka na klatkę kluczową, której nie będzie. Playlista
obiecuje wtedy długości, które nigdy nie nadchodzą, i odtwarzacz się opróżnia.
Test `HlsProfileTest.gop never exceeds the segment it has to fit in` pilnuje
tej nierówności; przy złamanym klempie padają dwa testy.

| Profil | Segment | GOP | `sync` | `buffer` |
|---|---|---|---|---|
| `default` | 250 ms | co klatkę | 3 | 6 |
| `low` | 120 ms | co klatkę | 1 | 2 |
| `high` | 2000 ms | 1 s | 4 | 10 |

Przełącznik „Niskie opóźnienie" w WebUI dziś zmieniał **wyłącznie
hls.js po stronie klienta** — serwer ciął 250 ms niezależnie. Teraz
`/hls/profile?set=low|high|default` przestawia obie strony, a odtwarzacz
przyjmuje liczby z odpowiedzi serwera, więc to, co playlista realnie jest, i to,
co hls.js zakłada, nie rozjeżdżają się.

### Zmierzone: `high` oszczędza CPU, nie bity

Oczekiwanie przy `high` brzmiało: 2 s segmentu na 1 s GOP = 2–3 klatki
międzykluczowe na segment, a klatki międzykluczowe są tanie, więc ta sama
jakość powinna kosztować mniej bitów na sekundę. **Nie potwierdziło się.**

Pomiar z enkodera (`bytes` w `/status.json`, okno 20 s, dwa odczyty
odejmowane), Redmi Note 10 Pro, 1080p:

| Profil | Bitrate | CPU procesu | Realny `EXTINF` |
|---|---|---|---|
| `low` | 5,83 / 5,68 Mbps | 131% / 124% | 0,186 / 0,183 s |
| `high` | 5,78 / 5,30 Mbps | 113% / 103% | 2,059 / 3,150 s |

Bitrate — ten sam, w granicach szumu. CPU — **13–18% mniej przy `high`**, co
powtarza się w obu powtórzeniach i w obie kolejności. Korzyść jest realna, ale
nie ta, o której pisałem w komentarzu: to mniej pracy, nie mniej przepływu.

Dlaczego GOP nie obniża bitrate: `c2.mtk.avc.encoder` zgłasza
`max input interval 204ms` i przy ~5 fps dostaje klatkę co ~185 ms, czyli
bliżej niż własny interwał. Enkoder i tak nie może czekać, więc
`KEY_I_FRAME_INTERVAL = 1` ma w tym urządzeniu niewiele do zrobienia.
To ta sama pułapka co przy klipach, gdzie 1-sekundowy GOP też nic nie zmienił.

Do udokumentowania, nie do naprawy: `high` to **zamiana opóźnienia na CPU**, nie
na bitrate. Jeśli telefon się grzeje, `high` jest właściwym wyborem; jeśli
liczy się przepływ, nie ma tu czego wybierać.

## Język interfejsu

Aplikacja ma dwa języki. Angielski jest domyślny i jest tym, co zobaczysz przy
braku tłumaczenia — `values/strings.xml` to kompletny interfejs, nie tylko
etykiety. Polski leży w `values-pl/strings.xml` i ma dokładnie tę samą listę
81 nazw.

WebUI serwowane z telefonu **nie zna ustawień języka telefonu** — może je
otworzyć dowolna przeglądarka w sieci. Dlatego samo wybiera język po stronie
klienta: z `localStorage`, inaczej z `?lang=` w adresie, inaczej z
`navigator.language`. `document.documentElement.lang` dostaje wybrany kod, więc
CSS i skrypt widzą, w czym są.

### Napisy składane w JavaScript nie wracają do tłumaczenia

`translatePage()` chodzi po węzłach tekstowych co 3 s. Każdy przycisk, który
sam przestawia własną etykietę, omija to całkowicie — i wraca do angielskiego
przy pierwszym kliknięciu. Dotyczyło to `bMode` i `bLL`, które składały napis
przez konkatenację.

Zasada: **napisu, który JS ustawia przez `textContent`, nie składa się
konkatenacją.** Wybiera się klucz, a tłumaczenie robi `t()`:

```js
this.textContent = t(lowLatency ? 'Low latency: on' : 'Low latency: off');
```

Ten sam wyścig dotyczył dwóch timerów: `refresh()` i `translatePage()` obie co
3 s przestawiały status, więc przycisk streamu raz na jakiś czas wracał do
angielskiego zależnie od kolejności w kolejce. Dlatego te napisy tłumaczy
`refresh()`, a nie `translatePage()`.

### `strings.xml` nie zgłasza brakujących tłumaczeń

Android podmienia `values/`, gdy w `values-pl/` brakuje nazwy — bez błędu, bez
ostrzeżenia. Polski użytkownik po prostu przeczyta angielski. Dlatego
`tools/strings_verify.js` porównuje oba pliki mechanicznie: brak nazwy,
zdublowana nazwa, zmieniony `%1$s` i napis identyczny z angielskim (chyba że
wyszczególniony z powodem).

## Weryfikatory WebUI

Cztery skrypty w `tools/`, każdy z kodem wyjścia różnym od zera — są w CI
między testami jednostkowymi a zapisem APK. Trzy z nich renderują WebUI w
Chromium, bo napisu spoza DOM-u nie da się sprawdzić testem JVM.

| Skrypt | Co sprawdza | Dlaczego nie da się tego zrobić testem JVM |
|---|---|---|
| `strings_verify.js` | kompletność `values-pl/`, `%s`, duplikaty | brak tłumaczenia nie jest błędem Androida |
| `i18n_verify.js` | każdy widoczny napis ma polski odpowiednik | napis spoza DOM-u nie istnieje bez renderu |
| `layout_verify.js` | 5 szerokości × 2 języki, brak uciętych etykiet | polskie słowo bywa dłuższe niż angielskie |
| `interaction_verify.js` | etykieta po kliknięciu zostaje w języku | kliknięcie to jedyny sposób, żeby to sprawdzić |

**Porównywanie zamiast zgadywania.** Pierwsza wersja `i18n_verify.js`
rozpoznawała „wygląda po angielsku" heurystyką i zgłaszała polskie `Klatki`,
`Plik`, `Obraz` — słowa bez znaków diakrytycznych — jako brakujące tłumaczenia.
Test oceniał sam siebie i przechodził, nie tłumacząc nic. Teraz renderuje stronę
dwa razy i porównuje: linia jest nietłumaczona dokładnie wtedy, gdy oba
rendery zwróciły ten sam tekst.

**Test, który nie potrafi złapać regresji, nie jest testem.** Każdy z czterech
sprawdzany jest przez usunięcie jednego elementu i potwierdzenie, że
kończy się błędem — nie przez spojrzenie na zielony exit.

## Kryterium sukcesu

Test: nagraj 3 minuty z ruchem, potem godzinę bez. Sprawdź na urządzeniu:
- liczba plików i łączny rozmiar mieszczą się w limitach
- klip z ruchem zawiera ruch, klip bez ruchu nie powstał
- `status.json` pokazuje `dropped` nie większe niż bez zapisu
- po `force-stop` i restarcie nie zostają śmieci
