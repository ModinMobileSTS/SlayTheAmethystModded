(() => {
  'use strict';
  const dialog = document.getElementById('game-intro');
  const canvas = document.getElementById('intro-canvas');
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const intro = window.SlingBreakIntro;
  const reveal = () => document.documentElement.classList.remove('intro-pending');
  if (reduced.matches) { intro.active = false; reveal(); Game.ui?.(); return; }
  const ctx = canvas.getContext('2d');
  if (!ctx) { intro.active = false; reveal(); Game.ui?.(); return; }

  const ratio = Math.min(devicePixelRatio || 1, 2);
  canvas.width = 640 * ratio;
  canvas.height = 460 * ratio;
  ctx.scale(ratio, ratio);
  const colors = ['#b6ed66', '#f67b65', '#dce4d4'];
  const blocks = Array.from({length: 15}, (_, i) => ({
    x: 192 + (i % 5) * 53,
    y: 68 + Math.floor(i / 5) * 43,
    color: colors[(i + Math.floor(i / 5)) % 3]
  }));
  let frame = 0, deadline = 0, exitTimer = 0, finished = false;
  const start = performance.now();
  const clamp = value => Math.min(1, Math.max(0, value));
  const ease = value => 1 - (1 - clamp(value)) ** 3;

  function finish() {
    if (finished) return;
    finished = true;
    cancelAnimationFrame(frame);
    clearTimeout(deadline);
    clearTimeout(exitTimer);
    reduced.removeEventListener('change', onReduced);
    document.removeEventListener('visibilitychange', onVisibility);
    intro.active = false;
    dialog.close();
    dialog.classList.remove('is-leaving');
    Game.ui?.();
    if (Game.phase !== 'draft') document.getElementById('game').focus({preventScroll: true});
  }
  function leave() {
    if (finished || dialog.classList.contains('is-leaving')) return;
    dialog.classList.add('is-leaving');
    exitTimer = setTimeout(finish, 340);
  }
  function onReduced() { if (reduced.matches) finish(); }
  function onVisibility() { if (document.hidden) finish(); }
  function line(points, color, width) {
    ctx.beginPath();
    points.forEach(([x, y], i) => i ? ctx.lineTo(x, y) : ctx.moveTo(x, y));
    ctx.strokeStyle = color;
    ctx.lineWidth = width;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.stroke();
  }
  function draw(now) {
    if (finished) return;
    const t = now - start;
    const pull = ease((t - 200) / 650);
    const flight = clamp((t - 920) / 240);
    const burst = clamp((t - 1160) / 700);
    ctx.clearRect(0, 0, 640, 460);

    ctx.globalAlpha = .55;
    for (let x = 136; x <= 504; x += 23) {
      for (let y = 45; y < 420; y += 23) {
        ctx.fillStyle = '#cdd3c6';
        ctx.fillRect(x, y, 1.5, 1.5);
      }
    }
    ctx.globalAlpha = 1;
    blocks.forEach((block, i) => {
      const reveal = ease((t - i * 22) / 380);
      ctx.save();
      if (!burst) {
        ctx.globalAlpha = reveal;
        ctx.translate(block.x + 22, block.y + 16 + (1 - reveal) * 18);
        ctx.scale(reveal, reveal);
        ctx.fillStyle = block.color;
        ctx.fillRect(-22, -16, 44, 32);
        ctx.fillStyle = '#ffffff70';
        ctx.fillRect(-17, -11, 34, 3);
      } else {
        // Deterministic fragments keep the same rhythm on every entry.
        const progress = ease(burst);
        for (let part = 0; part < 4; part++) {
          ctx.save();
          const vx = (i % 5 - 2) * 65 + (part % 2 ? 20 : -20);
          const vy = (Math.floor(i / 5) - 2) * 58 - (part < 2 ? 35 : 0);
          ctx.translate(block.x + 11 + (part % 2) * 22 + vx * progress,
            block.y + 8 + Math.floor(part / 2) * 16 + vy * progress + 65 * burst * burst);
          ctx.rotate((i % 2 ? 1 : -1) * burst * (part + 1));
          ctx.globalAlpha = 1 - burst;
          ctx.fillStyle = block.color;
          ctx.fillRect(-9, -6, 18, 12);
          ctx.restore();
        }
      }
      ctx.restore();
    });

    const anchorY = 326 + (flight ? (1 - ease(flight * 3)) : 1) * pull * 47;
    ctx.globalAlpha = 1 - ease((t - 1620) / 400);
    line([[320, 343], [320, 399]], '#343d2d', 14);
    line([[276, 314], [290, 348], [320, 366], [350, 348], [364, 314]], '#343d2d', 10);
    line([[276, 314], [320, anchorY], [364, 314]], '#91bd55', 4);
    if (!burst) {
      const arrowY = 326 + pull * 47 - flight * 210;
      if (flight > 0) {
        ctx.globalAlpha *= .25;
        line([[320, arrowY + 18], [320, arrowY + 75]], '#8fbd4f', 6);
        ctx.globalAlpha = 1;
      }
      line([[320, arrowY], [320, arrowY - 66]], '#343d2d', 3);
      line([[311, arrowY - 55], [320, arrowY - 68], [329, arrowY - 55]], '#343d2d', 3);
      line([[312, arrowY - 4], [320, arrowY + 3], [328, arrowY - 4]], '#f67b65', 3);
    }
    ctx.globalAlpha = 1;
    if (t >= 1860) leave();
    else frame = requestAnimationFrame(draw);
  }

  intro.active = true;
  dialog.showModal();
  reveal();
  document.getElementById('intro-skip').addEventListener('click', finish);
  dialog.addEventListener('cancel', event => { event.preventDefault(); finish(); });
  dialog.addEventListener('keydown', event => { if (event.key === 'Escape') event.stopPropagation(); });
  reduced.addEventListener('change', onReduced);
  document.addEventListener('visibilitychange', onVisibility);
  frame = requestAnimationFrame(draw);
  // A suspended frame loop must never leave the entry screen blocking play.
  deadline = setTimeout(finish, 2600);
})();
