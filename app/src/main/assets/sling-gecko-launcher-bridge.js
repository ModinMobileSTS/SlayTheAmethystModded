(function () {
    'use strict';
    var token = new URLSearchParams(location.search).get('geckoLauncher');
    if (!token) return;
    var base = location.pathname.substring(0, location.pathname.indexOf('/slingbreak/')) + '/bridge/' + token + '/';
    function send(method, detail) {
        fetch(base + method + '?detail=' + encodeURIComponent(String(detail || '').substring(0, 2000)), {
            cache: 'no-store', headers: { 'X-Sling-Bridge': '1' }
        }).catch(function () {});
    }
    window.AndroidSlingBreakLauncher = {
        onPageReady: function () { send('onPageReady'); },
        enterGame: function () { send('enterGame'); },
        scriptError: function (detail) { send('scriptError', detail); }
    };
    var stopped = false;
    window.addEventListener('pagehide', function () { stopped = true; });
    function poll() {
        if (stopped) return;
        fetch(base + 'poll', { cache: 'no-store', headers: { 'X-Sling-Bridge': '1' } })
            .then(function (response) {
                if (response.status === 410) { stopped = true; return []; }
                if (!response.ok) throw new Error('Launcher bridge HTTP ' + response.status);
                return response.json();
            }).then(function (scripts) {
                scripts.forEach(function (script) {
                    try { (0, eval)(script); } catch (error) { send('scriptError', error.message); }
                });
            }).catch(function () {}).then(function () { if (!stopped) setTimeout(poll, 100); });
    }
    poll();
}());
