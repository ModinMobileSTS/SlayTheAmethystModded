"use strict";

// Desktop Steam: modules 15148 / 7474. These are window-position animations, not CSS easing.
const timeline = Object.freeze({ enter: 300, hold: 5000, exit: 300, height: 70, maxVisible: 3 });
const active = [];
let frame = null;

function progressAt(elapsed) {
    if (elapsed < timeline.enter) return Math.max(0, elapsed / timeline.enter);
    if (elapsed < timeline.enter + timeline.hold) return 1;
    return Math.max(0, 1 - (elapsed - timeline.enter - timeline.hold) / timeline.exit);
}

function drawStack(now) {
    frame = null;
    const finished = [];
    for (let i = active.length - 1; i >= 0; i--) {
        if (active[i].ready && now - active[i].startedAt >= timeline.enter + timeline.hold + timeline.exit) {
            const toast = active.splice(i, 1)[0];
            toast.element.remove();
            finished.unshift(toast.token);
        }
    }
    const visible = active.filter(toast => toast.ready);
    const progress = visible.map(toast => progressAt(now - toast.startedAt));
    const stackHeight = progress.reduce((sum, p) => sum + Math.ceil(timeline.height * p), 0);
    let y = timeline.height * timeline.maxVisible - stackHeight;
    visible.forEach((toast, index) => {
        const offset = Math.floor(timeline.height * (1 - progress[index]));
        toast.element.style.transform = `translateY(${y + offset}px)`;
        toast.element.style.visibility = "visible";
        const position = visible.length > 2 && index + 1 === visible.length ? "Bottom"
            : visible.length > 1 && index === 0 ? "Top" : "Middle";
        toast.element.className = `DesktopToastPopup ${position}`;
        y += timeline.height - offset;
    });
    for (const token of finished) window.SteamAchievementHost?.finished(token);
    if (visible.length) frame = requestAnimationFrame(drawStack);
}

window.setAchievement = async function (entry) {
    const element = document.getElementById("toast-template").content.firstElementChild.cloneNode(true);
    const title = element.querySelector(".Name");
    const description = element.querySelector(".Desc");
    const artwork = element.querySelector(".StandardLogoDimensions");
    title.textContent = entry.title;
    description.textContent = entry.description;
    const wrapper = document.createElement("div");
    wrapper.className = "AchievementIconWrapper";
    if (entry.rare) {
        const root = document.createElement("div");
        root.className = "RareAchievementIconGlowContainerRoot";
        const container = document.createElement("div");
        container.className = "RareAchievementIconGlowContainer";
        const glow = document.createElement("div");
        glow.className = "RareAchievementIconGlow";
        container.appendChild(glow);
        root.appendChild(container);
        wrapper.appendChild(root);
    }
    const icon = document.createElement("img");
    icon.className = entry.rare ? "Icon IconGlow" : "Icon";
    icon.alt = "";
    icon.src = entry.image;
    wrapper.appendChild(icon);
    artwork.appendChild(wrapper);
    document.getElementById("stack").appendChild(element);
    // Preserve the host's event order even when different icons finish decoding out of order.
    const toast = { element, token: entry.token, startedAt: 0, ready: false };
    active.push(toast);
    await Promise.all([document.fonts.ready, icon.decode().catch(() => {})]);
    const titleLines = Math.max(1, Math.round(title.clientHeight / 16));
    description.style.webkitLineClamp = String(3 - titleLines);
    toast.startedAt = performance.now();
    toast.ready = true;
    // Do not await an off-screen frame before telling the host: Android can throttle it.
    window.SteamAchievementHost?.prepared(entry.token);
    if (frame === null) frame = requestAnimationFrame(drawStack);
};

Promise.all([
    document.fonts.load('400 12px "Motiva Sans"'),
    document.fonts.load('300 12px "Motiva Sans"'),
]).then(() => window.SteamAchievementHost?.ready());
