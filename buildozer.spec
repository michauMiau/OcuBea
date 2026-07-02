[app]
title = Sztreamerr
package.name = sztreamerr
package.domain = io.michaumiau
source.dir = src
version = 0.3.0
orientation = landscape
fullscreen = 0
android.permissions = CAMERA,INTERNET,WAKE_LOCK
requirements = python3,kivy,pyjnius,jnius,sdl2,aiohttp,pillow,requests,openssl
p4a.bootstrap = sdl2
deploy_dir = bin/
# Pin p4a to version with Python 3.11 support (avoids Python.h path mismatch)
p4a.branch = v2026.05.09
android.ndk = 25b
buildozer.target = android-34
android.minapi = 26
