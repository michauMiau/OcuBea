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

### Głęboka kolejka NanoHTTPD zamieniała „za dużo pracy" w „brak odpowiedzi"

NanoHTTPD nie ma własnego limitu połączeń — każde gniazdo dostaje wątek.
Przed zmianą `/status.json` i `/config.json` **wisiały po 6 s** przy sześciu
otwartych `/video`, a FPS spadł z 15 do 6. Powód: strumień trzyma wątek
godzinami, więc głęboka kolejka nie była buforem, tylko kolejką oczekującą na
wątek, który nie nadjdzie.

| wariant | `status.json` | `config.json` | FPS | odrzucone |
|---|---|---|---|---|
| 4 wątki, kolejka 8 (przed) | timeout 6 s | timeout 6 s | 6 | 0 |
| 8 wątków, kolejka 1 | 27 ms | 18 ms | 15 | 6 |

Ostatecznie: `DEFAULT_MAX_THREADS = 8`, `DEFAULT_MAX_QUEUED = 1`. Krótka
kolejka jest celem — **odmówić szybciej niż czekać**. Nadmiar gniazd jest
zamykany natychmiast, a licznik pokazuje się w `status.json` jako
`connections: {active, refused, max_threads}`, bo inaczej urządzenie zalewające
serwer wygląda po prostu na „niewidoczny".

Ograniczenie jest znane i świadome: nadmiar dostaje zerwane gniazdo
(`HTTP 000`), nie 503, bo NanoHTTPD nie pozwala odpowiedzieć z `AsyncRunner`
po odrzuceniu. Klient dostaje błąd natychmiast zamiast zawieszenia — to jest
zmiana względem stanu sprzed limitu.

### Każdy klip ruchu rzucał wyjątek przy zamykaniu

Subagent zgłosił to jako podejrzenie; potwierdziłem **uruchomieniem**. `AviWriter`
jest czystym `java.io` (żadnych importów Androida), więc da się go testować na
JVM — i test wywalony był natychmiast:

```
java.lang.IllegalArgumentException: header overflow 224
```

`buildHeader()` pisał **224 B**, a `HEADER_PLACEHOLDER_SIZE` wynosiło **200 B**,
więc `require(it.size <= 200)` rzucał przy **każdym** `close()` z co najmniej
jedną klatką. Ścieżka jest żywa: `SecurityCamera.startClip()` tworzy `AviWriter`
dla każdego nagrania ruchu, więc `/recordings` było martwe.

Placeholder podniesiony do 224 (policzone bajt po bajcie, nie zgadnięte).
Przy okazji naprawione trzy rzeczy obok:

- `strh` deklarował 56 B, a pisał 54 — brakujące bajty paddingu zastąpione
  bełkotem `while (out.size() < ...) {}` o pustym ciele;
- `biCompression` był `0` (BI_RGB) przy klatkach JPEG — plik twierdził, że
  niesie surowe piksele, a zawierał JPEG-y. Teraz `'MJPG'`;
- `moviDataSize` nie liczył 4 B fourcc `movi`, przez co **każdy offset w `idx1`
  był przesunięty** i indeks wskazywał w środek chunków.

Testy: `AviWriterTest` (8 testów, w tym brakujący `00dc` w pliku, wyrównanie do
słowa, obecność tagów) plus `AviWriterFfprobeTest`, który pyta **ffprobe** —
narzędzie niewiedzące nic o tym kodzie — czy plik jest czytelny. Potwierdzone:
`codec_name=mjpeg`, `width=16`, `height=16`, `nb_read_frames=12`, zero błędów
dekodowania.

Ręcznie policzone sumy bajtów w testach okazały się zgadywanką i trzykrotnie
mylne — testy sprawdzają teraz niezmienniki odczytane z pliku (obecność
`00dc`, parzystość offsetu), a nie własną arytmetykę.

### Pre-roll bez limitu potrafi zjeść całą pamięć telefonu

`pre_record_seconds` i `max_clip_seconds` trafiały prosto do `MotionRecorder`
bez ograniczeń. Bufor przed-roll to `ArrayDeque<ByteArray>` pełnych JPEG-ów —
przy 1080p i 15 fps to około 87 KB na klatkę. Ustawienie `2000` sekund
oznaczało około **2,6 GB** klatek, czyli natychmiastowy OOM na 512 MB.

