#!/usr/bin/env python3
"""Turns the client's 5x7 terminal bitmap font into a web font for the website.

Every lit pixel becomes a square (runs on a row are merged), so the font stays
perfectly pixel-sharp at multiples of 8px:
    python3 tools/gen_webfont.py
"""
import os
import sys

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

sys.path.insert(0, os.path.dirname(__file__))
from gen_assets import GLYPHS  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "..", "website", "public", "fonts")
PX = 125            # one font pixel in font units: 8 rows = 1000 units per em
ASCENT_ROWS = 7     # rows above the baseline; row 8 is the descender
NAME = "TrollTerminal"


def glyph_for(rows):
    pen = TTGlyphPen(None)
    for y, row in enumerate(rows):
        top = (ASCENT_ROWS - y) * PX
        bottom = top - PX
        x = 0
        while x < len(row):
            if row[x] != '#':
                x += 1
                continue
            start = x
            while x < len(row) and row[x] == '#':
                x += 1
            left, right = start * PX, x * PX
            # clockwise outer contour, as TrueType expects
            pen.moveTo((left, bottom))
            pen.lineTo((left, top))
            pen.lineTo((right, top))
            pen.lineTo((right, bottom))
            pen.closePath()
    return pen.glyph()


def main():
    names = [".notdef", "space"]
    cmap = {32: "space"}
    glyphs = {".notdef": TTGlyphPen(None).glyph(), "space": TTGlyphPen(None).glyph()}
    metrics = {".notdef": (5 * PX, 0), "space": (4 * PX, 0)}
    for ch, data in sorted(GLYPHS.items()):
        if ch == ' ':
            continue
        rows = data.split("|")
        width = max(len(r) for r in rows)
        name = "uni%04X" % ord(ch)
        names.append(name)
        cmap[ord(ch)] = name
        glyphs[name] = glyph_for(rows)
        metrics[name] = ((width + 1) * PX, 0)

    fb = FontBuilder(1000, isTTF=True)
    fb.setupGlyphOrder(names)
    fb.setupCharacterMap(cmap)
    fb.setupGlyf(glyphs)
    # left side bearings come from the outlines' bounding boxes
    fb.setupHorizontalMetrics({n: (adv, glyphs[n].xMin if hasattr(glyphs[n], "xMin") and glyphs[n].numberOfContours else 0)
                               for n, (adv, _) in metrics.items()})
    fb.setupHorizontalHeader(ascent=ASCENT_ROWS * PX + PX, descent=-2 * PX)
    fb.setupNameTable({"familyName": NAME, "styleName": "Regular",
                       "uniqueFontIdentifier": NAME + "-Regular", "fullName": NAME,
                       "psName": NAME + "-Regular", "version": "Version 1.0"})
    fb.setupOS2(sTypoAscender=ASCENT_ROWS * PX + PX, sTypoDescender=-2 * PX, sTypoLineGap=0,
                usWinAscent=ASCENT_ROWS * PX + PX, usWinDescent=2 * PX, achVendID="TROL")
    fb.setupPost()
    os.makedirs(OUT, exist_ok=True)
    ttf = os.path.join(OUT, "troll-terminal.ttf")
    fb.save(ttf)
    fb.font.flavor = "woff2"
    fb.font.save(os.path.join(OUT, "troll-terminal.woff2"))
    print("web font written to", os.path.abspath(OUT), "with", len(names) - 2, "glyphs")


if __name__ == "__main__":
    main()
