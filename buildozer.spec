[app]
title = Sztreamerr
package.name = sztreamerr
package.domain = io.michaumiau
source.dir = .
version = 0.3.0
orientation = landscape
fullscreen = 0
android.permissions = CAMERA,INTERNET,WAKE_LOCK
requirements = cpython@3.12,kivy,pyjnius,jnius,sdl2
p4a.bootstrap = sdl2
deploy_dir = bin/
# Use default Python version from p4a recipe (currently 3.12+ compatible)
android.ndk = 28c
buildozer.target = android-34
android.minapi = 24
