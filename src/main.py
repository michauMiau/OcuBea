"""Sztreamerr — IP Camera Streamer (Kivy Android App)."""

import os
import sys
import logging
from kivy.app import App as KivyApp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.clock import Clock
from http.server import HTTPServer, SimpleHTTPRequestHandler
import threading

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
            t = threading.Thread(target=self._run_httpd, daemon=True)
            t.start()
        except Exception as e:
            logger.error(f'Server thread error: {e}')
            self.status.text = f'Error: {str(e)}'

    def _run_httpd(self):
        try:
            class Handler(SimpleHTTPRequestHandler):
                def do_GET(self):
                    if self.path == '/':
                        self.send_response(200)
                        self.send_header('Content-Type', 'text/html')
                        self.end_headers()
                        self.wfile.write(b'<h1>Sztreamerr running!</h1>')
                    elif self.path.startswith('/stream'):
                        self.send_response(200)
                        self.send_header('Content-Type', 'multipart/x-mixed-replace; boundary=frame')
                        self.end_headers()
                        # Minimal JPEG frame (solid dark)
                        frame = b'\xff\xd8\xff\xe0' + b'\x00' * 100 + b'\xff\xd9'
                        self.wfile.write(frame)
                    else:
                        self.send_response(404)
                        self.end_headers()

            httpd = HTTPServer((SERVER_HOST, SERVER_PORT), Handler)
            self.status.text = f'✅ Running at http://{SERVER_HOST}:{SERVER_PORT}'
            logger.info(f'Server started on {SERVER_HOST}:{SERVER_PORT}')
        except Exception as e:
            logger.error(f'Server error: {e}')
            import traceback; traceback.print_exc()

    def on_stop(self):
        logger.info('App stopped')

if __name__ == '__main__':
    SztreamerrApp().run()