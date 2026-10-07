#!/usr/bin/env python3
"""Builds the website's generated parts from the client itself.

- js/data.js: every module (name, category, description, settings) parsed
  straight out of the Java source, plus the 5x7 glyphs for the canvas logo.
- img/*: pixel logo, 88x31 badges, animated GIFs, cursors, category icons,
  skin previews and the background tile, all in the client's black & white.
- img/skins/tex/*: the raw skin and cape textures the 3D player models use, plus
  "blink", a demo skin with every outer layer painted for the skin animation lab.
- the shared page chrome (website/partials) stamped into every page, the modules
  table, the gallery and the jar's size and checksum.

Needs Pillow. Run after changing modules or art (--pages skips the pixel art):
    python3 tools/gen_site.py [--pages]
"""
import json
import math
import os
import re
import shutil
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
from gen_assets import GLYPHS  # noqa: E402

SRC = os.path.join(REPO, "src", "main", "java", "com", "trollclient")
ASSETS = os.path.join(REPO, "src", "main", "resources", "assets", "trollclient")
SITE = os.path.join(REPO, "website", "public")
IMG = os.path.join(SITE, "img")
FONT = os.path.join(SITE, "fonts", "troll-terminal.ttf")
PARTIALS = os.path.join(REPO, "website", "partials")

# page file -> (tab id, title-bar name)
PAGES = {
    "index.html": ("home", "Home"),
    "skin-animation.html": ("skin", "Skin Animation"),
    "modules.html": ("modules", "Modules"),
    "gallery.html": ("gallery", "Gallery"),
    "download.html": ("download", "Download"),
    "guestbook.html": ("guestbook", "Guestbook"),
}

WHITE = (240, 240, 240, 255)
BLACK = (10, 10, 10, 255)
CLEAR = (0, 0, 0, 0)


# ------------------------------------------------------------------ module data

def java_string(s):
    return bytes(s, "utf-8").decode("unicode_escape").encode("latin-1").decode("utf-8")


def split_args(text, start):
    """Arguments of a Java call starting just after '(' -> (list of raw args, index after ')')."""
    args, depth, cur, i, in_str = [], 0, "", start, False
    while i < len(text):
        c = text[i]
        if in_str:
            cur += c
            if c == "\\":
                cur += text[i + 1]
                i += 1
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
            cur += c
        elif c in "([{":
            depth += 1
            cur += c
        elif c in ")]}":
            if depth == 0:
                args.append(cur.strip())
                return args, i + 1
            depth -= 1
            cur += c
        elif c == "," and depth == 0:
            args.append(cur.strip())
            cur = ""
        else:
            cur += c
        i += 1
    return args, i


def unquote(arg):
    return java_string(arg[1:-1]) if arg.startswith('"') else arg


def parse_setting(match):
    kind = match.group(1).lower()
    args, end = split_args(match.string, match.end())
    out = {"type": kind, "name": unquote(args[0]), "desc": unquote(args[1])}
    rest = args[2:]
    if kind == "bool":
        out["value"] = rest[0] == "true"
    elif kind == "number":
        out["value"], out["min"], out["max"], out["step"] = (float(x.rstrip("fFdD")) for x in rest[:4])
        unit = re.match(r'\s*\.unit\("([^"]*)"\)', match.string[end:])
        out["unit"] = unit.group(1) if unit else ""
    elif kind == "mode":
        out["value"] = unquote(rest[0])
        out["modes"] = [unquote(x) for x in rest[1:]]
    elif kind == "text":
        out["value"] = unquote(rest[0])
    elif kind == "color":
        out["value"] = "#%06X" % (int(rest[0], 16) & 0xFFFFFF) if rest[0].startswith("0x") else "#FFFFFF"
    return out


