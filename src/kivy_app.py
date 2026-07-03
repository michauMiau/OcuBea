"""
Kivy-based Android app for Sztreamerr.
Wraps the existing aiohttp streaming server with a Kivy UI.
"""

import asyncio
import logging
import os
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.clock import Clock
from stream.server import StreamServer
try:
    from stream.server import FrameSource as HttpFrameSource
except ImportError:
    # Kivy build environment may not have all deps yet — use placeholder
    class HttpFrameSource:  # type: ignore[no-redef]
        @classmethod
        async def blank_stream(cls, **kw):
            return None
from core import Settings

logger = logging.getLogger(__name__)


class SztreamerrApp(App):
    """Kivy wrapper for the Sztreamerr streaming server."""
    
    def build(self):
        # Set up UI layout
        root = BoxLayout(orientation='vertical', padding=10, spacing=10)
        
        # Title label
        self.title_label = Label(
            text='Sztreamerr - IP Camera Streamer',
            font_size=24,
            size_hint_y=0.1
        )
        root.add_widget(self.title_label)
        
        # Status indicator
        self.status_label = Label(
            text='Starting...',
            size_hint_y=0.05,
            foreground_color=(0.8, 0.8, 0.8, 1)
        )
        root.add_widget(self.status_label)
        
        # Video preview placeholder (will be replaced with actual stream)
        self.video_container = BoxLayout(
            size_hint_y=0.6,
            background_color=(0, 0, 0, 1)
        )
        root.add_widget(self.video_container)
        
        # Control buttons
        controls = BoxLayout(size_hint_y=0.15, spacing=10)
        self.start_button = Button(
            text='Start Streaming',
            size_hint_x=0.5,
            background_color=(0.2, 0.7, 0.2, 1)
        )
        self.stop_button = Button(
            text='Stop Streaming',
            size_hint_x=0.5,
            background_color=(0.7, 0.2, 0.2, 1),
            disabled=True
        )
        controls.add_widget(self.start_button)
        controls.add_widget(self.stop_button)
        root.add_widget(controls)
        
        # Settings panel (simplified for Android)
        settings = BoxLayout(size_hint_y=0.2, spacing=10, padding=5)
        Label(text='Resolution:', size_hint_x=0.3)
        self.res_label = Label(
            text=f'{Settings().camera.resolution_w}x{Settings().camera.resolution_h}',
            size_hint_x=0.7
        )
        settings.add_widget(Label(text='Resolution:'))
        settings.add_widget(self.res_label)
        root.add_widget(settings)
        
        # Bind button events
        self.start_button.bind(on_press=self._start_streaming)
        self.stop_button.bind(on_press=self._stop_streaming)
        
        return root
    
    async def _start_streaming(self, *args):
        """Start the streaming server."""
        try:
            # Initialize settings and camera
            settings = Settings()
            self.status_label.text = 'Initializing camera...'
            
            # Create frame source (MJPEG from FFmpeg subprocess)
            frame_source = await FrameSource.blank_stream(
                width=settings.camera.resolution_w,
                height=settings.camera.resolution_h
            )
            
            # Start the aiohttp server
            self.server = StreamServer(frame_source=frame_source, settings=settings)
            await self.server.start()
            
            self.status_label.text = f'Streaming at http://0.0.0.0:{settings.server.port}'
            self.start_button.disabled = True
            self.stop_button.disabled = False
            
        except Exception as e:
            logger.error(f'Failed to start streaming: {e}')
            self.status_label.text = f'Error: {str(e)}'
    
    async def _stop_streaming(self, *args):
        """Stop the streaming server."""
        if hasattr(self, 'server') and self.server:
            await self.server.stop()
            del self.server
            self.start_button.disabled = False
            self.stop_button.disabled = True
            self.status_label.text = 'Stopped'
    
    def on_stop(self):
        """Clean up when app is closed."""
        if hasattr(self, 'server') and self.server:
            asyncio.get_event_loop().run_until_complete(self._stop_streaming())


if __name__ == '__main__':
    SztreamerrApp().run()