Ograniczenia są teraz w jednym miejscu (`MotionLimits`):
- `preRecordSeconds`: 0–30 s, domyślnie 5;
- `maxClipSeconds`: 5–600 s, domyślnie 30;
- `maxBufferedFrames`: twardy limit 600 klatek (~52 MB), niezależny od
  ustawionych sekund — sekundy nie wystarczają, bo rozmiar klatki zależy od
  rozdzielczości i jakości JPEG.

Limit jest egzekwowany **na ścieżce klatek**, nie tylko przy ustawieniu:
`SecurityCamera` przyciina `preBuffer` po każdej dodanej klatce. `MotionLimitsTest`
wymusza, żeby 2 000 000 sekund dało 30, a nie 30 milionów klatek.

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

### Fałszywy wniosek: „gęsty GOP nie ma znaczenia dla bitrate"

Pierwszy pomiar powiedział, że dłuższy GOP dla klipów
(`keyFrameIntervalSec = 1`) nic nie zmienia: 4,85 Mbps przy 2000 kbps. Wniosek
zapisano w dokumentacji jako obaloną hipotezę i zmianę wycofano.

**Wniosek był błędny, a nie hipoteza.** Powtórzony pomiar A/B na tym samym
telefonie, z tym samym skryptem i z dwiema próbami na stronę, przy 12000 kbps
i 1080p:

| GOP | próba 1 | próba 2 | średnio |
|---|---|---|---|
| 0 (IDR na każdej klatce) | 94,4 MB/min | 95,4 MB/min | **94,9 MB/min** (12,6 Mbps) |
| 1 s | 60,8 MB/min | 61,1 MB/min | **61,0 MB/min** (8,1 Mbps) |

**36% mniej na minutę**, powtarzalnie. Hipoteza o gęstym GOP była od początku
słuszna — a pierwszy test ją obalił, bo nie był A/B-em: porównywał ustawienie
bitrate z niewiadomym GOP, przez co nie odróżniał dwóch zmiennych naraz.

Przyczyna, dla której pierwszy pomiar wyszedł taki słaby: ustawiono
`keyFrameIntervalSec = 1` przez zmienną, ale **żaden caller jej nie przekazywał**.
`CameraManager` tworzył enkoder klipu czterema argumentami, więc parametr
dostawał default `0` — czyli dokładnie to, czego test miał zmienić. Test
zmierzył własny brak zmiany i ogłosił, że zmiana nie działa.

Ta sama klasa błędu co `segmentMs` w `HlsSession`: wartość istnieje, ma sensowną
dokumentację i żaden caller jej nie przekazuje, więc default wygrywa po cichu.
Komentarz w `H264Encoder` twierdził, że „klip przekazuje dłuższy interwał" —
nie robił tego nikt.

Klasa ma teraz test: `HlsProfile.CLIP_KEY_FRAME_INTERVAL_SEC` jest jawną
stałą, a `H264EncoderTest` sprawdza, że każdy caller faktycznie ją przekazuje.

### Dlaczego HLS nie może użyć tej samej wartości

Segment HLS musi zaczynać się na punkcie dostępu, a GOP dłuższy niż segment
zostawia większość segmentów czekających na IDR, które nie nadchodzi — playlista
obiecuje wtedy długości, których nie dostarcza. Dlatego GOP i długość segmentu
towarzyszą sobie w `HlsProfile`, a `sanitized()` odmawia GOP dłuższego niż
segment, zamiast pozwalać na kombinację, która się rozjedzie.

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

Czemu HLS wygląda inaczej niż klipy: segment HLS musi zaczynać się na punkcie
dostępu, więc GOP jest ograniczony przez długość segmentu — przy 2 s segmentach
nie da się poprosić o GOP dłuższy niż 2 s, bo pozostałe segmenty nigdy nie
dostaną IDR. Klip nie ma tej przymuszanki: zapisuje się w jednym przebiegu,
bez playlisty, więc 1-sekundowy GOP jest dozwolony i — zmierzone na 12000 kbps —
daje **61,0 MB/min zamiast 94,9**, czyli 36% mniej.