def parse_modules():
    categories = []
    cat_src = open(os.path.join(SRC, "module", "Category.java"), encoding="utf-8").read()
    for m in re.finditer(r'(\w+)\("([^"]+)", Icon\.(\w+), "([^"]+)"\)', cat_src):
        categories.append({"id": m.group(1), "name": m.group(2), "icon": m.group(3).lower(), "tagline": m.group(4)})

    order = []
    manager = open(os.path.join(SRC, "module", "ModuleManager.java"), encoding="utf-8").read()
    for m in re.finditer(r"register\(new (\w+)\(\)\)", manager):
        order.append(m.group(1))

    modules = {}
    for root, _, files in os.walk(os.path.join(SRC, "module")):
        for f in files:
            if not f.endswith(".java"):
                continue
            text = open(os.path.join(root, f), encoding="utf-8").read()
            sup = re.search(r'super\(\s*"([^"]+)",\s*"((?:[^"\\]|\\.)*)",\s*Category\.(\w+)\)', text, re.S)
            if not sup:
                continue
            settings = [parse_setting(m) for m in re.finditer(r'new (Bool|Number|Mode|Text|Color)Setting\(', text)]
            modules[f[:-5]] = {
                "name": sup.group(1),
                "desc": java_string(sup.group(2)),
                "category": sup.group(3),
                "settings": settings,
                "toggleable": "isToggleable()" not in text,
            }
    ordered = [modules[c] for c in order if c in modules]
    return categories, ordered


def write_data():
    categories, modules = parse_modules()
    glyphs = {c: g.split("|") for c, g in GLYPHS.items()}
    js = "// generated by tools/gen_site.py from the client's source; don't edit by hand\n"
    js += "window.TROLL = " + json.dumps({"categories": categories, "modules": modules, "glyphs": glyphs},
                                         ensure_ascii=False, indent=1) + ";\n"
    with open(os.path.join(SITE, "js", "data.js"), "w", encoding="utf-8") as f:
        f.write(js)
    print(len(modules), "modules in", len(categories), "categories")
    return categories, modules


# ------------------------------------------------------------------ helpers

def font(size=8):
    return ImageFont.truetype(FONT, size)


def text_width(s, size=8):
    return int(font(size).getlength(s))


def save(im, name):
    path = os.path.join(IMG, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)


def save_gif(frames, name, duration):
    path = os.path.join(IMG, name)
    frames = [f.convert("RGBA") for f in frames]
    # transparent GIFs: reserve palette index 0 for "clear"
    pal = []
    for f in frames:
        p = Image.new("P", f.size, 0)
        q = f.convert("RGB").quantize(colors=255, method=Image.Quantize.MEDIANCUT)
        p = q
        alpha = f.getchannel("A").point(lambda a: 255 if a < 128 else 0)
        p.paste(255, mask=alpha)
        pal.append(p)
    pal[0].save(path, save_all=True, append_images=pal[1:], duration=duration, loop=0, transparency=255, disposal=2)


# ------------------------------------------------------------------ art

def pixel_logo(text, ps, depth):
    rows = 7
    width = 0
    for c in text:
        width += len(GLYPHS.get(c, GLYPHS.get(c.lower(), "..|..|..|..|..|..|..")).split("|")[0]) + 1
    im = Image.new("RGBA", ((width + 1) * ps + depth + 2, rows * ps + depth + 2), CLEAR)
    d = ImageDraw.Draw(im)
    for layer in range(depth, -1, -1):
        x = 0
        for c in text:
            g = GLYPHS.get(c, "..|..|..|..|..|..|..").split("|")
            for y, row in enumerate(g):
                for gx, bit in enumerate(row):
                    if bit != '#':
                        continue
                    px, py = (x + gx) * ps + layer, y * ps + layer
                    if layer == 0:
                        shade = 236 if y <= 4 else 206
                        # thin scanline on every face pixel, like the title screen
                        d.rectangle([px, py, px + ps - 1, py + ps - 1], fill=(shade, shade, shade, 255))
                        if ps >= 4:
                            d.line([px, py + ps - 1, px + ps - 1, py + ps - 1], fill=(shade - 40,) * 3 + (255,))
                    else:
                        v = int(60 + 90 * (depth - layer) / depth)
                        d.rectangle([px, py, px + ps - 1, py + ps - 1], fill=(v, v, v, 255))
            x += len(g[0]) + 1
    return im


