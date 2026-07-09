# API

<http://192.168.1.149:8080/video> to URL dla MJPEG.
<http://192.168.1.149:8080/shot.jpg> pobiera ostatnią klatkę.
<http://192.168.1.149:8080/audio.wav> to strumień audio w formacie Wav.
<http://192.168.1.149:8080/audio.aac> to strumień audio w formacie AAC (jeśli jest obsługiwany przez urządzenie).
<http://192.168.1.149:8080/audio.opus> to strumień audio w formacie Opus.
<http://192.168.1.149:8080/focus> ustawia ostrość w kamerze.
<http://192.168.1.149:8080/nofocus> zwalnia ostrość.

## Night vision

POST
 <http://192.168.1.149:8080/settings/night_vision?set=off>

## front facing camera

scheme
 http
host
 192.168.1.149:8080
filename
 /settings/ffc
set
 off
Adres
 192.168.1.149:8080

## Zoom

POST

scheme
 http
host
 192.168.1.149:8080
filename
 /ptz
zoom
 22

## Stream quality

POST

scheme
 http
host
 192.168.1.149:8080
filename
 /settings/quality
set
 90
k