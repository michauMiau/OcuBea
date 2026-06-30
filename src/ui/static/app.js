document.querySelectorAll('.tabs button').forEach(function (btn) {
  btn.addEventListener('click', function () {
    document.querySelectorAll('.tabs button').forEach(function (b) { b.classList.remove('active'); });
    this.classList.add('active');
    var tab = this.getAttribute('data-tab');
    document.getElementById('tab-feed').hidden = tab !== 'feed';
    document.getElementById('tab-settings').hidden = tab !== 'settings';
  });
});

document.getElementById('camera-form').addEventListener('submit', function (e) {
  e.preventDefault();
  var data = Object.fromEntries(new FormData(e.target));
  fetch('/api/camera', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) })
    .then(function (r) { return r.json(); })
    .then(function (j) { if (j.status === 'ok') alert('Zapisano!'); else alert(j.message); });
});
