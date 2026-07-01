[app]
title = Sztreamerr
package.name = sztreamerr
package.domain = io.michaumiau
source.dir = .
version = 0.3.0
orientation = landscape
fullscreen = 0
android.permissions = CAMERA,INTERNET,WAKE_LOCK
requirements = python3,kivy,pyjnius,jnius,sdl2
p4a.bootstrap = sdl2
deploy_dir = bin/
# Fix Python.h not found in SDL2 bootstrap
p4a.extra_setup_args = --build-system=ndk
python3.version = 3.11
