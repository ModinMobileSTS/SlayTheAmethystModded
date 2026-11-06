const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const root = path.resolve(__dirname, '../../../..');
const assets = path.join(root, 'app/src/main/assets/steam-achievement');
const out = path.join(root, 'agent-tmp/steam-achievement-reference');
fs.mkdirSync(out, { recursive: true });
const image = 'data:image/jpeg;base64,' + fs.readFileSync(path.join(root,
    'app/src/main/res/drawable-nodpi/achievement_eternal_one_locked.jpg')).toString('base64');
const origin = 'https://steam-achievement.invalid/';
const mime = { '.html': 'text/html', '.css': 'text/css', '.js': 'application/javascript',
    '.png': 'image/png', '.svg': 'image/svg+xml', '.ttf': 'font/ttf' };

let browser;
(async () => {
    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage({ viewport: { width: 283, height: 210 }, deviceScaleFactor: 1 });
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    page.on('console', msg => { if (msg.type() === 'error') errors.push(msg.text()); });
    await page.route(origin + '**', async route => {
        const file = new URL(route.request().url()).pathname.slice(1) || 'notification.html';
        if (file.includes('..') || file.includes('/')) return route.abort();
        const location = path.join(assets, file);
        await route.fulfill({ contentType: mime[path.extname(file)], body: fs.readFileSync(location) });
    });
    await page.addInitScript(() => {
        window.testTime = 0;
        window.events = [];
        Object.defineProperty(performance, 'now', { value: () => window.testTime });
        const callbacks = new Map();
        let nextFrame = 0;
        window.requestAnimationFrame = callback => { callbacks.set(++nextFrame, callback); return nextFrame; };
        window.cancelAnimationFrame = id => callbacks.delete(id);
        window.step = time => {
            window.testTime = time;
            const pending = [...callbacks.values()];
            callbacks.clear();
            for (const callback of pending) callback(time);
        };
        window.SteamAchievementHost = {
            ready: () => { window.rendererReady = true; },
            prepared: token => window.events.push(['prepared', token]),
            finished: token => window.events.push(['finished', token]),
        };
    });
    const load = async () => {
        await page.goto(origin);
        await page.waitForFunction(() => window.rendererReady);
    };
    const add = async (token, rare, title = 'Eternal One', description = 'Obtain all other Achievements.') => {
        await page.evaluate(entry => window.setAchievement(entry), { token, rare, title, description, image });
    };
    const tick = async time => page.evaluate(time => window.step(time), time);
    const positions = async () => page.locator('.DesktopToastPopup').evaluateAll(elements => elements.map(e => ({
        y: e.getBoundingClientRect().y, w: e.offsetWidth, h: e.offsetHeight, cls: e.className,
    })));
    await load();
    await add(1, false);
    for (const [time, expectedY] of [[0, 280], [150, 210], [300, 140], [5300, 140], [5450, 210]]) {
        await tick(time);
        assert.equal((await positions())[0].y, expectedY, `single @${time}`);
    }
    await tick(5600);
    assert.equal((await positions()).length, 0);
    assert.deepEqual(await page.evaluate(() => window.events), [['prepared', 1], ['finished', 1]]);
    console.log('PASS single timeline and bridge callbacks');

    await load();
    await add(1, true);
    await add(2, false, 'Ironclad Victory', 'Beat the game with the Ironclad.');
    await add(3, true, '测试很长很长的中文标题必须占用两行并且正确截断', '这是很长的中文说明，必须使用剩余的一行，不得挤出通知框。');
    await tick(300);
    const stack = await positions();
    assert.deepEqual(stack.map(p => p.y), [0, 70, 140]);
    assert.deepEqual(stack.map(p => p.cls), ['DesktopToastPopup Top', 'DesktopToastPopup Middle', 'DesktopToastPopup Bottom']);
    assert.ok(stack.every(p => p.w === 283 && p.h === 70));
    const measurements = await page.evaluate(() => [...document.querySelectorAll('.DesktopToastPopup')].map(e => {
        const title = e.querySelector('.Name');
        const desc = e.querySelector('.Desc');
        return { titleLines: title.clientHeight / 16, descLines: desc.clientHeight / 16,
            descClamp: getComputedStyle(desc).webkitLineClamp,
            iconWidth: e.querySelector('.Icon').clientWidth,
            rare: e.querySelectorAll('.RareAchievementIconGlow').length,
            fonts: document.fonts.check('300 12px "Motiva Sans"') && document.fonts.check('400 12px "Motiva Sans"'),
        };
    }));
    assert.deepEqual(measurements.map(m => m.rare), [1, 0, 1]);
    assert.ok(measurements.every(m => m.iconWidth === 44 && m.fonts));
    assert.ok(measurements.every(m => m.titleLines + m.descLines <= 3));
    assert.equal(measurements[2].descClamp, '1');
    await page.evaluate(() => document.getAnimations().forEach(a => { a.pause(); a.currentTime = 1500; }));
    await page.screenshot({ path: path.join(out, 'three-toasts.png'), omitBackground: true });
    console.log('PASS three-toast stack, original fonts, rare-only glow, CJK line clamp', measurements);
    await tick(5600);
    assert.equal((await positions()).length, 0);
    assert.equal((await page.evaluate(() => window.events)).filter(e => e[0] === 'finished').length, 3);

    await load();
    await add(1, false);
    await tick(1000);
    await add(2, true);
    await tick(1300);
    assert.deepEqual((await positions()).map(p => p.y), [70, 140]);
    await tick(5600);
    assert.deepEqual((await positions()).map(p => p.y), [140]);
    assert.equal((await page.evaluate(() => window.events)).filter(e => e[0] === 'finished').length, 1);
    await tick(6600);
    assert.equal((await positions()).length, 0);
    console.log('PASS independently timed overlapping notifications');
    assert.deepEqual(errors, []);
    console.log('PASS no browser errors or blocked resource loads; Chromium', await browser.version());
    await browser.close();
})().catch(async error => {
    console.error(error);
    await browser?.close();
    process.exitCode = 1;
});