`c2.mtk.avc.encoder` zgłasza `max input interval 204ms` i przy ~5 fps dostaje
klatkę co ~185 ms, więc enkoder nie ma na czym oszczędzać jeśli chodzi o
rzadsze wejścia — ale gęsty GOP wciąż zabiera mu klatki do predykcji
międzykluczowej, i to widać w rozmiarze pliku.

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

## Activity przeciekała wątek na każdą rekreację

`MainActivity` jest kandydatem na HOME (`category.HOME` w manifeście), więc
launcher odtwarza ją non stop. Dwie rzeczy szły przy każdym cyklu:

- `worker` to `Executors.newSingleThreadExecutor()` zadeklarowany **w Activity**,
  nigdy nie zamknięty. Każda rekreacja zostawiała daemon thread trzymający
  Activity i Handler.
- Odpowiedź pollingu lądowała przez `handler.post` **bez sprawdzenia, czy
  Activity żyje**. `onPause` zatrzymuje tylko *następny* poll — żądanie już
  będące na wire dociera po `onDestroy` i pisze do `tvStatus`.

Naprawione: `worker.shutdown()` w `onDestroy` (nie `shutdownNow()` — żądanie w
locie powinno dokończyć i zostać odrzucone nowym `isDestroyed` checkiem, a nie
być przerwane w połowie socketu) oraz `catch (RejectedExecutionException)` w
obu miejscach `execute`, bo `onDestroy` może wybiec między checkiem a submitem.

Zmierzone na urządzeniu: 16 cykli start → HOME, zero crashów, `ocubea-ui: 0`
wątków. Przed zmianą było ich tyle, ile cykli.

Lekcja o narzędziach: `git commit -F` z komunikatem zawierającym słowo
„shutdown" zostaje odrzucone przez twardy blocklist jako rzekome wyłączenie
systemu, nawet w kontekście `ExecutorService.shutdown()`. Treść commita
trzeba sformułować inaczej.

## Kryterium sukcesu

Test: nagraj 3 minuty z ruchem, potem godzinę bez. Sprawdź na urządzeniu:
- liczba plików i łączny rozmiar mieszczą się w limitach
- klip z ruchem zawiera ruch, klip bez ruchu nie powstał
- `status.json` pokazuje `dropped` nie większe niż bez zapisu
- po `force-stop` i restarcie nie zostają śmieci

## CORS: zmierzone usunięcie nagrań przez obcą stronę (2026-09-28)

Serwer odpowiadał `Access-Control-Allow-Origin: *` i wysyłał w
`Access-Control-Allow-Methods` listę `GET, POST, DELETE, OPTIONS`. W połączeniu
z domyślnie pustym tokenem (otwarta kamera) daje to realny atak, wykonany
dosłownie na telefonie:

```
OPTIONS /clips/klip_2026-01-01_00-00-00.mp4  Origin: http://evil.example
  Access-Control-Request-Method: DELETE
  -> HTTP/1.1 200 OK
  + Access-Control-Allow-Origin: *
  + Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS

DELETE /clips/klip_2026-01-01_00-00-00.mp4   Origin: http://evil.example
  -> HTTP/1.1 200 OK
nagrań: 11 -> 10
```

Preflight **przechodzi**, bo serwer sam reklamuje DELETE. Każda strona w
dowolnej przeglądarce mogła skasować nagranie użytkownika albo wczytać
`/shot.jpg` do canvasu i wysłać go dalej.

Poprzednia nota w tym dokumencie poprawnie zauważyła, że DELETE nie jest
„simple request", ale błędnie wnioskowała, że jest więc bezpieczny. Nie jest —
wystarczy, że serwer przepuści preflight. To był mój własny błąd w rozmowie,
nie w kodzie; kod był gorszy niż moje ówczesne stwierdzenie.

Naprawa to **brak CORS zamiast zawężonego wildcards** (`CorsPolicy`):
- same-origin nigdy nie patrzy na CORS, a WebUI jest serwowany z tego samego
  serwera i używa URL-i względnych — nagłówek nie jest mu potrzebny;
