/** Feed tab — MJPEG video preview + live stats */

(function () {
  var video = document.getElementById('video');
  var statusEl = document.getElementById('status');
  
  // Connect to MJPEG stream endpoint
  if (video && navigator.mediaDevices) {
    video.src = '/api/mjpeg';
    
    video.addEventListener('playing', function () {
      setStatus(statusEl, 'green', '● Live');
    });
    
    video.addEventListener('error', function (e) {
      console.error('Video error:', e);
      setStatus(statusEl, 'red', '✗ No feed');
    });
  }

  function setStatus(el, color, text) {
    if (!el) return;
    el.className = 'badge ' + color;
    el.textContent = text;
  }

  // API call to update camera settings
  async function saveSettings(data) {
    try {
      var res = await fetch('/api/camera', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(data)
      });
      var result = await res.json();
      console.log('Settings saved:', result);
    } catch (err) {
      console.error('Failed to save settings:', err);
    }
  }

  // Form submission handler
  var form = document.getElementById('camera-form');
  if (form) {
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var framerate = parseInt(form.framerate.value, 10);
      var height = parseInt(form.height.value, 10);
      
      saveSettings({framerate: framerate, resolution_h: height});
    });
  }

  // Slider handlers for brightness/contrast/saturation
  document.querySelectorAll('.controls input[type="range"]').forEach(function (input) {
    input.addEventListener('change', function () {
      var name = this.name;
      var value = parseInt(this.value, 10);
      saveSettings({[name]: value});
    });
  });
})();
