#!/usr/bin/env python3
"""Generates every texture Troll Client ships with.

Pure standard library (no PIL) so it runs anywhere:
    python3 tools/gen_assets.py
"""
import math
import os
import random
import struct
import zlib

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "trollclient")
GUI = os.path.join(ROOT, "textures", "gui")
FONT_TEX = os.path.join(ROOT, "textures", "font")
FONT_DEF = os.path.join(ROOT, "font")


def write_png(path, w, h, pixels):
    """pixels: list of (r, g, b, a) tuples, row-major."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            raw.extend(pixels[y * w + x])

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


# ---------------------------------------------------------------- overlays

def gen_overlays():
    rnd = random.Random(1337)
    n = 256
    # film grain: grey noise, full alpha (tinted down at draw time)
    write_png(os.path.join(GUI, "grain.png"), n, n,
              [(v, v, v, 255) for v in (rnd.randint(0, 255) for _ in range(n * n))])
    # scanlines: every other row black
    write_png(os.path.join(GUI, "scanlines.png"), n, n,
              [(0, 0, 0, 255 if (i // n) % 2 == 0 else 0) for i in range(n * n)])
    # checkerboard dither, used for old-school drop shadows
    write_png(os.path.join(GUI, "dither.png"), n, n,
              [(255, 255, 255, 255 if ((i % n) + (i // n)) % 2 == 0 else 0) for i in range(n * n)])
    # vignette: transparent centre, dark corners
    px = []
    for y in range(n):
        for x in range(n):
            dx = (x + 0.5) / n * 2 - 1
            dy = (y + 0.5) / n * 2 - 1
            d = min(1.0, math.sqrt(dx * dx + dy * dy) / math.sqrt(2))
            a = int(255 * max(0.0, (d - 0.35) / 0.65) ** 1.8)
            px.append((0, 0, 0, a))
    write_png(os.path.join(GUI, "vignette.png"), n, n, px)


# ---------------------------------------------------------------- icons
# 32x32 cells, drawn at 16x16 GUI units. Shapes are rasterised with 4x4
# supersampling so they stay crisp on high GUI scales.

CELL = 32
ICONS = ["combat", "movement", "troll", "player", "world", "client",
         "search", "gear", "close", "check", "keyboard", "palette",
         "chevron", "star", "eye", "power",
         "chat", "skull", "radar", "cube"]


def ring(cx, cy, r, w):
    return lambda x, y: abs(math.hypot(x - cx, y - cy) - r) <= w / 2


def disc(cx, cy, r):
    return lambda x, y: math.hypot(x - cx, y - cy) <= r


def seg(x0, y0, x1, y1, w):
    def f(x, y):
        vx, vy = x1 - x0, y1 - y0
        t = max(0.0, min(1.0, ((x - x0) * vx + (y - y0) * vy) / (vx * vx + vy * vy)))
        return math.hypot(x - (x0 + vx * t), y - (y0 + vy * t)) <= w / 2
    return f


def box(x0, y0, x1, y1):
    return lambda x, y: x0 <= x <= x1 and y0 <= y <= y1


def union(*fs):
    return lambda x, y: any(f(x, y) for f in fs)


def minus(a, b):
    return lambda x, y: a(x, y) and not b(x, y)


def icon_shape(name):
    c = 16
    if name == "combat":  # crosshair
        return union(ring(c, c, 10, 2.6), disc(c, c, 2),
                     seg(c, 2, c, 9, 2.6), seg(c, 23, c, 30, 2.6),
                     seg(2, c, 9, c, 2.6), seg(23, c, 30, c, 2.6))
    if name == "movement":  # double chevron
        return union(seg(6, 7, 14, 16, 3), seg(14, 16, 6, 25, 3),
                     seg(16, 7, 24, 16, 3), seg(24, 16, 16, 25, 3))
    if name == "troll":  # mischievous grin
        def grin(x, y):
            d = math.hypot(x - c, y - 13)
            return 9 <= d <= 12 and y > 18 and abs(x - c) < 11
        eyes = union(seg(8, 10, 13, 12, 2.5), seg(24, 10, 19, 12, 2.5))
        return union(ring(c, c, 13.5, 2.4), eyes, grin, seg(9, 20, 23, 20, 2))
    if name == "player":
        return union(disc(c, 10, 5.5),
                     minus(disc(c, 30, 12), box(0, 30, 32, 40)))
    if name == "world":
        def merid(x, y):
            return abs(((x - c) / 5.0) ** 2 + ((y - c) / 12.5) ** 2 - 1) < 0.22
        return union(ring(c, c, 12.5, 2.4), merid, seg(4, c, 28, c, 2.2),
                     seg(6, 10, 26, 10, 1.8), seg(6, 22, 26, 22, 1.8))
    if name in ("client", "gear"):
        def gear(x, y):
            a = math.atan2(y - c, x - c)
            r = math.hypot(x - c, y - c)
            outer = 12.5 if math.cos(a * 8) > 0.2 else 9.5
            return 5 <= r <= outer
        return gear
    if name == "search":
        return union(ring(13, 13, 8, 3), seg(19, 19, 28, 28, 4))
    if name == "close":
        return union(seg(7, 7, 25, 25, 3.5), seg(25, 7, 7, 25, 3.5))
    if name == "check":
        return union(seg(5, 17, 12, 24, 4), seg(12, 24, 27, 8, 4))
    if name == "keyboard":
        keys = [box(x, y, x + 3, y + 3) for x in (6, 11, 16, 21) for y in (10, 15)]
        return union(minus(box(3, 7, 29, 25), box(5, 9, 27, 23)), box(9, 20, 23, 22), *keys)
    if name == "palette":
        holes = union(disc(10, 12, 2.4), disc(16, 9, 2.4), disc(22, 12, 2.4), disc(21, 20, 3))
        return minus(disc(c, c, 13), holes)
    if name == "chevron":
        return union(seg(11, 6, 21, 16, 3.5), seg(21, 16, 11, 26, 3.5))
    if name == "star":
        pts = []
        for i in range(10):
            ang = -math.pi / 2 + i * math.pi / 5
            r = 13 if i % 2 == 0 else 5.5
            pts.append((c + r * math.cos(ang), c + 1 + r * math.sin(ang)))

        def star(x, y):
            inside = False
            j = len(pts) - 1
            for i in range(len(pts)):
                xi, yi = pts[i]
                xj, yj = pts[j]
                if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
                    inside = not inside
                j = i
            return inside
        return star
    if name == "eye":
        def lens(x, y):
            return ((x - c) / 14) ** 2 + ((y - c) / 8) ** 2 <= 1
        return union(minus(lens, lambda x, y: ((x - c) / 11.5) ** 2 + ((y - c) / 5.8) ** 2 <= 1), disc(c, c, 4))
    if name == "power":
        def arc(x, y):
            d = math.hypot(x - c, y - c + -1)
            return 9.5 <= d <= 12.5 and not (y < c and abs(x - c) < 5)
        return union(arc, seg(c, 3, c, 15, 3.2))
    if name == "chat":  # speech bubble with three dots and a tail
        bubble = lambda x, y: ((x - c) / 13.5) ** 2 + ((y - 14) / 10) ** 2 <= 1
        inner = lambda x, y: ((x - c) / 11) ** 2 + ((y - 14) / 7.6) ** 2 <= 1
        def tail(x, y):
            return 7 <= x <= 13 and 20 <= y <= 28 and (x - 7) >= (y - 20) * 0.7
        dots = union(disc(10, 14, 2), disc(c, 14, 2), disc(22, 14, 2))
        return union(minus(union(bubble, tail), inner), dots)
    if name == "skull":
        head = union(disc(c, 13, 11), box(9, 16, 23, 25))
        holes = union(disc(11.5, 14, 3.2), disc(20.5, 14, 3.2),
                      lambda x, y: abs(x - c) <= 1.6 - (y - 18) * 0.6 and 18 <= y <= 21,
                      box(12.2, 24, 13.8, 27), box(15.2, 24, 16.8, 27), box(18.2, 24, 19.8, 27))
        return minus(union(head, box(11, 24, 21, 28)), holes)
    if name == "radar":
        def sweep(x, y):
            a = math.atan2(y - c, x - c)
            r = math.hypot(x - c, y - c)
            return r <= 12 and -2.2 <= a <= -1.2
        return union(ring(c, c, 13, 2.2), ring(c, c, 7, 1.6), disc(c, c, 1.8), sweep, disc(22, 21, 2.2))
    if name == "cube":  # isometric block, for "mods"
        def iso(x, y):
            dx, dy = x - c, y - c
            return abs(dx) <= 12 and abs(dy) + abs(dx) * 0.5 <= 13
        top = lambda x, y: (y - c) + abs(x - c) * 0.5 <= -6 + 13 - 13
        edges = union(seg(c, 16, c, 29, 2), seg(4, 9.5, c, 16, 2), seg(28, 9.5, c, 16, 2))
        hollow = lambda x, y: abs(x - c) <= 9.5 and abs(y - c) + abs(x - c) * 0.5 <= 10.5
        return union(minus(iso, hollow), edges)
    raise ValueError(name)


def gen_icons():
    cols = 4
    rows = (len(ICONS) + cols - 1) // cols
    w, h = cols * CELL, rows * CELL
    px = [(255, 255, 255, 0)] * (w * h)
    ss = 4
    for idx, name in enumerate(ICONS):
        f = icon_shape(name)
        ox, oy = (idx % cols) * CELL, (idx // cols) * CELL
        for y in range(CELL):
            for x in range(CELL):
                hits = 0
                for sy in range(ss):
                    for sx in range(ss):
                        if f(x + (sx + 0.5) / ss, y + (sy + 0.5) / ss):
                            hits += 1
                if hits:
                    px[(oy + y) * w + ox + x] = (255, 255, 255, int(255 * hits / (ss * ss)))
    write_png(os.path.join(GUI, "icons.png"), w, h, px)


# ---------------------------------------------------------------- terminal font
# A hand drawn proportional 5x7 font with a descender row. Glyphs are left
# aligned; Minecraft's bitmap provider measures each glyph's width itself.

GLYPHS = {
    'A': ".###.|#...#|#...#|#####|#...#|#...#|#...#",
    'B': "####.|#...#|#...#|####.|#...#|#...#|####.",
    'C': ".###.|#...#|#....|#....|#....|#...#|.###.",
    'D': "####.|#...#|#...#|#...#|#...#|#...#|####.",
    'E': "#####|#....|#....|####.|#....|#....|#####",
    'F': "#####|#....|#....|####.|#....|#....|#....",
    'G': ".###.|#...#|#....|#.###|#...#|#...#|.####",
    'H': "#...#|#...#|#...#|#####|#...#|#...#|#...#",
    'I': "###|.#.|.#.|.#.|.#.|.#.|###",
    'J': "..###|...#.|...#.|...#.|#..#.|#..#.|.##..",
    'K': "#...#|#..#.|#.#..|##...|#.#..|#..#.|#...#",
    'L': "#....|#....|#....|#....|#....|#....|#####",
    'M': "#...#|##.##|#.#.#|#.#.#|#...#|#...#|#...#",
    'N': "#...#|#...#|##..#|#.#.#|#..##|#...#|#...#",
    'O': ".###.|#...#|#...#|#...#|#...#|#...#|.###.",
    'P': "####.|#...#|#...#|####.|#....|#....|#....",
    'Q': ".###.|#...#|#...#|#...#|#.#.#|#..#.|.##.#",
    'R': "####.|#...#|#...#|####.|#.#..|#..#.|#...#",
    'S': ".####|#....|#....|.###.|....#|....#|####.",
    'T': "#####|..#..|..#..|..#..|..#..|..#..|..#..",
    'U': "#...#|#...#|#...#|#...#|#...#|#...#|.###.",
    'V': "#...#|#...#|#...#|#...#|#...#|.#.#.|..#..",
    'W': "#...#|#...#|#...#|#.#.#|#.#.#|#.#.#|.#.#.",
    'X': "#...#|#...#|.#.#.|..#..|.#.#.|#...#|#...#",
    'Y': "#...#|#...#|.#.#.|..#..|..#..|..#..|..#..",
    'Z': "#####|....#|...#.|..#..|.#...|#....|#####",
    'a': ".....|.....|.###.|....#|.####|#...#|.####",
    'b': "#....|#....|####.|#...#|#...#|#...#|####.",
    'c': ".....|.....|.###.|#....|#....|#....|.###.",
    'd': "....#|....#|.####|#...#|#...#|#...#|.####",
    'e': ".....|.....|.###.|#...#|#####|#....|.###.",
    'f': "..##|.#..|####|.#..|.#..|.#..|.#..",
    'g': ".....|.....|.####|#...#|#...#|.####|....#|.###.",
    'h': "#....|#....|####.|#...#|#...#|#...#|#...#",
    'i': "#|.|#|#|#|#|#",
    'j': "..#|...|..#|..#|..#|..#|#.#|.#.",
    'k': "#...|#...|#..#|#.#.|##..|#.#.|#..#",
    'l': "#.|#.|#.|#.|#.|#.|.#",
    'm': ".....|.....|##.#.|#.#.#|#.#.#|#.#.#|#.#.#",
    'n': ".....|.....|####.|#...#|#...#|#...#|#...#",
    'o': ".....|.....|.###.|#...#|#...#|#...#|.###.",
    'p': ".....|.....|####.|#...#|#...#|####.|#....|#....",
    'q': ".....|.....|.####|#...#|#...#|.####|....#|....#",
    'r': ".....|.....|#.##.|##..#|#....|#....|#....",
    's': ".....|.....|.####|#....|.###.|....#|####.",
    't': ".#..|.#..|####|.#..|.#..|.#..|..##",
    'u': ".....|.....|#...#|#...#|#...#|#...#|.####",
    'v': ".....|.....|#...#|#...#|#...#|.#.#.|..#..",
    'w': ".....|.....|#...#|#...#|#.#.#|#.#.#|.#.#.",
    'x': ".....|.....|#...#|.#.#.|..#..|.#.#.|#...#",
    'y': ".....|.....|#...#|#...#|#...#|.####|....#|.###.",
    'z': ".....|.....|#####|...#.|..#..|.#...|#####",
    '0': ".###.|#...#|#..##|#.#.#|##..#|#...#|.###.",
    '1': ".#.|##.|.#.|.#.|.#.|.#.|###",
    '2': ".###.|#...#|....#|...#.|..#..|.#...|#####",
    '3': "#####|...#.|..#..|...#.|....#|#...#|.###.",
    '4': "...#.|..##.|.#.#.|#..#.|#####|...#.|...#.",
    '5': "#####|#....|####.|....#|....#|#...#|.###.",
    '6': "..##.|.#...|#....|####.|#...#|#...#|.###.",
    '7': "#####|....#|...#.|..#..|.#...|.#...|.#...",
    '8': ".###.|#...#|#...#|.###.|#...#|#...#|.###.",
    '9': ".###.|#...#|#...#|.####|....#|...#.|.##..",
    '!': "#|#|#|#|#|.|#",
    '"': "#.#|#.#",
    '#': ".#.#.|.#.#.|#####|.#.#.|#####|.#.#.|.#.#.",
    '$': "..#..|.####|#.#..|.###.|..#.#|####.|..#..",
    '%': "##..#|##..#|...#.|..#..|.#...|#..##|#..##",
    '&': ".##..|#..#.|#.#..|.#...|#.#.#|#..#.|.##.#",
    "'": "#|#",
    '(': "..#|.#.|#..|#..|#..|.#.|..#",
    ')': "#..|.#.|..#|..#|..#|.#.|#..",
    '*': ".....|..#..|#.#.#|.###.|#.#.#|..#..",
    '+': ".....|..#..|..#..|#####|..#..|..#..",
    ',': "..|..|..|..|..|.#|.#|#.",
    '-': "....|....|....|####",
    '.': ".|.|.|.|.|.|#",
    '/': "....#|....#|...#.|..#..|.#...|#....|#....",
    ':': ".|.|#|.|.|#",
    ';': "..|..|.#|..|..|.#|#.",
    '<': "...#|..#.|.#..|#...|.#..|..#.|...#",
    '=': ".....|.....|#####|.....|#####",
    '>': "#...|.#..|..#.|...#|..#.|.#..|#...",
    '?': ".###.|#...#|....#|...#.|..#..|.....|..#..",
    '@': ".###.|#...#|#.###|#.#.#|#.###|#....|.###.",
    '[': "###|#..|#..|#..|#..|#..|###",
    '\\': "#....|#....|.#...|..#..|...#.|....#|....#",
    ']': "###|..#|..#|..#|..#|..#|###",
    '^': "..#..|.#.#.|#...#",
    '_': ".....|.....|.....|.....|.....|.....|.....|#####",
    '`': "#.|.#",
    '{': "..##|.#..|.#..|#...|.#..|.#..|..##",
    '|': "#|#|#|#|#|#|#|#",
    '}': "##..|..#.|..#.|...#|..#.|..#.|##..",
    '~': ".....|.....|.#...|#.#.#|...#.",
}


def gen_font():
    chars = [chr(c) for c in range(32, 127)]
    per_row = 16
    rows = (len(chars) + per_row - 1) // per_row
    cell = 8
    w, h = per_row * cell, rows * cell
    px = [(255, 255, 255, 0)] * (w * h)
    lines = []
    for i, ch in enumerate(chars):
        g = GLYPHS.get(ch)
        if not g:
            continue
        ox, oy = (i % per_row) * cell, (i // per_row) * cell
        for y, row in enumerate(g.split("|")):
            for x, bit in enumerate(row):
                if bit == '#':
                    px[(oy + y) * w + ox + x] = (255, 255, 255, 255)
    for r in range(rows):
        row = "".join(chars[r * per_row:(r + 1) * per_row])
        row = row.ljust(per_row, "\u0000")
        lines.append(row)
    write_png(os.path.join(FONT_TEX, "terminal.png"), w, h, px)

    import json
    os.makedirs(FONT_DEF, exist_ok=True)
    # space is handled by a dedicated provider so it keeps a sane width
    bitmap_lines = [l.replace(" ", "\u0000") for l in lines]
    data = {"providers": [
        {"type": "space", "advances": {" ": 4}},
        {"type": "bitmap", "file": "trollclient:font/terminal.png", "ascent": 7, "height": 8, "chars": bitmap_lines},
        {"type": "reference", "id": "minecraft:default"},
    ]}
    with open(os.path.join(FONT_DEF, "terminal.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=True)


# ---------------------------------------------------------------- mod icon

def gen_mod_icon():
    n = 128
    px = [(0, 0, 0, 255)] * (n * n)

    def plot(x, y, s, col=(255, 255, 255, 255)):
        for yy in range(y, y + s):
            for xx in range(x, x + s):
                if 0 <= xx < n and 0 <= yy < n:
                    px[yy * n + xx] = col

    # "TC" in the terminal font, 8x scale, centred
    text = "TC"
    scale = 8
    widths = [len(GLYPHS[c].split("|")[0]) for c in text]
    total = sum(widths) * scale + scale * (len(text) - 1)
    x = (n - total) // 2
    y = (n - 7 * scale) // 2 - 6
    for c, gw in zip(text, widths):
        for ry, row in enumerate(GLYPHS[c].split("|")):
            for rx, bit in enumerate(row):
                if bit == '#':
                    plot(x + rx * scale, y + ry * scale, scale)
        x += (gw + 1) * scale
    # underline bar
    for xx in range(20, n - 20):
        for yy in range(n - 26, n - 20):
            px[yy * n + xx] = (255, 255, 255, 255)
    # 1px frame
    for i in range(n):
        for t in (0, n - 1):
            px[t * n + i] = (255, 255, 255, 255)
            px[i * n + t] = (255, 255, 255, 255)
    write_png(os.path.join(ROOT, "icon.png"), n, n, px)


if __name__ == "__main__":
    gen_overlays()
    gen_icons()
    gen_font()
    gen_mod_icon()
    print("assets written to", os.path.abspath(ROOT))
