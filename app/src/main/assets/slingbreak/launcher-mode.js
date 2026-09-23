(() => {
  'use strict';
  if (new URLSearchParams(location.search).get('launcher') !== '1') return;

  document.documentElement.classList.add('launcher-mode');
  const progress = document.getElementById('launcher-progress');
  const label = document.getElementById('launcher-progress-label');
  const percent = document.getElementById('launcher-progress-percent');
  const bar = document.getElementById('launcher-progress-bar');
  const readyButton = document.getElementById('launcher-ready-button');
  const track = document.getElementById('launcher-progress-track');
  const phase = document.getElementById('launcher-phase');
  const waiting = document.getElementById('launcher-waiting');
  const bridge = window.AndroidSlingBreakLauncher;
  let ready = false;
  let gameEntered = false;

  progress.hidden = false;
  const setProgress = (value, message) => {
    if (ready) return;
    const bounded = Math.max(0, Math.min(100, Number(value) || 0));
    if (message) label.textContent = message;
    percent.replaceChildren(document.createTextNode(Math.round(bounded)), Object.assign(document.createElement('span'), {textContent: '%'}));
    bar.style.width = bounded + '%';
    track.setAttribute('aria-valuenow', bounded);
    phase.textContent = bounded >= 100 ? '即将就绪' : '正在加载';
  };
  const enterGame = () => {
    if (!ready || gameEntered || !window.Game) return;
    gameEntered = true;
    window.Game.paused = false;
    window.Game.audio?.sync?.();
    window.Game.ui?.();
    document.documentElement.classList.add('launcher-entered');
    bridge?.enterGame?.();
  };
  const reportScriptFailure = event => {
    const error = event?.error;
    const detail = error?.stack || error?.message || event?.message || 'unknown script error';
    bridge?.scriptError?.(String(detail).slice(0, 500));
  };
  const setReady = () => {
    if (ready) return;
    setProgress(100, '游戏已就绪');
    ready = true;
    phase.textContent = '加载完成';
    waiting.hidden = true;
    progress.classList.add('is-ready');
    document.documentElement.classList.add('launcher-ready');
    readyButton.hidden = false;
    readyButton.focus({preventScroll:true});
  };
  readyButton.addEventListener('click', enterGame);
  window.SlingBreakLauncher = {setProgress, setReady, enterGame, pageReady: () => bridge?.onPageReady?.()};
  window.addEventListener('error', reportScriptFailure);
  window.addEventListener('unhandledrejection', event => {
    bridge?.scriptError?.(String(event?.reason || 'unhandled promise rejection').slice(0, 500));
  });
  window.lucide?.createIcons?.();
})();