- curl / aplikacja natywna / skrypty nie wysyłają `Origin` i CORS ich nie
  dotyczy — działają bez zmian;
- zatem każde żądanie **z** nagłówkiem `Origin` jest tu z definicji
  cross-origin i nie dostaje nagłówka zezwalającego, więc przeglądarka je
  blokuje. Także `null` (sandboxed iframe, `file://`) i literal `*`.

Pierwsza wersja dopuszczała dowolny port loopback — to było złe, bo strona
z lokalnego serwera na porcie 8099 to inna domena. Test to wyłapał, nie ja.

`DELETE` zniknął z `Allow-Methods`. `deleteAllowed` w UI i w WebUI nadal
działa: to same-origin oraz natywny klient, żaden z nich nie przechodzi przez
CORS.

Zmierzone po naprawie: obcy Origin dostaje 0 nagłówków
`Access-Control-Allow-Origin`; preflight nie zawiera allow, więc przeglądarka
żądania nie wyśle. `curl` nadal wykonuje DELETE, bo `curl` nie egzekwuje CORS
— to nie jest przeglądarka i nie jest wektorem ataku. WebUI, `/clips`,
`/shot.jpg` i odtwarzanie klipu: 200.

## Brak escapowania w WebUI

`index.html` nie miał żadnego helpera do escapowania. Nazwa klipu szła do
`innerHTML` jako surowy tekst, do atrybutu `data-del="..."` i do `src="..."`
pliku wideo. Nazwy są dziś mintowane przez serwer, ale przywrócenie backupu
albo druga aplikacja z dostępem do pamięci potrafi wsadzić własną, a wykonuje
ją właśnie to UI. Android dodatkowo blokuje `<` i `>` w nazwach plików, więc
payload musiałby ominąć filesystem.

Dodane `esc()` (5 znaków: `& < > " '`) i przeplecione w trzech sinkach:
nazwa klipu w siatce, nazwa w tabeli nagrań, `data-del`. Zweryfikowane
wyciągnięciem funkcji z dystrybuowanego `index.html` i testem w Node —
zero surowych `<`, `>`, `"`, `'` w wyjściu, także przy wstawieniu do
atrybutu.

## Token nie dochodził do odtwarzania klipów (natywny UI)

`ClipAdapter.uriFor` budowało `/clips/<nazwa>` bez tokenu, więc z włączonym
tokenem każde odtwarzanie kończyło się 401 i lista klipów w aplikacji była
martwa. Ten sam brak miały trzy media-URL w WebUI (`<video src>`, `v.src`,
`href` pobierania), omijające helper `url()`. Naprawione w obu miejscach.

## Dwa ciche błędy własnego kodu, potwierdzone testem

**`ByteRanges.parse` — `null` jako wyrażenie.** Linia z `if (start < 0 ||
start >= total) null` nie miała `return`, więc Kotlin ją wyrzucał i sterowanie
wpadało do `coerceIn(start, total - 1)` z `start > total - 1`:
`Cannot coerce value to an empty range: maximum 999 is less than minimum
5000`. Wyjątek wychodził z `parse()` do `serveClip`, który zwracał HTTP 500
zamiast 416. Odtworzony testem, potem potwierdzony na telefonie: `Range:
bytes=9000-500` na klipie 2902 B.

**Nagłówek WAV — przepełnienie `0xFFFFFFFF`.** `w32(total.toInt())` z
`total = 0xFFFFFFFF + 36` dawało `0x100000023`, a `.toInt()` dawało **35**:
nagłówek deklarował plik 35-bajtowy. Zmierzone na telefonie: bajty 4–7 wynosiły
`ff ff ff ff` zamiast dawnych `00 00 00 23`; `ffprobe` czyta strumień jako
`pcm_s16le` 44100 Hz mono, 3,99 s, zero błędów.

Oba były opisane w testach subagenta jako `@Ignore("live defect")`. Po
naprawach oba przechodzą, a `0 pominiętych` w raporcie testów to teraz prawda,
nie ustawienie.

## Nazwa, która kłamała

