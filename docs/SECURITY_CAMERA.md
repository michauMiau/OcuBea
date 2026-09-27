# Security camera — automatyczne usuwanie i retencja

Status: **zaplanowane, nie zaimplementowane.** Powstało 2026-09-27 podczas
debugowania HLS. Poniżej decyzje projektowe; implementacja osobnym commitem.

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

## Kolejność implementacji

1. Rotacja klipów + `ClipInfo` + retencja po **czasie** (najprostsze, daje
   wartość od razu)
2. Limit **miejsca** na tej samej liście
3. Detekcja **ruchu** jako gate zapisu
4. `mfra` i sprzątanie plików po crashu

## Kryterium sukcesu

Test: nagraj 3 minuty z ruchem, potem godzinę bez. Sprawdź na urządzeniu:
- liczba plików i łączny rozmiar mieszczą się w limitach
- klip z ruchem zawiera ruch, klip bez ruchu nie powstał
- `status.json` pokazuje `dropped` nie większe niż bez zapisu
- po `force-stop` i restarcie nie zostają śmieci
