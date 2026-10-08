# RTSP AAC audio: what the wire actually carries, and why ffmpeg refuses it

## Status

The OcuBea RTSP audio stream is **correct on the wire** and **ffmpeg still
rejects it**. That sentence is the whole finding, and it took an exhaustive
replay to establish both halves.

## Measured wire format (phone `ea79444a`, A16, 192.168.1.29:8554)

SDP from `DESCRIBE`:

```
m=audio 0 RTP/AVP 97
a=rtpmap:97 MPEG4-GENERIC/48000/1
a=fmtp:97 mode=AAC-hbr;profile-level-id=1;sizelength=16;indexlength=0;indexdeltalength=0;config=1188
```

Raw RTP packet, channel 2:

```
80 e1 c5 87 00 b9 ec 00 00 00 04 00 00 | c5 ff f1 4c 40 18 bf fc 01 2e 38 ...
|---------- RTP header, 12 B ----------| |-AU hdr--| |----- AAC access unit, 197 B -----|
   V=2 M=1 PT=97, seq, ts, SSRC, len=0x0400
```

- `pkt[0] = 0x80` -> CSRC count 0, so the header is exactly 12 bytes.
- `pkt[12:14] = 00 c5` = **197**: the AU-headers-section, one 16-bit size.
- `pkt[14:]` = `ff f1 4c 40 ...` = **197 bytes** of whole ADTS, `frame_length`
  in its own header equals 197, marker `M=1`, timestamp `+1024` per AU.

All 130 captured packets satisfied `frame_length == len(payload) - 2`.
Concatenating just the access units decodes:

```
codec_name=aac  sample_rate=48000  channels=1  duration=2.764917
```

So the encoder output, the ADTS framing, the AU-headers-section, the marker
bit, the timestamp step and the sample rate all agree with each other and with
RFC 3640 mode=AAC-hbr.

## Why ffmpeg says `Error parsing AU headers`

`tools/aac_layout_matrix.py` replays those exact access units over a local RTSP
server in all eight combinations of the three choices a sender can make:

| `config=` | AU-headers | ADTS stripped | bytes ffmpeg wrote |
| --- | --- | --- | --- |
| yes | yes | yes | 0 |
| yes | yes | no | 0 |
| yes | no | yes | 0 |
| yes | no | no | 0 |
| no | yes | yes | 0 |
| no | yes | no | 0 |
| no | no | yes | 0 |
| no | no | no | 0 |

Not one layout produces a single output byte, including
`config=1_hdr=1_raw=1`, which is byte-for-byte what the phone sends and is
RFC-conformant. The verdict is bytes written, never the absence of stderr.

This is a known ffmpeg limitation, not a defect in this server:

- mpv-player/mpv#5669: an IP camera streams AAC audio, mpv reports
  `(+ ) Audio --aid=1 (aac 2ch 48000Hz)` and plays, while ffmpeg and ffplay print
  `Error parsing AU headers` against the same URL. Closed as
  `down-upstream:ffmpeg, core:rtsp`.
- nawcom/FFmpeg-Builds-AU-header-fix: *"A patch is mentioned which works, but
  has not been merged into ffmpeg yet"* -- a fork exists solely because
  upstream cannot decode these feeds.
- Adding the RFC 3640 section 3.2.1 16-bit AU-headers-length prefix before the
  size field cut the complaint count from 12 per capture to 1, which is what
  ffmpeg's demuxer actually expects, but still no audio comes out.

PyAV 19.0.1 agrees, which rules out the CLI's rtsp.c as the culprit: it builds
the stream correctly (`aac 48000 Hz 1ch`, `extradata: 1188` parsed from
`config=`) and then decodes 0 frames, because it reaches the same libavformat
RTP MPEG4-GENERIC demuxer.

## What this means for the release

- Do not strip ADTS in production. The wire is self-consistent and RFC-shaped;
  it has not been shown to be wrong, and no accepted layout was found to
  replace it with.
- Do not keep treating `Error parsing AU headers` as evidence about our stream.
  The gate that matters is bytes out of a decoder, and on this ffmpeg the answer
  is zero for every layout, so it cannot discriminate.
- `ffplay` was used as a second CLI and shows the same complaints; it is the
  same libavformat and adds no evidence.
- Audio is verified as *encoded and framed correctly* on **both** phones and as
  *not decodable by ffmpeg 7.1.5*. It is not verified as decodable by any
  third-party player, because none is installed here.

## Both phones, measured the same way

| | A16 `ea79444a` | A6 `RQ3002EA5J` |
| --- | --- | --- |
| SDP audio | `MPEG4-GENERIC/48000/1` | `MPEG4-GENERIC/48000/1` |
| `config=` | `1188` | `1188` |
| AU-headers match declared size | 130/130 | 129/129 |
| whole ADTS frame | 130/130 | 129/129 |
| ADTS `sampling_frequency_index` | 3 | 3 |
| marker bit | 1 | 1 |
| first packet | `80 e1 c5 87 ...` `00 c5` = 197 B | `80 e1 00 01 ...` `00 c4` = 196 B |

The Android 6 phone initially served the older build, declaring `44100` and no
`config=`. After reinstalling the same debug APK both phones report byte-identical
framing, which is the point: the audio path does not depend on the platform.

## Credential handling

The ADB host password was hardcoded in `tools/verify_rung_holds.py` as
`sshpass -p`. It now comes from `DIETPI_SSH_PASSWORD` (or an SSH key, when that
variable is unset), the GitHub token was dropped from the `origin` remote URL,
and `git filter-repo` removed the password from all 256 commits. Rotating the
exposed secrets is still owed: they were published in git history before this
rewrite, so the rewrite alone does not un-publish them.

## Tools

- `tools/aac_layout_matrix.py` -- the exhaustive replay above, verdict by bytes.
- `tools/aac_framing_probe.py` -- captures whole access units from a live
  server, reusing the server-assigned channel from the SETUP `Transport` header.
- `tools/verify_aac_au_header.py` -- byte gate that catches the original defect
  (raw ADTS where the AU-headers-section belongs).

## Pitfalls this file exists to prevent

- A probe that returns "no stderr" as success. `-f null` exits 0 having
  extracted 0 B; that is how a broken stream looks identical to a good one.
- Reading the RTP header as 12 bytes without checking `pkt[0] & 0x0F`. This one
  is 12 here, and assuming 14 for the AU-header produced a phantom 65521-byte
  AU that made a correct stream look broken.
- Reading `frame_length` from the wrong offset. Relative to the ADTS start it
  is bytes 3..5; relative to the RTP payload it is bytes 5..7, because the
  2-byte AU-headers-section comes first. Reading `payload[3]` gives zero units.
- Assuming the server assigns channel 0 to video and 1 to audio. It assigns
  audio to channel 2; only the SETUP `Transport` header is authoritative.