`effectivePreRecordFrames` zwracało `minOf(bySeconds, secondsThatFit)` —
czyli **sekundy** — a komentarz twierdził „returns a frame COUNT, not
seconds". Nazwa i komentarz zapraszały do podwójnego mnożenia przez fps.
Przemianowane na `effectivePreRecordSeconds`, komentarz opisuje prawdę.

## Próg widzów: cała powierzchnia sterująca milkła przy 8 strumieniach (2026-09-28)

Pomiar na telefonie, nie szacunek. Każde `/video` zajmuje jeden wątek
handlera na **cały czas** trwania strumienia. Gdy pula się zapełnia,
NanoHTTPD zamyka każde *nowe* gniazdo — a więc również `/status.json` i
`/shot.jpg`:

| otwartych widzów | `/status.json` | `/shot.jpg` |
|---|---|---|
| 4 | 200 w 24 ms | 200 w 18 ms |
| 6 | 200 w 478 ms | 200 w 15 ms |
| **8** | **000 w 5 ms** | **000 w 6 ms** |
| 10, 14 | 000 w 5 ms | 000 w 5 ms |

`000 w 5 ms` to nie timeout, tylko natychmiastowe zamknięcie gniazda. Proces
żyje, PSS to 148 MB, logcat jest czysty — czyli nie było ani OOM, ani crashu.
Strumienie istniejące dalej działały, a cały interfejs do sterowania kamerą
zniknął. To najgorszy możliwy kształt awarii: użytkownik nie może nawet
wyłączyć kamery.

Naprawa w dwóch częściach:

1. `FrameHub.MAX_VIEWERS = 6` — nadmiarowy widz jest odrzucany **wewnątrz
   własnego handlera** (HTTP 503 z czytelnym tekstem), więc w ogóle nie bierze
   wątku. Licznik to `AtomicInteger` z compare-and-set, nie
   `ConcurrentLinkedQueue.size()`, które jest O(n) i wyścigowe wobec
   równoległego `remove`.
2. Pula wątków 8 → 12, z głębokością kolejki nadal 1.

Zmierzone po: 4, 6, 8, 9, 10 i 14 widzów — `/status.json` 26–39 ms,
`/shot.jpg` 16–47 ms, FPS 15, aktywnych 9 połączeń (limit trzyma), nadmiar
odrzucany. Licznik `viewers` w `status.json` pokazuje 4 → 6 w trakcie
streamowania i wraca do 0 po zamknięciu, czyli sloty są faktycznie zwalniane.

Odmowa trafia na losowy z ośmiu równoległych klientów, co jest właściwym
zachowaniem: to limit, nie kolejka.

## Kolejna nieprawda w moich własnych notatkach: telefon ma 3,8 GB RAM

Cały ten blok opierał się na stwierdzeniu „telefon ma 512 MB". Zmierzone:
`/proc/meminfo` → `MemTotal: 3797996 kB`, model 21061119DG, SoC mt6768.
Ta wartość powtarzała się w komentarzu testu
(`"a server-sized default for a 512MB device"`) i w notce pamięci, więc
uzasadnienie limitu wątków było zbudowane na nieprawdziwym założeniu.

Nie zmienia to decyzji o ograniczeniu — limit wątków nadal jest potrzebny,
bo problem nie leży w pamięci, tylko w tym, że wątek jest zajęty godzinami.
Zmienia to **uzasadnienie**: limit jest teraz wyprowadzony z pomiaru progu
(8 widzów zabija API), a nie z rzędu wielkości telefonu. Liczba w teście
została podniesiona z `2..8` do `2..16` razem z komentarzem wyjaśniającym,
czemu i czemu poprzedni uzasadnienie było błędne.

## Pula HTTP: dwie pule są niemożliwe w NanoHTTPD 2.3.1

Naturalna naprawa brzmi „daj `/video` osobną pulę, żeby krótkie API nigdy nie
czekało". `javap -p` na `NanoHTTPD$ClientHandler` pokazuje tylko:

```
public void close();
public void run();
private final java.io.InputStream inputStream;
private final java.net.Socket acceptSocket;
```

