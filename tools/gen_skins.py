#!/usr/bin/env python3
"""Paints the black & white player skins and the cape that SkinChanger ships with.

Skins use the standard 64x64 layout; every body part is painted face by face
with a small function, so designs stay readable:
    python3 tools/gen_skins.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from gen_assets import ROOT, write_png  # noqa: E402

SKIN_DIR = os.path.join(ROOT, "textures", "skin")
CAPE_DIR = os.path.join(ROOT, "textures", "cape")

# box origins in the skin texture: (u, v, width, height, depth)
HEAD = (0, 0, 8, 8, 8)
HAT = (32, 0, 8, 8, 8)
BODY = (16, 16, 8, 12, 4)
JACKET = (16, 32, 8, 12, 4)
R_ARM = (40, 16, 4, 12, 4)
R_SLEEVE = (40, 32, 4, 12, 4)
L_ARM = (32, 48, 4, 12, 4)
L_SLEEVE = (48, 48, 4, 12, 4)
R_LEG = (0, 16, 4, 12, 4)
R_PANTS = (0, 32, 4, 12, 4)
L_LEG = (16, 48, 4, 12, 4)
L_PANTS = (0, 48, 4, 12, 4)

CLEAR = (0, 0, 0, 0)


def g(v, a=255):
    return (v, v, v, a)


class Skin:
    def __init__(self, w=64, h=64):
        self.w, self.h = w, h
        self.px = [CLEAR] * (w * h)

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y * self.w + x] = c

    def faces(self, box):
        """Yields (face name, x0, y0, width, height) for each face of a box."""
        u, v, w, h, d = box
        yield "top", u + d, v, w, d
        yield "bottom", u + d + w, v, w, d
        yield "right", u, v + d, d, h
        yield "front", u + d, v + d, w, h
        yield "left", u + d + w, v + d, d, h
        yield "back", u + d + w + d, v + d, w, h

    def paint(self, box, fn):
        """fn(face, x, y, w, h) -> colour or None (leave untouched)."""
        for face, x0, y0, w, h in self.faces(box):
            for y in range(h):
                for x in range(w):
                    c = fn(face, x, y, w, h)
                    if c is not None:
                        self.set(x0 + x, y0 + y, c)

    def grid(self, box, face, rows, palette):
        """Stamps a character grid onto one face (front of the head, a logo...)."""
        for name, x0, y0, w, h in self.faces(box):
            if name != face:
                continue
            for y, row in enumerate(rows):
                for x, ch in enumerate(row):
                    if ch in palette and x < w and y < h:
                        self.set(x0 + x, y0 + y, palette[ch])

    def save(self, name, folder=SKIN_DIR):
        write_png(os.path.join(folder, name + ".png"), self.w, self.h, self.px)


def solid(c):
    return lambda face, x, y, w, h: c


def arms(s, sleeve, hand, cuff=None, overlay=None):
    for arm in (R_ARM, L_ARM):
        def fn(face, x, y, w, h):
            if face == "bottom" or (face != "top" and y >= h - 2):
                return hand
            if cuff is not None and face != "top" and y == h - 3:
                return cuff
            return sleeve(face, x, y, w, h) if callable(sleeve) else sleeve
        s.paint(arm, fn)
    if overlay:
        for sl in (R_SLEEVE, L_SLEEVE):
            s.paint(sl, overlay)


def legs(s, pants, shoes):
    for leg in (R_LEG, L_LEG):
        s.paint(leg, lambda face, x, y, w, h: shoes if face == "bottom" or (face != "top" and y >= h - 2) else
                (pants(face, x, y, w, h) if callable(pants) else pants))


# ------------------------------------------------------------------ troll

def troll():
    s = Skin()
    skin, ink, white = g(226), g(18), g(250)
    s.paint(HEAD, lambda face, x, y, w, h: g(40) if face == "top" or (y == 0 and face != "bottom") else skin)
    # the grin that launched a thousand forum posts
    s.grid(HEAD, "front", [
        "########",
        "........",
        ".##..##.",
        ".w#..#w.",
        "........",
        "#wwwwww#",
        ".#w#w#w#",
        "..####..",
    ], {"#": ink, "w": white, ".": skin})
    # black suit, white shirt, skinny black tie
    def suit(face, x, y, w, h):
        if face == "front":
            if 1 <= x <= 6 and y <= 5.4 - abs(x - 3.5) * 1.2:
                return ink if x in (3, 4) else white
            if x in (3, 4) and y <= 7:
                return ink
            if y == 7 and x in (1, 6):
                return g(60)  # pocket flaps
        if y == 8:
            return g(48)  # belt line
        return g(22)
    s.paint(BODY, suit)
    arms(s, g(22), skin, cuff=white)
    legs(s, g(34), g(8))
    return s


# ------------------------------------------------------------------ mime

def mime():
    s = Skin()
    paint, ink = g(244), g(16)
    s.paint(HEAD, lambda face, x, y, w, h: g(20) if face == "top" or (face in ("back", "left", "right") and y < 2) else paint)
    s.grid(HEAD, "front", [
        "........",
        "........",
        ".#....#.",
        "##w..w##",
        ".#....#.",
        "........",
        "...##...",
        "........",
    ], {"#": ink, "w": g(255), ".": paint})
    # black beret on the hat layer
    s.paint(HAT, lambda face, x, y, w, h: g(14) if face == "top" or (face != "bottom" and y == 0) else None)
    stripes = lambda face, x, y, w, h: g(18) if (y // 2) % 2 == 0 else g(238)
    s.paint(BODY, stripes)
    arms(s, stripes, g(250))  # white gloves
    legs(s, g(20), g(10))
    return s


# ------------------------------------------------------------------ referee

def referee():
    s = Skin()
    skin, ink = g(205), g(14)
    s.paint(HEAD, lambda face, x, y, w, h: g(52) if face == "top" or (face in ("back", "left", "right") and y < 3) else skin)
    s.grid(HEAD, "front", [
        "########",
        "#......#",
        ".##..##.",
        ".w#..#w.",
        "........",
        "........",
        "..####..",
        "........",
    ], {"#": g(52), "w": g(250), ".": skin})
    # black cap with a brim line
    s.paint(HAT, lambda face, x, y, w, h: ink if face == "top" or (face != "bottom" and y <= 1) else None)
    stripes = lambda face, x, y, w, h: g(16) if (x // 2) % 2 == 0 else g(242)
    s.paint(BODY, stripes)
    s.grid(BODY, "front", ["", "", "", "", "  w", "  #"], {"w": g(160), "#": g(90)})  # whistle
    arms(s, stripes, skin)
    legs(s, g(18), g(240))
    return s


# ------------------------------------------------------------------ ghost

def ghost():
    s = Skin()
    sheet, ink = g(240), g(12)
    s.paint(HEAD, solid(sheet))
    s.grid(HEAD, "front", [
        "........",
        "........",
        ".##..##.",
        ".##..##.",
        "........",
        "...##...",
        "...##...",
        "........",
    ], {"#": ink, ".": sheet})
    fold = lambda face, x, y, w, h: g(225) if (x + y) % 5 == 0 else sheet
    s.paint(BODY, fold)
    arms(s, fold, sheet)
    # ragged hem on the trousers layer
    legs(s, fold, g(210))
    s.paint(R_PANTS, lambda face, x, y, w, h: g(232) if y < h - 1 or x % 2 == 0 else None)
    s.paint(L_PANTS, lambda face, x, y, w, h: g(232) if y < h - 1 or x % 2 == 1 else None)
    return s


# ------------------------------------------------------------------ hacker

def hacker():
    s = Skin()
    hood, shade, eye = g(24), g(52), g(255)
    s.paint(HEAD, solid(shade))
    s.grid(HEAD, "front", [
        "........",
        "........",
        "........",
        ".ww..ww.",
        "........",
        "........",
        "..####..",
        "........",
    ], {"w": eye, "#": g(36), ".": shade})
    # the hood: everything but a window for the face
    def hood_fn(face, x, y, w, h):
        if face == "front":
            return hood if y <= 1 or x == 0 or x == w - 1 else None
        return hood
    s.paint(HAT, hood_fn)
    def hoodie(face, x, y, w, h):
        if face == "front":
            if 2 <= x <= 5 and 7 <= y <= 9:
                return g(40)  # pocket
            if x in (3, 4) and 1 <= y <= 3:
                return g(200) if (x + y) % 2 else g(140)  # drawstrings
        return hood
    s.paint(BODY, hoodie)
    arms(s, hood, shade)
    legs(s, g(30), g(232))
    return s


# ------------------------------------------------------------------ cape

def cape():
    c = Skin(64, 32)
    black, white = g(10), g(245)
    # cape box: u=0, v=0, 10 x 16 x 1
    box = (0, 0, 10, 16, 1)

    def fn(face, x, y, w, h):
        if face in ("front", "back") and (x == 0 or x == w - 1 or y == h - 1):
            return white
        return black
    c.paint(box, fn)
    # "TC" monogram on the outside of the cape
    c.grid(box, "front", [
        "",
        "",
        "",
        " ###  ##",
        "  #  #  ",
        "  #  #  ",
        "  #  #  ",
        "  #   ##",
        "",
        "  ####  ",
        " #    # ",
        " # ## # ",
        " #    # ",
        "  ####  ",
    ], {"#": white})
    return c


if __name__ == "__main__":
    for name, fn in [("troll", troll), ("mime", mime), ("referee", referee), ("ghost", ghost), ("hacker", hacker)]:
        fn().save(name)
    cape().save("troll", CAPE_DIR)
    print("skins written to", os.path.abspath(SKIN_DIR))
