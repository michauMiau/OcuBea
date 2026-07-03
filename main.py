"""Sztreamerr Android App entry point."""

from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.clock import Clock

class SztreamerrApp(App):
    def build(self):
        self.title = 'Sztreamerr'
        layout = BoxLayout(orientation='vertical', padding=10, spacing=10)
        
        # Title
        title = Label(
            text='Sztreamerr',
            font_size=32,
            bold=True,
            size_hint_y=0.15
        )
        layout.add_widget(title)
        
        # Status label
        self.status_label = Label(
            text='Initializing...',
            size_hint_y=0.05,
            foreground_color=(0.7, 0.7, 0.7, 1),
            font_size=14
        )
        layout.add_widget(self.status_label)
        
        # Info text
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
            from core import Settings
            from stream.server import StreamServer
            
            settings = Settings()
            self.status_label.text = f'Starting server on {settings.server.host}:{settings.server.port}...'
            
            # Start in background thread
            loop = asyncio.get_event_loop()
            asyncio.ensure_future(self.run_server(settings), loop=loop)
        except Exception as e:
            self.status_label.text = f'Error: {e}'
            import traceback
            traceback.print_exc()
    
    async def run_server(self, settings):
        try:
            # Create blank frame source for Android (no camera available)
            from stream.server import FrameSource as HttpFrameSource
            width = settings.camera.resolution_w
            height = settings.camera.resolution_h
            
            # Generate a simple test pattern
            async def _blank_generator():
                while True:
                    await asyncio.sleep(1/30)
                    yield b'\x00' * (width * height * 4)  # Black frame
            
            frame_source = HttpFrameSource(
                frames=_blank_generator(),
                width=width,
                height=height
            )
            
            server = StreamServer(frame_source=frame_source, settings=settings)
            await server.start()
            self.status_label.text = f'✅ Streaming at http://{settings.server.host}:{settings.server.port}'
        except Exception as e:
            self.status_label.text = f'Server error: {e}'
            import traceback
            traceback.print_exc()
    
    def on_stop(self):
        pass  # Cleanup if needed

if __name__ == '__main__':
    SztreamerrApp().run()