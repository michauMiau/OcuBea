"""
Sitecustomize for Android - Chaquopy compatible path setup.
This file is compiled into the APK and replaces any CI-generated version.
"""

import sys
import os

# Chaquopy handles Python paths automatically on Android
# No need to manipulate sys.path here - it would break Chaquopy's setup
# Just ensure we can import our app modules correctly

def _setup_app_paths():
    """Set up application paths for Kivy/Chaquopy on Android"""
    try:
        # Get the app directory (where APK is installed)
        app_dir = os.path.dirname(os.path.abspath(__file__))
        
        # Add app directory to Python path if not already there
        if app_dir not in sys.path:
            sys.path.insert(0, app_dir)
            
    except Exception as e:
        # Fail silently on Android - Chaquopy handles paths
        pass

# Run setup when module is imported
_setup_app_paths()
