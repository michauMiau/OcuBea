# Sztreamerr — Alternatywne Nazwy Projektu

Pomysły na lepsze/nowsze nazwy dla tego IP Camera Streaming Server.
Kryteria: krótkość, łatwe do zapamiętania, pasuje do funkcji (streaming wideo z kamery IP).

## Top picks

| Nazwa | Dlaczego warto |
|---|---|
| **Streamlet** | Diminutive od "stream" — proste, jasne, 9 liter |
| **Camflow** | Kamera + flow strumienia |
| **Picostream** | Picture + stream — opisowe |
| **Vidyo / Vidio** | Video + io (input/output) |
| **Streamix** | Stream + mix/matrix |
| **Watchtower** | Strażnik/obserwator (camera monitoring vibe) |
| **Eyebeam** | "Oko" + promień światła — kamera jak oko |
| **Pulsecam** | Kamera z pulsującą aktywnością streamu |
| **Lensflow** | Lens (obiektyw) + flow strumienia |
| **Streamora** | Stream + ora (złoty/słynny)

## Krótkie / minimalistyczne

- **Vido** — Video + IO w 4 literach
- **Camra** — Kamera bez "e" na końcu (jak GitHub, Flickr)
- **Strea** — odcięcie "stream"
- **Kamri** — kamera po japońsku?
- **Pixarv** — Picture + TV / video
- **Lumi** — światło w językach romańskich (francuski/laciński)
- **Ocula** — od "oculus" (oko) — brzmi profesjonalnie

## Z polskimi akcentami

- **Strumyk** — mały strumień wody (jak streaming!) — 7 liter, łatwe
- **Obrazek** — prosty obrazek / obraz — 8 liter
- **Fila** — nić / strumień (polski) — tylko 4 litery!
- **Łowca** — ten kto łapie klatki z kamery
- **Przódka** — coś co jest przed tobą (monitoring)
- **Słoik** — jak słoik na klocki / obrazki z kamery (użytkownik lubił to słowo!)

## Krótkie technicznie

- **MJPEGd** — daemon MJPEG (jak httpd, nginx) — brzmi profesjonalnie
- **Camsrv** — Camera Server w 6 literach
- **IPCamGo** — IP Camera + Go (szybkość)
- **FrameOS** — system operacyjny do klatek / ramki
- **StreamAP** — Stream App w skrócie

## Najlepsze propozycje wg mnie

1. **Strumyk** 🌊 — polski, łatwy, 7 liter, pasuje do streamingu jak ulał
2. **Eyebeam** 👁️ — angielski, profesjonalny, odzwierciedla kamerę monitoringową
3. **Streamlet** 🎬 — anglosaski, diminutive, brzmi miło
4. **Ocula** 🔭 — łacińskie "oko", brzmi jak profesjonalne narzędzie
5. **Fila** 🧵 — polski strumień/nitka, tylko 4 litery!

## Jak zmienić nazwę w projekcie? (jeśli użytkownik wybierze)

```bash
grep -r "Sztreamerr" src/ --include="*.py" | head -20
# Zmieniamy w:
# 1. main.py — nazwa w docstring i logging
# 2. README.md — tytuł i opis
# 3. buildozer.spec — app.title = "NowaNazwa"
# 4. AndroidManifest.xml (generowany) — przez buildozer.spec.app.title
```
