"""
Android APK Build Instructions for Sztreamerr

Buildozer is configured in buildozer.spec. To build the APK:

1. Install dependencies (Linux):
   sudo apt-get install openjdk-17-jdk unzip
   pip install buildozer cython

2. Configure Android SDK (first time):
   export ANDROID_HOME=~/Android/Sdk
   sdkmanager --licenses  # accept all licenses interactively
   sdkmanager "platforms;android-34" "build-tools;37.0.0"

3. Build:
   cd /tmp/sztreamerr (or your cloned repo)
   buildozer android debug

4. Find the APK:
   ls bin/*.apk

Notes:
- The project is now PRIVATE on GitHub — use `git pull` with credentials.
- For Android-specific features, connect an emulator or USB device.
"""
print(__doc__)