def badge(top, bottom, invert=False, style="split"):
    """Classic 88x31 web button."""
    im = Image.new("RGBA", (88, 31), BLACK)
    d = ImageDraw.Draw(im)
    fg, bg = (WHITE, BLACK) if not invert else (BLACK, WHITE)
    d.rectangle([0, 0, 87, 30], fill=bg)
    d.rectangle([0, 0, 87, 30], outline=fg)
    d.rectangle([2, 2, 85, 28], outline=(120, 120, 120, 255))
    f = font(8)
    if style == "split":
        d.rectangle([3, 3, 30, 27], fill=fg)
        d.text((17 - text_width(top) / 2, 11), top, font=f, fill=bg)
        d.text((59 - text_width(bottom) / 2, 11), bottom, font=f, fill=fg)
    else:
        d.text((44 - text_width(top) / 2, 6), top, font=f, fill=fg)
        d.text((44 - text_width(bottom) / 2, 17), bottom, font=f, fill=fg)
    return im


def construction_gif():
    frames = []
    for i in range(8):
        im = Image.new("RGBA", (150, 40), BLACK)
        d = ImageDraw.Draw(im)
        for x in range(-40, 190, 16):
            off = x + i * 2
            d.polygon([(off, 0), (off + 8, 0), (off - 32, 40), (off - 40, 40)], fill=(230, 230, 230, 255))
        d.rectangle([6, 11, 143, 28], fill=BLACK)
        d.rectangle([6, 11, 143, 28], outline=WHITE)
        msg = "UNDER CONSTRUCTION"
        d.text((75 - text_width(msg) / 2, 15), msg, font=font(8), fill=WHITE if i % 4 < 3 else (120, 120, 120, 255))
        frames.append(im)
    save_gif(frames, "construction.gif", 90)


def new_gif():
    frames = []
    for i in range(2):
        im = Image.new("RGBA", (34, 13), CLEAR)
        d = ImageDraw.Draw(im)
        fg, bg = (BLACK, WHITE) if i == 0 else (WHITE, BLACK)
        d.rectangle([0, 0, 33, 12], fill=bg, outline=fg if i else bg)
        d.text((17 - text_width("NEW!") / 2, 3), "NEW!", font=font(8), fill=fg)
        frames.append(im)
    save_gif(frames, "new.gif", 450)


def mail_gif():
    frames = []
    for i in range(6):
        im = Image.new("RGBA", (32, 24), CLEAR)
        d = ImageDraw.Draw(im)
        bob = [0, -1, -2, -1, 0, 1][i]
        d.rectangle([2, 6 + bob, 29, 21 + bob], fill=WHITE, outline=BLACK)
        flap = [(2, 6 + bob), (16, 14 + bob - (2 if i in (1, 2) else 0)), (29, 6 + bob)]
        d.line(flap, fill=BLACK, width=1)
        d.line([(2, 21 + bob), (12, 13 + bob)], fill=(120, 120, 120, 255))
        d.line([(29, 21 + bob), (19, 13 + bob)], fill=(120, 120, 120, 255))
        frames.append(im)
    save_gif(frames, "mail.gif", 130)


