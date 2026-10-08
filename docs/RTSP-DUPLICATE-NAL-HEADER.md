# OcuBea RTSP — RTP reasemblacja: podwójny nagłówek NAL

Data: 2026-10-04, telefon A6 (Sony F3311, Android 6), `192.168.1.184:8554`

## Objaw

RTSP daje `200 OK` na DESCRIBE/SETUP/PLAY, pakuje 2.4 MB RTP,
ale ffmpeg odrzuca każdą klatkę:

    [h264 @ ...] illegal POC type 5
    [h264 @ ...] sps_id 1 out of range
    [h264 @ ...] missing picture in access unit

Ten sam strumień przez HLS (`/hls/init.mp4` + `/hls/seg*.m4s`) dekoduje się
poprawnie — `codec_name=h264 profile=Baseline width=1280 height=720 level=41`,
PNG 226004 B. Więc koder i parametry zestawu są dobre; zepsute jest wyłącznie
pakowanie H.264 na RTP.

## Dowód: powtórzony nagłówek NAL

Rozkład wakeup-identyfikatorów ze strumienia RTSP:

    SPS in-band : 67 67 42 00 29 8d 8d 40 28 02 dd 00 f0 88 45 38
    SPS z SDP   :    67 42 00 29 8d 8d 40 28 02 dd 00 f0 88 45 38
    PPS in-band : 68 68 ca 43 c8
    PPS z SDP   :    68 ca 43 c8

Bajt `67` to nagłówek NAL (`nal_ref_idc=3, nal_unit_type=7` = SPS).
W strumieniu jest go **dwa razy**. To samo z `68` (PPS).

Konsekwencja jest dokładnie widoczna w statystyce typów NAL:

    typy: {7: 1, 8: 1, 0: 75}

75 jednostek dostępu ma `nal_unit_type = 0`, zamiast `5` (IDR). `0` nie jest
typem jednostki dostępu w H.264 — to pierwszy bajt obrazu interpretowany
jako nagłówek, czyli przesunięcie o jeden bajt. Stąd `sps_id 1 out of range`
(decoder liczy SPS-y i trafia na bajt, którego nie ma) i `illegal POC type 5`
(POC liczony z bajtu `pic_order_cnt_type`, który w tym miejscu już nie jest
prawdziwym polem).

## Przyczyna

`H264Rtp.Packet.payload()` buduje nagłówek FU-A z `indicator` i `nalType`:

    it[0] = (indicator and 0xFF).toByte()      // FU-A indicator
    it[1] = (start ? 0x80 : 0) or (end ? 0x40 : 0) or (nalType and 0x1F)

A potem `H264Rtp` składa z tego NAL przez doklejenie `indicator` do `body`.
Jeśli `body` **już zawiera** nagłówek NAL, powstaje `67 67` — a przy
pakowaniu z `body` jako bajtami „surowego” NAL-a `indicator` trafia do
`body` drugi raz.

Dla pojedynczego NAL-a (nie fragmentu) ta sama ścieżka:

    it[0] = (indicator and 0xFF).toByte()      // naglowek NAL
    ... body ...

więc jeśli `body` zaczyna się od bajtu nagłówkowego, wynik ma `67 67`.

## Co NIE jest przyczyną (sprawdzone, nie zgadywane)

