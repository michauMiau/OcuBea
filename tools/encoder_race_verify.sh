#!/bin/sh
# Reproduces the encoder stop/teardown race on a real phone.
#
# The JVM test can hold the HANDSHAKE (the flag, the bounded wait, the ordering)
# but not the native object: MediaCodec cannot be constructed off-device, so
# `codec` is always null there and a stop() that reads the handle after clearing
# the field is indistinguishable from one that reads it before. Both orderings
# pass on the JVM, which is exactly why this has to run on hardware.
#
# What killed the process (Sony F3311, Android 6, logcat, in this order):
#
#     Fatal signal 11 (SIGSEGV) ... tid (ocubea-analysis)
#     stopped OMX.MTK.VIDEO.ENCODER.AVC
#     started OMX.MTK.VIDEO.ENCODER.AVC 864x480@24
#     Process com.ocubea has died
#
# The crash landed first; the encoder switch only logged afterwards. So the
# switch opens the window rather than causing it -- which means the reproduction
# is: get the encoder running, then stop it while the analyzer is feeding frames,
# repeatedly, and watch for a native crash.
#
# Exits non-zero on any SIGSEGV, so it can gate a change.
set -u

HOST="${OCUBEA_HOST:-192.168.1.184}"
ADB="${ADB:-/usr/bin/adb}"
SERIAL="${OCUBEA_SERIAL:-}"
ADB_SERIAL_ARGS="${OCUBEA_SERIAL:-}"
ROUNDS="${ROUNDS:-40}"
CLIP_S="${CLIP_S:-3}"

# The phone hangs off a second machine here (adb over ssh), so ADB can be any
# command line -- a local binary, or "ssh host adb". Quoting it as one word
# would break that, so it is expanded unquoted on purpose.
a() { $ADB $SERIAL "$@" 2>/dev/null; }
log() { $ADB $SERIAL "$@" 2>/dev/null; }

api() { curl -sS -X POST "$@" 2>/dev/null; }
alive() { curl -sS -o /dev/null -w '%{http_code}' "$HOST:8080/status.json" 2>/dev/null; }

echo "=== wyczyszczenie logcat ==="
a logcat -c

echo "=== telefon musi odpowiadać (HTTP 200) ==="
code=$(alive)
if [ "$code" != "200" ]; then
  echo "  HTTP $code -- start apkki recznie i uruchom ponownie"
  exit 2
fi
echo "  OK"

# Warm the encoder up first: the race needs a live MediaCodec being fed, and a
# session that never started the encoder cannot crash.
echo "=== rozgrzewka enkodera (nagrywanie ${CLIP_S}s) ==="
api "$HOST:8080/clips/record?seconds=$CLIP_S" >/dev/null
sleep 4

i=1
started=0
while [ "$i" -le "$ROUNDS" ]; do
  # Alternate the profile: each switch stops and rebuilds the H.264 encoder,
  # which is the stop() under test. The clip record overlaps it so there is a
  # second encoder in flight on the same camera path.
  if [ $((i % 2)) -eq 0 ]; then p=low; else p=default; fi
  api "$HOST:8080/hls/profile?set=$p" >/dev/null

  # Fire-and-forget: the point is to hit stop() mid-drain, not to sequence it.
  if [ $((i % 3)) -eq 0 ]; then
    api "$HOST:8080/clips/record?seconds=$CLIP_S" >/dev/null &
  fi

  sleep 1

  if [ "$(alive)" != "200" ]; then
    echo "  przebieg $i: serwer przestal odpowiadać (HTTP $(alive))"
    sleep 8
  else
    [ $((i % 10)) -eq 0 ] && echo "  przebieg $i/$ROUNDS: zyje"
  fi
  i=$((i + 1))
done

sleep 5

echo
echo "=== wynik ==="
segv=$(log logcat -d | grep -cE 'Fatal signal|SIGSEGV')
fatal=$(log logcat -d | grep -cE 'FATAL EXCEPTION')
died=$(log logcat -d | grep -cE 'has died')
kept=$(log logcat -d | grep -cE 'keeping it alive')

echo "  SIGSEGV:            $segv"
echo "  FATAL EXCEPTION:    $fatal"
echo "  proces zginal:      $died"
echo "  stop() zatrzymal kodek (zabezpieczenie zadzialalo): $kept"
echo "  serwer teraz:       HTTP $(alive)"

if [ "$segv" -gt 0 ] || [ "$fatal" -gt 0 ] || [ "$died" -gt 0 ]; then
  echo
  echo "  FAIL -- crash wystapil:"
  log logcat -d | grep -E 'Fatal signal|SIGSEGV|FATAL|has died' | tail -12
  exit 1
fi

echo
echo "  PASS -- $ROUNDS przebiegow bez natywnego crasha"
exit 0
