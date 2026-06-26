# Sztreamerr (NAME W.I.P.)

A lightweight IP camera streamer mainly for phones

## The Goal

Create a lightweight, low latency, high resolution camera streaming app for phones and other device

The App will basically be IP Webcam, but without the ads, lower latency and open source

The app will host a webpage on the local network, where the user can see the live feed of the camera, along with some controls that will be controlled by a IP webcam compatible HTTP API

The uses will be: A quick security camera, A 3D Printer camera, A Webcam for OBS and general desktop use, a low latency robotics feed mainly for another project [xTRAP](https://github.com/michauMiau/xTRAP)

## The Architecture

The App will be built in a yet undecided, lightweight, high performance, easy to code, crossplatform language.

Libraries such as FFMPEG, Go2RTC and others will be used to encode the video and stream it

Unlike the original app which only used h264, different codecs such as H265, AVIF and other codecs will be availble depending on the availible hardware/software encoders avaiilble on the host device 