def spin_gif():
    """A spinning 'TC' coin, because every 1998 homepage had something spinning."""
    frames = []
    n = 16
    for i in range(n):
        im = Image.new("RGBA", (40, 40), CLEAR)
        w = abs(math.cos(i / n * math.pi * 2))
        face = Image.new("RGBA", (36, 36), CLEAR)
        fd = ImageDraw.Draw(face)
        fd.ellipse([0, 0, 35, 35], fill=WHITE if math.cos(i / n * math.pi * 2) > 0 else (150, 150, 150, 255), outline=BLACK)
        fd.ellipse([3, 3, 32, 32], outline=(90, 90, 90, 255))
        if math.cos(i / n * math.pi * 2) > 0:
            fd.text((18 - text_width("TC", 16) / 2, 10), "TC", font=font(16), fill=BLACK)
        nw = max(2, int(36 * w))
        face = face.resize((nw, 36), Image.NEAREST)
        im.alpha_composite(face, (20 - nw // 2, 2))
        frames.append(im)
    save_gif(frames, "spin.gif", 70)


def cursors():
    arrow = [
        "X", "XX", "X.X", "X..X", "X...X", "X....X", "X.....X", "X......X", "X.......X", "X........X",
        "X.....XXXXX", "X..X..X", "X.X X..X", "XX  X..X", "X    X..X", "     X..X", "      XX",
    ]
    hand = [
        "     XX", "    X..X", "    X..X", "    X..X", "    X..XXX", "    X..X..XXX", " XX X..X..X..XX",
        "X..XX........X", "X...X........X", " X...........X", "  X..........X", "  X.........X", "   X........X",
        "   X.......X", "    X......X", "    XXXXXXXX",
    ]
    for name, shape in (("cursor.png", arrow), ("cursor-hand.png", hand)):
        s = 2
        im = Image.new("RGBA", (16 * s, 18 * s), CLEAR)
        d = ImageDraw.Draw(im)
        for y, row in enumerate(shape):
            for x, ch in enumerate(row):
                if ch in "X.":
                    d.rectangle([x * s, y * s, x * s + s - 1, y * s + s - 1], fill=BLACK if ch == "X" else WHITE)
        save(im, name)


def bg_tile():
    """Dark tile with faint binary digits, for the page background."""
    import random
    rnd = random.Random(7)
    im = Image.new("RGBA", (128, 128), (8, 8, 8, 255))
    d = ImageDraw.Draw(im)
    for y in range(0, 128, 16):
        for x in range(0, 128, 16):
            if rnd.random() < 0.35:
                v = rnd.randint(18, 30)
                d.text((x + 4, y + 4), rnd.choice("01"), font=font(8), fill=(v, v, v, 255))
    save(im, "bg.png")


def icons():
    atlas = Image.open(os.path.join(ASSETS, "textures", "gui", "icons.png")).convert("RGBA")
    names = ["combat", "movement", "troll", "player", "world", "client", "search", "gear", "close", "check",
             "keyboard", "palette", "chevron", "star", "eye", "power", "chat", "skull", "radar", "cube"]
    for i, n in enumerate(names):
        cell = atlas.crop(((i % 4) * 32, (i // 4) * 32, (i % 4) * 32 + 32, (i // 4) * 32 + 32))
        save(cell, "icons/%s.png" % n)


def skin_previews():
    """Front 'paper doll' renders of the bundled skins, 8x."""
    parts = [((8, 8, 8, 8), (4, 0)), ((20, 20, 8, 12), (4, 8)), ((44, 20, 4, 12), (0, 8)), ((36, 52, 4, 12), (12, 8)),
             ((4, 20, 4, 12), (4, 20)), ((20, 52, 4, 12), (8, 20))]
    over = [((40, 8, 8, 8), (4, 0)), ((20, 36, 8, 12), (4, 8)), ((44, 36, 4, 12), (0, 8)), ((52, 52, 4, 12), (12, 8)),
            ((4, 36, 4, 12), (4, 20)), ((4, 52, 4, 12), (8, 20))]
    for name in ("troll", "mime", "referee", "ghost", "hacker"):
        skin = Image.open(os.path.join(ASSETS, "textures", "skin", name + ".png")).convert("RGBA")
        fig = Image.new("RGBA", (16, 32), CLEAR)
        for layer in (parts, over):
            for (x, y, w, h), pos in layer:
                fig.alpha_composite(skin.crop((x, y, x + w, y + h)), pos)
        save(fig.resize((16 * 8, 32 * 8), Image.NEAREST), "skins/%s.png" % name)
    cape = Image.open(os.path.join(ASSETS, "textures", "cape", "troll.png")).convert("RGBA").crop((1, 1, 11, 17))
    save(cape.resize((10 * 8, 16 * 8), Image.NEAREST), "skins/cape.png")


def skin_textures():
    """Raw textures for the CSS 3D models: the SkinChanger skins, the cape, and the demo skin."""
    from gen_skins import Skin, HAT, JACKET, R_SLEEVE, L_SLEEVE, R_PANTS, L_PANTS, g
    out = os.path.join(IMG, "skins", "tex")
    os.makedirs(out, exist_ok=True)
    for name in ("troll", "mime", "referee", "ghost", "hacker"):
        shutil.copyfile(os.path.join(ASSETS, "textures", "skin", name + ".png"), os.path.join(out, name + ".png"))
    shutil.copyfile(os.path.join(ASSETS, "textures", "cape", "troll.png"), os.path.join(out, "cape.png"))

    # "blink": the troll skin dressed in a beanie, a varsity jacket, track sleeves and striped
    # trousers, all on the outer layer, so SkinBlink has something big to flicker
    base = Image.open(os.path.join(ASSETS, "textures", "skin", "troll.png")).convert("RGBA")
    s = Skin()
    pix = base.load()
    s.px = [pix[x, y] for y in range(64) for x in range(64)]
    white, ink, mid, rib = g(240), g(14), g(150), g(208)

    def beanie(face, x, y, w, h):
        if face == "top":
            return white if (x + y) % 2 else rib
        if face == "bottom":
            return None
        rows = 2 if face == "front" else 4
        if y < rows - 1:
            return white if x % 2 else rib
        return ink if y == rows - 1 else None

    def jacket(face, x, y, w, h):
        hem = y == h - 1
        if face == "front":
            if x in (0, 7):
                return mid if hem else white
            if x in (1, 6):
                return ink
            return None  # open at the front: the suit and tie show through
        if face == "back":
            letters = ["........", "........", ".###.###", "..#..#..", "..#..#..", "..#..#..", "..#..###"]
            if y < len(letters) and letters[y][x] == "#":
                return ink
            if y == 9:
                return ink
            return mid if hem else white
        if face == "bottom":
            return None
        return mid if hem else white

    def sleeve(face, x, y, w, h):
        if face == "bottom" or (face != "top" and y >= h - 2):
            return None  # hands stay bare
        if face != "top" and y in (h - 4, h - 6):
            return ink
        return white

    def trousers(face, x, y, w, h):
        if face in ("top", "bottom") or y >= h - 2:
            return None  # shoes stay bare
        if face in ("left", "right"):
            return white if x in (1, 2) else ink if x in (0, 3) and y < 3 else None
        if y == 0:
            return rib
        if face == "front" and y in (5, 6):
            return white
        return None

    s.paint(HAT, beanie)
    s.paint(JACKET, jacket)
    for box in (R_SLEEVE, L_SLEEVE):
        s.paint(box, sleeve)
    for box in (R_PANTS, L_PANTS):
        s.paint(box, trousers)
    im = Image.new("RGBA", (64, 64))
    im.putdata(s.px)
    im.save(os.path.join(out, "blink.png"))


def favicon():
    icon = Image.open(os.path.join(ASSETS, "icon.png")).convert("RGBA")
    save(icon.resize((32, 32), Image.NEAREST), "favicon.png")
    # browsers ask for /favicon.ico whatever the page says
    icon.resize((32, 32), Image.NEAREST).save(os.path.join(SITE, "favicon.ico"), sizes=[(16, 16), (32, 32)])


def divider():
    frames = []
    for i in range(6):
        im = Image.new("RGBA", (400, 6), CLEAR)
        d = ImageDraw.Draw(im)
        for x in range(-12, 412, 12):
            ox = x + i * 2
            d.rectangle([ox, 2, ox + 6, 3], fill=WHITE)
        frames.append(im)
    save_gif(frames, "divider.gif", 80)


# ------------------------------------------------------------------ static page parts

def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def fill(page, marker, html):
    """Replaces the text between <!-- marker:start --> and <!-- marker:end --> (or the inline /marker form)."""
    path = os.path.join(SITE, page)
    if not os.path.exists(path):
        return
    text = open(path, encoding="utf-8").read()
    block = re.compile(r"(<!-- %s:start -->)(.*?)(<!-- %s:end -->)" % (marker, marker), re.S)
    inline = re.compile(r"(<!-- %s -->)(.*?)(<!-- /%s -->)" % (marker, marker), re.S)
    text = block.sub(lambda m: m.group(1) + "\n" + html + "\n" + m.group(3), text)
    text = inline.sub(lambda m: m.group(1) + html + m.group(3), text)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def modules_table(categories, modules):
    out = []
    for c in categories:
        mods = [m for m in modules if m["category"] == c["id"]]
        rows = []
        for m in mods:
            settings = ", ".join('<span title="%s">%s</span>' % (esc(st["desc"]), esc(st["name"].lower()))
                                 for st in m["settings"]) or "-"
            search = " ".join([m["name"], m["desc"]] + [st["name"] for st in m["settings"]]).lower()
            name = esc(m["name"])
            featured = m["name"] == "SkinBlink"
            if featured:
                name = '<a href="/skin-animation">%s</a><span class="star-badge">skin animation</span>' % name
            rows.append('\t\t\t\t\t<tr class="mod-row%s" data-search="%s"><td class="name">%s</td><td><span class="desc">%s</span>'
                        '<div class="settings">settings: %s</div></td></tr>'
                        % (" featured" if featured else "", esc(search), name, esc(m["desc"]), settings))
        out.append('\t\t\t\t<div class="group mod-group" data-cat="%s">\n\t\t\t\t\t<span class="legend"><img class="icon" src="img/icons/%s.png" alt=""> %s '
                   '<span class="dim">%d &middot; %s</span></span>\n\t\t\t\t\t<table class="data">\n%s\n\t\t\t\t\t</table>\n\t\t\t\t</div>'
                   % (c["id"].lower(), c["icon"], c["name"].lower(), len(mods), esc(c["tagline"]), "\n".join(rows)))
    return "\n".join(out)


def stamp_chrome():
    """Puts the shared window chrome (website/partials) into every page, with that page's tab lit."""
    top = open(os.path.join(PARTIALS, "top.html"), encoding="utf-8").read()
    bottom = open(os.path.join(PARTIALS, "bottom.html"), encoding="utf-8").read()
    for page, (tab, title) in PAGES.items():
        html = top.replace("{{title}}", title).replace(
            '<a class="tab" data-tab="%s"' % tab, '<a class="tab here" aria-current="page" data-tab="%s"' % tab).replace(
            '<a class="tab hot" data-tab="%s"' % tab, '<a class="tab hot here" aria-current="page" data-tab="%s"' % tab)
        fill(page, "chrome:top", html.rstrip("\n"))
        fill(page, "chrome:bottom", bottom.rstrip("\n"))


def jar_info():
    jar = os.path.join(REPO, "build", "libs", "troll-client-1.0.0.jar")
    if not os.path.exists(jar):
        return
    import hashlib
    import shutil
    dest = os.path.join(SITE, "downloads")
    os.makedirs(dest, exist_ok=True)
    shutil.copyfile(jar, os.path.join(dest, "troll-client-1.0.0.jar"))
    data = open(jar, "rb").read()
    seconds = len(data) * 8 / 28800
    fill("download.html", "jar:size", "%d KB" % round(len(data) / 1024))
    fill("download.html", "jar:sha", hashlib.sha256(data).hexdigest())
    fill("download.html", "jar:time", "%d min %02d s" % (seconds // 60, seconds % 60))


def gallery():
    manifest = os.path.join(IMG, "shots", "manifest.json")
    if not os.path.exists(manifest):
        return
    shots = json.load(open(manifest, encoding="utf-8"))
    figs = ['\t\t\t\t\t<figure class="bevel"><img class="clicky" src="img/shots/%s" data-full="img/shots/%s" alt="%s" data-caption="%s" loading="lazy">'
            '<figcaption>%s</figcaption></figure>' % (s["file"], s["file"], esc(s["caption"]), esc(s["caption"]), esc(s["caption"]))
            for s in shots]
    fill("gallery.html", "gallery", '\t\t\t\t<div class="gallery">\n%s\n\t\t\t\t</div>' % "\n".join(figs))


def main():
    os.makedirs(IMG, exist_ok=True)
    os.makedirs(os.path.join(SITE, "js"), exist_ok=True)
    categories, modules = write_data()
    stamp_chrome()
    fill("modules.html", "modules", modules_table(categories, modules))
    jar_info()
    gallery()
    skin_textures()
    if "--pages" in sys.argv:
        print("pages, data and skin textures written to", SITE)
        return
    save(pixel_logo("TROLL CLIENT", 8, 5), "logo.png")
    save(pixel_logo("TC", 4, 2), "logo-small.png")
    badges = {
        "troll": ("TROLL", "CLIENT", False, "split"),
        "fabric": ("FABRIC", "POWERED", True, "stack"),
        "notepad": ("MADE WITH", "NOTEPAD", False, "stack"),
        "mc": ("MC", "26.2", False, "split"),
        "bw": ("BLACK &", "WHITE", True, "stack"),
        "800": ("BEST AT", "800x600", False, "stack"),
        "java": ("JAVA", "25", True, "split"),
        "nofl": ("NO", "FLASH", False, "split"),
    }
    for name, (a, b, inv, style) in badges.items():
        save(badge(a, b, inv, style), "badges/%s.png" % name)
    construction_gif()
    new_gif()
    mail_gif()
    spin_gif()
    divider()
    cursors()
    bg_tile()
    icons()
    skin_previews()
    favicon()
    print("site assets written to", IMG)


if __name__ == "__main__":
    main()
