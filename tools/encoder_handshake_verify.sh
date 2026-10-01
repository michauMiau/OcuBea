#!/bin/sh
# Proves the encoder stop/teardown handshake actually fires.
#
# "deferred: 0 after 155 profile switches" cannot tell a working guard behind a
# narrow window apart from dead code, and the two call for opposite conclusions.
# This forces the collision instead of waiting for it.
#
# The mechanism is /diagnostic/encoder_hold?ms=N, which parks the analyzer INSIDE
# drain() -- while the native codec is genuinely mid-use. Default 0, so the
# shipping build is unaffected.
#
# Run in two parts:
#   part 1  hold=0   many profile switches  -> the guard must NOT fire
#   part 2  hold=N   one profile switch     -> the guard MUST fire
#
# Part 2 is the one that matters. Part 1 passing means nothing on its own: a
# guard that never fires also passes it.
#
# A note on where the hold has to sit. The first version of this probe parked
# after the encode body, and the negative control proved it was worthless --
# with the guard removed and a 900ms hold, still no SIGSEGV, because by then
# encode() no longer touched MediaCodec. Inside drain() it is real. Anything
# that parks the analyzer while it is NOT using the codec will let a broken
# build pass.
set -u

HOST="${OCUBEA_HOST:-192.168.1.184}"
HOLD_MS="${HOLD_MS:-1000}"
SWITCHES="${SWITCHES:-8}"
ADB="${ADB:-adb}"

api() { curl -sS "$@" 2>/dev/null; }

telemetry() {
  api "$HOST:8080/diagnostic/encoder_hold?ms=$1" | tr -d '\n'
}

# Average milliseconds per encode, from the cumulative counters.
held_us() {
  api "$HOST:8080/status.json" | python3 -c "
import json,sys
h=json.load(sys.stdin).get('hls') or {}
c=h.get('encoder_calls') or 0
n=h.get('encoder_held_ns') or 0
print(int(n/1e3/c) if c else 0)
"
}

counters() {
  api "$HOST:8080/status.json" | python3 -c "
import json,sys
h=json.load(sys.stdin).get('hls') or {}
print('%s %s' % (h.get('encoder_stops') or 0, h.get('encoder_stops_deferred') or 0))
"
}

# stops, deferred, released -- the leak identity needs all three.
counters_all() {
  api "$HOST:8080/status.json" | python3 -c "
import json,sys
h=json.load(sys.stdin).get('hls') or {}
print('%s %s %s' % (h.get('encoder_stops') or 0,
                    h.get('encoder_stops_deferred') or 0,
                    h.get('encoder_codecs_released') or 0))
"
}

wait_up() {
  i=0
  while [ "$i" -lt 12 ]; do
    [ "$(api -o /dev/null -w '%{http_code}' "$HOST:8080/status.json")" = "200" ] && return 0
    i=$((i + 1)); sleep 5
  done
  return 1
}

echo "=== telefon musi odpowiadać ==="
if ! wait_up; then
  echo "  brak odpowiedzi -- uruchom apkke recznie"
  exit 2
fi
echo "  OK"

# Force HLS to exist: it is lazy and only builds a session when a client asks
# for a playlist. Without this the profile switch has no encoder to stop and the
# guard is never even consulted.
echo "=== wymuszam sesje HLS ==="
i=0
while [ "$i" -lt 4 ]; do api "$HOST:8080/hls.m3u8" >/dev/null; sleep 1; i=$((i + 1)); done
echo "  $(telemetry 0)"

# ── part 1: hold off ────────────────────────────────────────────────────────
echo
echo "=== CZESC 1: hold=0, $SWITCHES przelaczen -- bramka nie powinna odroczyc ==="
telemetry 0 >/dev/null
i=1
while [ "$i" -le "$SWITCHES" ]; do
  if [ $((i % 2)) -eq 0 ]; then p=default; else p=low; fi
  api -X POST "$HOST:8080/hls/profile?set=$p" >/dev/null
  sleep 2
  api "$HOST:8080/hls.m3u8" >/dev/null
  sleep 1
  i=$((i + 1))
done
set -- $(counters)
stops1=$1; def1=$2
echo "  stops=$stops1 deferred=$def1"
echo "  oczekiwane: deferred 0 (okno za waskie, zeby trafic)"

# ── part 2: hold on ─────────────────────────────────────────────────────────
echo
echo "=== CZESC 2: hold=${HOLD_MS}ms, jedno przelaczenie -- bramka MUSI odroczyc ==="
telemetry "$HOLD_MS" >/dev/null

# Settle before switching. The hold applies to the NEXT encode, and with
# HOLD_MS=1000 each encode occupies ~2s inside the drain, so a switch sent
# immediately lands in a gap where nothing is holding the codec. Wait until the
# measured hold actually reflects the setting -- otherwise this reports FAIL for
# a build whose guard is fine, which is the failure mode worth avoiding.
base=$(held_us)
settled=0
i=0
while [ "$i" -lt 10 ]; do
  api "$HOST:8080/hls.m3u8" >/dev/null
  sleep 2
  now=$(held_us)
  if [ "$now" -gt 500 ]; then settled=1; break; fi
  i=$((i + 1))
done
echo "  settle: hold_ms/frame = $now us (settled=$settled)"
if [ "$settled" -ne 1 ]; then
  echo "  hold nie wszedl w encode() -- nie da sie ocenic bramki, wylaczam probe"
  telemetry 0 >/dev/null
  exit 3
fi

before=$(counters | cut -d' ' -f2)
api -X POST "$HOST:8080/hls/profile?set=low" >/dev/null
sleep 4
after=$(counters | cut -d' ' -f2)

telemetry 0 >/dev/null     # always switch the probe back off
echo "  deferred przed=$before po=$after"

# ── the leak check ──────────────────────────────────────────────────────────
# Every stop must release its codec exactly once, inline or handed off. The old
# deferred branch bumped the counter and returned, dropping the handle with no
# stop() and no release(): a leaked native MediaCodec per deferral, invisible in
# every other number on the page -- the stream keeps working and the heap looks
# flat. So stops + deferred has to equal released, and it does not need the probe
# at all; it needs the wait for the handoff threads to land.
echo
echo "=== WYCIEK: stops + deferred musi rowac sie z released ==="
i=0
while [ "$i" -lt 12 ]; do
  set -- $(counters_all)
  if [ "$(( $1 + $2 ))" = "$3" ]; then break; fi
  sleep 1; i=$((i + 1))
done
set -- $(counters_all)
stops=$1; def=$2; rel=$3
echo "  stops=$stops deferred=$def released=$rel  (suma=$((stops + def)))"
if [ "$((stops + def))" != "$rel" ]; then
  echo "  FAIL -- $((stops + def - rel)) zwolnien gubione. Deferred stop upuscil uchwyt"
  echo "         zamiast przekazac go do watku zwalniajacego."
  exit 1
fi
echo "  OK -- kazdy stop zwolnil dokladnie jeden kodek"

echo
echo "=== wynik ==="
if [ "$after" -gt "$before" ]; then
  echo "  PASS -- bramka zadzialala: stop() znalazl analyzer w srodku kodeka,"
  echo "          odczekal ${HOLD_MS}ms, nie znalazl wolnego i zachowal kodek"
  echo "          zamiast zwolnic go spod aktywnego encode."
  exit 0
fi

echo "  FAIL -- bramka nie odroczyla przy wymuszonym oknie."
echo "         Albo jest martwym kodem, albo hold nie trafia w encode()."
echo "         Sprawdz w logu: 'stop: analyzer still inside the codec'"
exit 1
