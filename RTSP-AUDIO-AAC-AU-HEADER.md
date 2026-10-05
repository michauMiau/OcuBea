# OcuBea RTSP audio — SDP obiecuje AAC, na wire leci ADTS bez AU-headerów

Data: 2026-10-04, A16 (Redmi Note 12 Pro `ea79444a`, 192.168.1.29) i A6
(Sony F3311 `RQ3002EA5J`, 192.168.1.184). Codec ustawiony na `aac`.

## Objaw

Wideo dekoduje się poprawnie (po naprawie zduplikowanego nagłówka NAL).
Audio nie:

    $ ffmpeg -rtsp_transport tcp -i rtsp://192.168.1.29:8554/h264_pcm.sdp -t 3 -f null -
    [rtsp @ ...] Error parsing AU headers
        Last message repeated 887 times

Ten sam `200 OK` na SETUP, ten sam SDP, 177 pakietów RTP na kanale 2 —
a dekoder odrzuca każdą ramkę.

## SDP jest poprawny

    m=audio 0 RTP/AVP 97
    a=rtpmap:97 MPEG4-GENERIC/44100/1
    a=fmtp:97 mode=AAC-hbr;profile-level-id=1

To jest dokładnie to, czego wymaga AAC-hbr: `MPEG4-GENERIC` z trybem
`AAC-hbr`. Poprzednia naprawa SDP działa — nie należy jej cofać.

## Dowód: co faktycznie leci na wire

Pierwszy payload RTP, kanał 2, 384 B:

    ff f1 4c 40 17 df fc 01 28 38 29 8c 61 e1 09 c5

    AU-headers length = 0xff = 255 B  ->  nieprawidłowe

`0xFFF1` to nagłówek **ADTS** (syncword `0xFFF`), a nie AU-header. Dla
`mode=AAC-hbr` RFC 3640 pierwsze bajty ładunku to **AU-headers-section**:
`0xFFFF&0x0FFF >> 8` bajtów, czyli dla jednego AU z długością < 256 B jest to
**2 bajty** — `00 0X`, gdzie `X` to długość AU w bajtach.

Serwer wysyła zatem surowy ADTS, gdzie klient spodziewa się AU-headerów.
Nie ma sensu, w którym te dwa formaty się zgadzają.

## Przyczyna

`RtspServer.writeRtp()` ma dwa miejsca, które zakładają L16 niezależnie od
kodeka:

1. **Timestamp** (`clock = if (payloadType == PTYPE_PCM) audioTs.get()`)
   i przyrost `audioTs.addAndGet(read / BYTES_PER_SAMPLE)` — dzieli przez
   `BYTES_PER_SAMPLE = 2`, co jest prawdą dla 16-bitowego PCM i fałszem dla AAC,
   gdzie jednostka to próbka 44,1 kHz niezależnie od bajtów na wejściu.
2. **Nagłówek RTP** (`samples = length / BYTES_PER_SAMPLE`) — to „samples per
   packet" z definicji RFC 3551, czyli liczone w próbkach kodeka. Dla AAC to
   liczba próbek, nie bajtów/2.

Sam `PTYPE_PCM = 97` akurat pasuje, bo `m=audio ... RTP/AVP 97` deklaruje
właśnie 97 — nazwa stałej jest myląca, nie numer.

Bramka audio (`verify_audio_truth_gate.py`) tego nie łapie: sprawdza kody
SETUP/PLAY, nie payload. Dlatego bramka była zielona przy strumieniu, którego
nie da się odtworzyć.

## Co naprawa musi zrobić

Dla `aac` (`MPEG4-GENERIC`, `Aac-hbr`):
- wysyłać **2-bajtowy AU-header** przed ładunkiem: `0x00`, `0xNN` gdzie `NN`
  to długość AU w bajtach (a dla AU > 255 B — liczbę AU-headerów),
- liczyć timestamp w **próbkach** 44,1 kHz, nie `bajty/2`,
- `sizeLength`/`indexLength`/`indexDeltaLength` w `a=fmtp` muszą być jawne,
  bo klient musi wiedzieć, że liczy 2 bajty na AU.

Alternatywa: zmienić deklarację na `MP4V-ES`/`mpeg4-generic` z poprawnym
pakowaniem. Prostsza i uczciwa jest poprawka pakowania dla Aac-hbr.

Dla `wav`/`L16` obecna ścieżka jest poprawna i nie może się zmienić — dlatego
rozwiązanie musi być warunkowe na kodeku, a nie „zawsze AU-header".

## Czego NIE zmieniam

- Nie ruszam `sprop-parameter-sets` ani routingu `trackID` — oba potwierdzone
  działające.
- Nie ruszę GOP H.264 — bez P-frame'ów to decyzja jakość/latencja, nie błąd.
- Nie cofam naprawy zduplikowanego nagłówka NAL: potwierdzona ffprobe na obu
  telefonach (`Baseline 1280×720` na A6, `High 1280×720 level=31` na A16).