- **Nie cleartext i nie routing.** SETUP idzie po `trackID=0`, wraca `200 OK`.
- **Nie transport.** Serwer celowo używa `RTP/AVP/TCP` interleaved
  (RtspServer.kt, linia 34-35: „One port, no UDP loss"). Żądanie UDP
  dostaje `Transport: RTP/AVP/TCP;interleaved=0-1` w odpowiedzi, co jest
  zgodne z RFC 2326 dla serwera, który nie chce UDP. Zero pakietów na porcie
  UDP to **poprawna** odpowiedź na moje żądanie, nie błąd.
- **Nie profil/kodek.** SPS z SDP dekoduje się ręcznie: `profile_idc=66`
  (Baseline), `level_idc=41` (4.1), `nal_unit_type=7`. Parametry są typowe
  dla H.264 Baseline 4.1.
- **Nie GOP.** `KEY_I_FRAME_INTERVAL=0` (HlsProfile.DEFAULT) daje IDR na
  każdej klatce. To kosztne, ale legalne — i HLS z tym samym GOP dekoduje
  się bez błędu, więc to nie jest przyczyna błędu dekodowania.
- **Nie długość segmentu ani `tfhd`.** Segment HLS ma poprawny
  `tfhd`/`tfdt`/`trun` z `data-offset=108` wskazującym na `mdat`; mój
  pierwszy parser zgłosił to jako rozjazd o 100 B, co było moim błędem
  (liczyłem rozmiar `moof` zamiast offsetu).

## Naprawa

W `H264Rtp`: `body` musi być **bez** nagłówka NAL-a (same bajty ładunku),
albo `indicator` nie może być doklejany, gdy `body` już go zawiera.
Test jednostkowy musi złożyć strumień przez prawdziwy kod i zdekodować /
sprawdzić, że nagłówek występuje **dokładnie raz**.

## Bramka

`tools/rtsp_interleaved_probe.py` — robi DESCRIBE/SETUP/PLAY po interleaved TCP,
reasembluje RTP do Annex-B i wymaga:

- co najmniej jednego SPS i jednego PPS,
- **dokładnie jednego** bajtu nagłówkowego przed SPS (nie `67 67`),
- `nal_unit_type` jednostek dostępu równe 5, nie 0.

## Wynik po naprawie (A6, realny pomiar)

    SDP: SPS 15B PPS 4B
    SETUP: RTSP/1.0 200 OK   Transport: RTP/AVP/TCP;unicast;interleaved=0-1
    PLAY:  RTSP/1.0 200 OK
    interleaved 3258418 B -> 2420 ramek, kanal(y) [0, 1]
    NAL: {'NAL 7': 1, 'NAL 8': 1, 'FU-A/5S': 97, 'FU-A/5': 2175, 'FU-A/5E': 96}

    ffprobe: codec_name=h264  profile=Baseline  width=1280  height=720  level=41
    ffmpeg:  97 klatek typu I, bez "illegal POC type 5" i bez "sps_id out of range"

Błędy, które zniknęły po naprawie:

- ~~`illegal POC type 5`~~
- ~~`sps_id 1 out of range`~~
- ~~`non-existing PPS 0 referenced`~~

Testy: `H264RtpHeaderOnceTest` 6/6 PASS. Test mutacyjny (przywrócenie
`copyOfRange(from, to)`) daje **5/19 FAIL** w `H264RtpHeaderOnceTest`
i `H264RtpTest` — czyli naprawa jest pilnowana, a nie tylko napisana.

## Uczciwe zastrzeżenie o obrazie

Klatki dekodują się poprawnie, ale pokój był ciemny w chwili pomiaru
(RTSP i HLS pokazywały niemal czarną kadrę z szumem). Kamera żyje —
`frames_published=1042`, `pipeline_errors=0`, enkoder
`OMX.MTK.VIDEO.ENCODER.AVC` — więc to warunki oświetlenia, nie uszkodzenie.
Wcześniejszy pomiar HLS w tym samym dniu pokazywał fioletowy obraz z widoczną
plamą światła, więc obraz przechodzi.

## Wniosek o „braku P-frame'ów"

`KEY_I_FRAME_INTERVAL=0` (HlsProfile.DEFAULT) rzeczywiście daje IDR na każdej
klatce — 97 klatek, 97 IDR, zero P. To koszt bitrate'u (pomiar w H264Encoder.kt:
GOP=0 dawał 12,6-12,7 Mbps wobec 8,1 Mbps dla GOP=1), ale **nie jest błędem
dekodowania**. Każdy klient gra to poprawnie, wolniej w sieci. Zmiana GOP dla
RTSP to decyzja o jakości/latencji, nie naprawa — i nie została zrobiona,
bo nie o nią prosiłeś.

## Czego nie dało się sprawdzić

Android 16 (22101320G) jest niedostępny — wszystkie znane adresy ADB
(192.168.1.122, .124, .184, .199) odrzucają połączenie na 5555. Pełny
przebieg na A16 czeka na reconnect.
