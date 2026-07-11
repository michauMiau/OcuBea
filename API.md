# OcuBea IP Webcam Compatible API

Base URL: `http://<device-ip>:9090`

## Streaming & Snapshots

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/video` or `/mjpeg` or `/stream` | GET | MJPEG streaming (multipart/x-mixed-replace) |
| `/shot.jpg` or `/snapshot.jpg` | GET | Single snapshot as JPEG binary |

## Camera Control

### Focus

```
POST /focus
```
Trigger autofocus at center. Returns `text/plain: ok`.

```
POST /nofocus
```
Release focus (disable continuous AF). Returns `text/plain: ok`.

### Front/Back Camera Switch

```
POST /api/camera?set=true  # front camera
POST /api/camera?set=false # back camera
```
Returns JSON: `{"status": "ok", "camera": "front"}` or `"back"`.

Alternatively via settings endpoint (IP Webcam compatible):
```
POST /settings/ffc?set=on   # front
POST /settings/ffc?set=off  # back
```

### Zoom (PTZ)

```
POST /ptt?zoom=2.0
```
Set zoom level (1.0 = native, higher = digital zoom). Pan/Tilt not supported (fixed mount).

## Settings

```
POST /settings/<name>?set=<value>
```

Supported settings:

| Name | Values | Description |
|------|--------|-------------|
| `night_vision` | `on`, `off` | Low-light enhancement (stub) |
| `ffc` | `on`, `off` | Front-facing camera toggle |
| `quality` | number (480, 720, 1080) | Resolution: QVGA (≤480), HD720 (720-1079), FullHD (≥1080) |

## Torch/Lantern

```
POST /api/torch?on=true   # enable flashlight
POST /api/torch?on=false  # disable flashlight
```
Returns JSON with camera_id and torch state.

---

## Example Usage

```bash
# Start streaming
curl http://192.168.1.10:9090/video

# Take snapshot
curl -o shot.jpg "http://192.168.1.10:9090/shot.jpg"

# Switch to front camera
curl -X POST "http://192.168.1.10:9090/api/camera?set=true"

# Set quality to 1080p
curl -X POST "http://192.168.1.10:9090/settings/quality?set=1080"

# Enable torch
curl -X POST "http://192.168.1.10:9090/api/torch?on=true"
```
