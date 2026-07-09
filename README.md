# OcuBea

**Lightweight IP Camera Streamer for Android**

## Progress

### Current blockers

A Fresh start

### Currently worked on

A fresh start

## The Goal

Create a lightweight, low latency, high resolution camera streaming app for phones and other device

The App will basically be IP Webcam, but without the ads, lower latency and open source

The app will host a webpage on the local network, where the user can see the live feed of the camera, along with some controls that will be controlled by a IP webcam compatible HTTP API

The uses will be: A quick security camera, A 3D Printer camera, A Webcam for OBS and general desktop use, a low latency robotics feed mainly for another project [xTRAP](https://github.com/michauMiau/xTRAP)

## The Architecture

The APP shall be modular, easy to read the codebase, easy to understand, performant and easy to contribute to, and well documented
The App will be built in a kotlin (most likely)

Libraries such as FFMPEG, Go2RTC and others will be used to encode the video and stream it

Unlike the original app which only used h264, different codecs such as H265, AVIF and other codecs will be availble depending on the availible hardware/software encoders avaiilble on the host and decoders on the client device 

## Requirements
Must run on Android 6 or later
Can run on desktop platforms like Linux or Windows
Optional: Run on IOS 12 +

## Features to be recreated
- [ ] Basic Streaming
- [ ] Complete WebUI
- [ ] Basic App UI to change settings
- [ ] Basic API
- [ ] Motion Detection Recording (security camera)
- [ ] Audio Streaming
- [ ] Bidirectional Audio
- [ ] HTTPS Support
- [ ] More Streaming codecs
- [ ] Running App in the background
- [ ] Running on start-up
- [ ] Full API Parity
- [ ] Auto Screen Dim/Turn Off
- [ ] ONVIF Support
- [ ] Night Vision, without special hardware

## 📄 License
GPL

---
