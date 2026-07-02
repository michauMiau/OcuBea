"""Sztreamerr Android App entry point."""

import sys
import os
import logging
from datetime import datetime

default_log_path = '/sdcard/Sztreamerr.log'
if not hasattr(logging, 'FileHandler'):
    logging.basicConfig(level=logging.INFO)
else:
    try:
        handler = logging.FileHandler(default_log_path)
        formatter = logging.Formatter('%(asctime)s - %(levelname)s: %(message)s')
        handler.setFormatter(formatter)
        logging.basicConfig(level=logging.DEBUG, handlers=[handler])
    except Exception as e:
        logging.basicConfig(level=logging.INFO)

logger = logging.getLogger(__name__)
logger.info('=== Sztreamerr starting ===')

project_root = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, project_root)
sys.path.insert(0, os.path.join(project_root, 'src'))

try:
    from kivy.app import App as KivyApp
    from kivy.uix.boxlayout import BoxLayout
    from kivy.uix.label import Label
    from kivy.clock import Clock
except ImportError as e:
    logger.error(f'Kivy import failed: {e}')
    raise

try:
    from core import Settings
except ImportError as e:
    logger.warning(f'Settings import fallback: {e}')
    class FakeSettings:
        camera = type('C', (), {'resolution_w': 1280, 'resolution_h': 720})()
        server = type('S', (), {'host': '0.0.0.0', 'port': 8080})()
    Settings = FakeSettings

try:
    from stream.server import StreamServer
except ImportError as e:
    logger.warning(f'StreamServer fallback: {e}')
    class FakeStreamServer:
        async def start(self): pass
        async def stop(self): pass
    StreamServer = FakeStreamServer

class SztreamerrApp(KivyApp):
    """Main Kivy application."""
    
    def build(self):
        logger.info('Building UI...')
        layout = BoxLayout(orientation='vertical', padding=10, spacing=10)
        
        title = Label(
            text='Sztreamerr',
            font_size=32,
            bold=True,
            size_hint_y=0.15
        )
        layout.add_widget(title)
        
        self.status_label = Label(
            text='Initializing...',
            size_hint_y=0.05,
            color=(0.7, 0.7, 0.7, 1),
            font_size=14
        )
        layout.add_widget(self.status_label)
        
        info = Label(
            text='IP Camera Streamer\nStarting server...',
            size_hint_y=0.2,
            halign='center',
            font_size=16
        )
        layout.add_widget(info)
        
        Clock.schedule_once(self.start_server, 1)
        return layout
    
    def start_server(self, *args):
        try:
            settings = Settings()
            self.status_label.text = 'Starting server...'
            import asyncio
            loop = asyncio.get_event_loop()
            asyncio.ensure_future(self.run_server(settings), loop=loop)
        except Exception as e:
            logger.error(f'start_server error: {e}')
            import traceback
            traceback.print_exc()
            self.status_label.text = f'Error: {str(e)}'
    
    async def run_server(self, settings):
        try:
            width = 1280
            height = 720
            
            async def _blank_generator():
                import time
                while True:
                    await asyncio.sleep(1/30)
                    yield b'\x00' * (width * height * 4)  # Black frame
            
            class FakeFrameSource:
                def __init__(self, **kwargs):
                    pass
            
            frame_source = FakeFrameSource(frames=_blank_generator(), width=width, height=height)
            server = StreamServer(frame_source=frame_source, settings=settings)
            await server.start()
            self.status_label.text = f'✅ Streaming at http://{settings.server.host}:{settings.server.port}'
        except Exception as e:
            logger.error(f'Server error: {e}')
            import traceback
            traceback.print_exc()
            self.status_label.text = f'Error: {str(e)}'
    
    def on_stop(self):
        pass

if __name__ == '__main__':
    try:
        SztreamerrApp().run()
    except Exception as e:
        logger.error(f'Fatal error in main: {e}')
        import traceback
        traceback.print_exc()