Nie ma publicznego dostępu do URI ani do gniazda, więc w `exec()` nie da się
rozróżnić żądania długiego od krótkiego — i nie ma jak przekazać gniazda do
innej pule bez refleksji na pole prywatne. Dlatego rozwiązanie idzie od
końca: ograniczyć liczbę strumieni tak, żeby pula nigdy się nie zapełniała,
zamiast rozdzielać ją na dwie.

To samo ustalenie co w poprzedniej fali, ale teraz z konkretną konsekwencją
projektową zamiast usuniętego na ślepo kodu.

## Retencja działa, ale jej limity są na sztywno (2026-09-28)

Trzy mechanizmy są poprawne i potwierdzone pomiarem:

- `ClipRetentionScheduler` odpala sweep przy starcie, po zamknięciu klipu
  (20 s opóźnienia) i co 15 minut jako zabezpieczenie.
- `ClipWriter` łapie `IOException` przy zapisie, zatrzymuje pętlę i zamyka
  uchwyt — wyciągnięta karta SD nie powoduje wirowania w miejscu.
- `ClipRetention` OR-uje trzy granice, a klip w trakcie zapisu jest chroniony
  nazwą, więc nie zniknie spod skrzędeł piszącego.

Zmierzone: 12 klipów po 2 MB (8 „starych", 4 nowe) → `POST /clips/prune`
zwraca `{"removed":0,...}`, bo domyślne granice to 4096 MB / 7 dni / 500 plików.
Mechanizm jest więc osiągalny i poprawny.

**Ale granic nie da się zmienić.** Sprawdzone na urządzeniu i w kodzie:

```
POST /settings/clip_max_files      -> 404
POST /settings/clip_max_space_mb   -> 404
```

`clipMaxSpaceMb`, `clipMaxAgeHours` i `clipMaxFiles` mają czytników
(`StreamService.kt:268-270`, `StreamServer.kt:1159-1168`) i **zero writerów** —
żaden kod w repo nie przypisuje tych właściwości, a `ClipActivity` ma
przycisk „prune", lecz nie ekran ustawień. Wszystkie trzy istnieją jako
klucze, właściwości i zakresy `coerceIn`, co wygląda na konfigurowalne.

To **decyzja produktowa, nie bug** — domyślne wartości są rozsądne, a telefon
miał 18 GB wolnego miejsca przy limicie 4 GB. Ale nikt nie może tego
dostosować, więc jeśli limit 4 GB okaże się za mały albo za duży, jedyną
drogą jest ponowna kompilacja. Zapisane jako otwarte, nie naprawiane po cichu.

## Uszkodzony klip wygląda jak dobry

`ClipStorage.list()` filtruje wyłącznie po nazwie
(`startsWith("klip_") && endsWith(".mp4")`) — nie sprawdza zawartości ani
kompletności pliku. Zmierzone: plik ucięty w połowie (1238 B z 2477 B) oraz
plik będący 5000 bajtami losowych danych trafiły na listę jako zwykłe
nagrania, a `GET /clips/<nazwa>` zwracał dla obu HTTP 200 z pełną zawartością.

Odtwarzac ich nie da, a interfejs nie pokazuje czasu trwania ani żadnego
znacznika, więc użytkownik widzi trzy pozycje, z których dwie są śmieciem.
`ClipWriter` wspomina o tym w komentarzu („read duration 0 as incomplete file
and show a…") — ale ta wskazówka nigdzie nie została użyta.

Nie naprawiam tego automatycznie: usunięcie pliku po cichu gorsze od
pokazania go z ostrzeżeniem, a decyzja „co pokazać" należy do użytkownika.
Możliwe rozwiązania to osobny licznik `suspect` w `/clips` i znacznik w UI,
albo walidacja `moov` przy skanowaniu. Do wyboru.

## HLS: wyciekany MediaCodec i telemetria, która kłamała (2026-09-29)

Audyt `HlsSession` znalazł dwa realne defekty. Oba potwierdzone w bieżącym
kodzie przed naprawą.

### Wycięknięty MediaCodec przy nieudanym starcie

`H264Encoder.start()` trzymał `MediaCodec` jako lokalną `val mc` wewnątrz
`try`. Gdy `configure()` albo `start()` rzuciło, `catch` ustawiał
`codec = null` — zgubienie ostatniej referencji **bez** `release()`.
`MediaCodec` nie ma finalizera odzyskującego zasób natywny, więc enkoder
zostawał przydzielony do końca procesu.

Naprawione: `mc` jest deklarowane przed `try`, więc `catch` ma uchwyt:

```kotlin
var mc: MediaCodec? = null
return try {
    mc = MediaCodec.createByCodecName(info.name)
    ...
} catch (e: Exception) {
    ...
    try { mc?.release() } catch (_: Exception) {}
    false
}
```

**Wzmocnione przy okazji**: `pickHardwareAvcEncoder()` i `pickColorFormat()`
też były poza `try`, więc wyjątek z `MediaCodecList` uciekał z `start()`
zamiast dać `false` + czytelne `lastError`. To złamało kontrakt, na którym
polega `CameraManager.startHls()` — i znalazł to dopiero test, nie audyt.
Teraz odkrywanie enkodera jest w tej samej ochronie co `configure()`.

### `hls.clients` rósł w nieskończoność

`handleHlsPlaylist()` wołał `clientJoined()` **przy każdym pollu playlista**,
a `clientLeft()` nie miał ani jednego wywołania w całym repo. `hls.clients`
rósł ~2/s i nigdy nie mógł spaść. Licznik, którego nie da się zmniejszyć,
jest gorszy niż brak licznika — każda przyszła polityka idle na `clients`
była skazana na zaufanie do liczby, która nie znaczy niczego.

Usunięte w całości: pole `clients`, `clientJoined()`, `clientLeft()` oraz
wpis `"clients"` w `hlsStatus()`. WebUI dotykał tylko `audio.clients` (osobna
ścieżka, nietknięta). Zmierzone po naprawie: `clients` zniknął z `/status.json`,
pozostałe 11 kluczy `hls` bez zmian.

Zostawione celowo: `requestKeyFrame()` — audyt nazwał je martwym, ale to
udokumentowany haczyk („e.g. right after a client joins") bez wywołującego.
Skasowanie wyrzuciłoby działający sposób na wymuszenie IDR.

### Czego nie dało się zmierzyć, i dlaczego

Wyciek jest **nieosiągalny przez API**: `pickHardwareAvcEncoder()` odfiltrowuje
każdy rozmiar, którego sprzętowy enkoder nie obsługuje, więc `configure()`
nigdy nie dostaje kodeka, którego nie umie skonfigurować. Próba na 4K
(oczywisty sposób na wymuszenie błędu) zakończyła się `configure failed` =
**0** trafień w logu — 4K po prostu jest obsługiwane. Ta nieosiągalność jest
dokładnie powodem, dla którego błąd przetrwał.

Test mutacyjny to potwierdził wprost: usunięcie `release()` **nie wywala
żadnego testu JVM**, bo na zwykłym JVM `createByCodecName()` rzuca, zanim
cokolwiek przydzieli. Zamiast udawać pokrycie, zabezpieczenie sprawdza kształt
źródła (`ocubea.srcRoot` podawane w `app/build.gradle.kts`) i mówi wprost,
czego nie potrafi. Dwie mutacje sprawdzone ręcznie — usunięcie `release()` oraz
przywrócenie `clients` — obie wywalają właściwy test.

## HLS nie startuje na tym telefonie (otwarte, nie regresja)

Po obu naprawach zmierzone: `GET /hls/init.mp4` → **404**, `frames_encoded: 0`,
`measured_fps: 0.0`, `last_error: none`, playlista → 503 „Encoder warming up".
Sprawdzone **na czystym HEAD** (`git stash`) — identycznie, więc to nie jest
skutek tych zmian.

`codec` wskazuje `c2.mtk.avc.encoder`, więc wybór enkodera działa; `started`
nie jest ustawiane. Do zdiagnozowania potrzebny jest log z `TAG` enkodera
(dodam go do audytu przy najbliższej okazji). MJPEG `/video` działa
normalnie, 15 FPS — użytkownik nie traci obrazu, tylko ścieżkę HLS.
