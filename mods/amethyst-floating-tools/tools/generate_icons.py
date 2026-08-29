#!/usr/bin/env python3
"""Regenerate the Amethyst Floating Tools icon set.

All icons are drawn from vector primitives at 4x and downsampled, so the whole
set shares one stroke weight and one optical size. Icons are pure white with an
alpha channel because the mod tints them at draw time.

Usage: python3 mods/amethyst-floating-tools/tools/generate_icons.py
"""

import math
import os

from PIL import Image, ImageDraw

SIZE = 128
SS = 4                      # supersampling factor
STROKE = 9.0                # default stroke weight in 128-space
WHITE = (255, 255, 255, 255)

OUT_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "src", "main", "resources", "amethystFloatingTools", "images", "tools",
)


def _s(v):
    return v * SS


def new_canvas():
    return Image.new("RGBA", (SIZE * SS, SIZE * SS), (255, 255, 255, 0))


def stroke_path(draw, pts, w=STROKE, closed=False):
    """Polyline with round caps and joins."""
    pw = int(round(_s(w)))
    scaled = [(_s(x), _s(y)) for x, y in pts]
    if closed:
        scaled = scaled + [scaled[0]]
    draw.line(scaled, fill=WHITE, width=pw)
    r = pw / 2.0
    for x, y in scaled:
        draw.ellipse([x - r, y - r, x + r, y + r], fill=WHITE)


def circle_outline(draw, cx, cy, radius, w=STROKE):
    pw = int(round(_s(w)))
    box = [_s(cx - radius), _s(cy - radius), _s(cx + radius), _s(cy + radius)]
    draw.ellipse(box, outline=WHITE, width=pw)


def ellipse_outline(draw, cx, cy, rx, ry, w=STROKE):
    pw = int(round(_s(w)))
    box = [_s(cx - rx), _s(cy - ry), _s(cx + rx), _s(cy + ry)]
    draw.ellipse(box, outline=WHITE, width=pw)


def rrect_outline(draw, x0, y0, x1, y1, radius, w=STROKE):
    pw = int(round(_s(w)))
    draw.rounded_rectangle(
        [_s(x0), _s(y0), _s(x1), _s(y1)],
        radius=_s(radius), outline=WHITE, width=pw,
    )


def rrect_fill(draw, x0, y0, x1, y1, radius):
    draw.rounded_rectangle(
        [_s(x0), _s(y0), _s(x1), _s(y1)], radius=_s(radius), fill=WHITE,
    )


def dot(draw, cx, cy, radius):
    draw.ellipse(
        [_s(cx - radius), _s(cy - radius), _s(cx + radius), _s(cy + radius)],
        fill=WHITE,
    )


def arc_stroke(draw, cx, cy, radius, start_deg, end_deg, w=STROKE):
    """Arc with round caps."""
    pw = int(round(_s(w)))
    box = [_s(cx - radius), _s(cy - radius), _s(cx + radius), _s(cy + radius)]
    draw.arc(box, start_deg, end_deg, fill=WHITE, width=pw)
    r = pw / 2.0
    for deg in (start_deg, end_deg):
        ex = _s(cx + radius * math.cos(math.radians(deg)))
        ey = _s(cy + radius * math.sin(math.radians(deg)))
        draw.ellipse([ex - r, ey - r, ex + r, ey + r], fill=WHITE)


# ---------------------------------------------------------------- icons


def icon_online(d):
    """Globe: sphere with a meridian and an equator."""
    circle_outline(d, 64, 64, 42)
    ellipse_outline(d, 64, 64, 20, 42, w=7.5)
    stroke_path(d, [(24, 64), (104, 64)], w=7.5)


def icon_mouse(d):
    """Mouse body with a button split."""
    rrect_outline(d, 42, 16, 86, 112, radius=22)
    stroke_path(d, [(42, 58), (86, 58)], w=7.5)
    stroke_path(d, [(64, 20), (64, 58)], w=7.5)


def icon_keyboard(d):
    """Keyboard tray with two key rows and a spacebar."""
    rrect_outline(d, 10, 36, 118, 92, radius=11, w=8.0)
    for x in (28, 46, 64, 82, 100):
        dot(d, x, 54, 4.6)
    for x in (28, 46, 64, 82, 100):
        dot(d, x, 68, 4.6)
    rrect_fill(d, 44, 78, 84, 84, radius=3)


def icon_add_key(d):
    """A keycap plus an add sign."""
    rrect_outline(d, 12, 34, 78, 100, radius=13)
    dot(d, 45, 67, 6.5)
    stroke_path(d, [(98, 30), (98, 62)], w=9.5)
    stroke_path(d, [(82, 46), (114, 46)], w=9.5)


def icon_ctrl(d):
    """Control chevron, drawn heavy so it carries the same weight as the rest."""
    stroke_path(d, [(26, 86), (64, 38), (102, 86)], w=13.0)


def icon_shift(d):
    """Hollow shift arrow."""
    stroke_path(
        d,
        [(64, 20), (28, 58), (47, 58), (47, 104), (81, 104), (81, 58), (100, 58)],
        closed=True,
    )


def icon_tab(d):
    """Arrow running into a stop bar."""
    stroke_path(d, [(24, 64), (82, 64)])
    stroke_path(d, [(64, 46), (82, 64), (64, 82)])
    stroke_path(d, [(102, 40), (102, 88)])


def icon_alt(d):
    """Option/Alt glyph: stepped path plus an independent top-right bar."""
    stroke_path(d, [(16, 44), (46, 44), (82, 88), (112, 88)])
    stroke_path(d, [(72, 44), (112, 44)])


def icon_lock(d):
    """Padlock."""
    arc_stroke(d, 64, 48, 23, 180, 360, w=8.5)
    rrect_outline(d, 28, 46, 100, 100, radius=12)
    dot(d, 64, 70, 6.0)
    stroke_path(d, [(64, 72), (64, 84)], w=8.0)


def icon_wheel(d):
    """Gear: eight teeth around a hollow hub."""
    cx, cy, outer, inner, teeth = 64, 64, 40, 26, 8
    points = []
    for i in range(teeth * 2):
        angle = math.radians(360.0 / (teeth * 2) * i - 90)
        r = outer if i % 2 == 0 else inner
        points.append((_s(cx + math.cos(angle) * r), _s(cy + math.sin(angle) * r)))
    d.polygon(points + [points[0]], fill=WHITE)
    hub = int(round(_s(inner - 10)))
    d.ellipse(
        [_s(cx) - hub, _s(cy) - hub, _s(cx) + hub, _s(cy) + hub],
        fill=(255, 255, 255, 0),
    )


ICONS = {
    "online": icon_online,
    "mouse": icon_mouse,
    "keyboard": icon_keyboard,
    "add_key": icon_add_key,
    "ctrl": icon_ctrl,
    "shift": icon_shift,
    "tab": icon_tab,
    "alt": icon_alt,
    "lock": icon_lock,
    "wheel": icon_wheel,
}


def render(name):
    img = new_canvas()
    ICONS[name](ImageDraw.Draw(img))
    return img.resize((SIZE, SIZE), Image.LANCZOS)


def main():
    out = os.path.normpath(OUT_DIR)
    for name in sorted(ICONS):
        img = render(name)
        path = os.path.join(out, name + ".png")
        img.save(path)
        print("wrote", os.path.relpath(path))


if __name__ == "__main__":
    main()
