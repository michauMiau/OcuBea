"""Sztreamerr — IP Camera Streamer (Kivy Android App)."""

import os
import sys
import logging
from kivy.app import App as KivyApp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.clock import Clock
import asyncio

CAMERA_W = 1280
CAMERA_H = 720
SERVER_HOST = "0.0.0.0"
SERVER_PORT = 8080

default_log_path = '/sdcard/Sztreamerr.log'
try:
    handler = logging.FileHandler(default_log_path)
except Exception:
    from logging import NullHandler as handler
formatter = logging.Formatter('%(asctime)s %(levelname)s: %(message)s')
handler.setFormatter(formatter)
logging.basicConfig(level=logging.INFO, handlers=[handler])
logger = logging.getLogger(__name__)


class SztreamerrApp(KivyApp):
    def build(self):
        layout = BoxLayout(orientation='vertical', padding=10, spacing=10)

        layout.add_widget(Label(
            text='Sztreamerr 📹', font_size=28, bold=True, size_hint_y=0.15))

        self.status = Label(text='Starting...', color=(0.7, 0.7, 0.7, 1),
                            size_hint_y=0.05, font_size=14)
        layout.add_widget(self.status)

        info = Label(
            text=f'Port: {SERVER_PORT}\nResolution: {CAMERA_W}x{CAMERA_H}',
            size_hint_y=0.2, halign='center', font_size=15)
        layout.add_widget(info)

        Clock.schedule_once(self._start_server, 0.5)
        return layout

    def _start_server(self, *args):
        self.status.text = 'Starting server...'
        try:
            loop = asyncio.get_event_loop()
            loop.create_task(self._run_server())
        except Exception as e:
            logger.error(f'async error: {e}')

    async def _run_server(self):
        try:
            from aiohttp import web

            async def index(request):
                return web.Response(text='Sztreamerr running!')

            async def mjpeg(request):
                async def gen():
                    while True:
                        yield b'\xff\xd8\xff\xe0' + b'\x00' * 100 + b'\xff\xd9'
                        await asyncio.sleep(1 / 30)
                return web.StreamResponse(
                    headers={
                        'Content-Type': 'multipart/x-mixed-replace; boundary=frame',
                        'Cache-Control': 'no-cache',
                    })

            app = web.Application()
            app.router.add_get('/', index)
            app.router.add_get('/stream', mjpeg)

            runner = web.AppRunner(app)
            await runner.setup()
            site = web.TCPSite(runner, SERVER_HOST, SERVER_PORT)
            await site.start()

            self.status.text = f'✅ Running at http://{SERVER_HOST}:{SERVER_PORT}'
        except Exception as e:
            logger.error(f'Server error: {e}')
            import traceback; traceback.print_exc()
            self.status.text = f'Error: {str(e)}'


if __name__ == '__main__':
    SztreamerrApp().run()