#!/usr/bin/env python3
"""Turns the showcase recorder's frames into the website video.

1. fills any gaps in the frame sequence (repeats the previous frame),
2. writes an original chiptune soundtrack (square waves, triangle-ish bass,
   noise drums) exactly as long as the video,
3. encodes website/public/video/showcase.mp4 (1080p H.264 + AAC, sized to fit a Workers asset) and a poster,
4. saves stills for the website (shader samples and gallery shots).

Needs numpy, Pillow and ffmpeg:
    ./gradlew runShowcase && python3 tools/make_video.py
"""
import json
import math
import os
import shutil
import subprocess
import sys
import wave

import numpy as np
from PIL import Image

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FRAMES = os.path.join(REPO, "run-showcase", "showcase", "frames")
WORK = os.path.join(REPO, "run-showcase", "showcase")
SITE = os.path.join(REPO, "website", "public")
FPS = 30
RATE = 44100

# (seconds into the video, file name, caption) for the website
STILLS = [
    (34.1, "shader_noir.jpg", "noir"),
    (36.3, "shader_crt.jpg", "crt"),
    (38.5, "shader_dither.jpg", "dither"),
    (40.7, "shader_halftone.jpg", "halftone"),
    (42.9, "shader_ink.jpg", "ink"),
]
GALLERY = [
    (6.2, "title.jpg", "the title screen: warp stars, a 3D pixel logo and a sine scroller"),
    (14.5, "menu.jpg", "the menu, with the category name sliding out of the sidebar"),
    (21.0, "settings.jpg", "settings: switches that say on/off, sliders, typewriter tooltips"),
    (24.0, "themes.jpg", "themes: this one's Ink"),
    (29.0, "search.jpg", "type to search: matches light up"),
    (47.5, "twerk.jpg", "Twerk + ItemFlex, with the troll skin and cape (Noir shader)"),
    (54.5, "orbit.jpg", "Orbit circling a stand-in player, target HUD and radar (CRT shader)"),
    (63.5, "stalker.jpg", "Stalker in angel mode (Noir shader)"),
    (74.0, "trapper.jpg", "Trapper boxing someone in (Ink shader)"),
    (81.0, "pelter.jpg", "Pelter + Confetti (Dither shader)"),
    (88.5, "nowayhome.jpg", "NoWayHome walling a player in (Halftone shader)"),
    (97.0, "mimic.jpg", "Mimic copying every move"),
    (104.5, "racket.jpg", "Racket: every door, gate and lever at once (CRT shader)"),
    (122.0, "outro.jpg", "fin"),
]


# ------------------------------------------------------------------ frames

def frame_path(i):
    return os.path.join(FRAMES, "frame_%05d.jpg" % i)


def fill_gaps():
    names = sorted(f for f in os.listdir(FRAMES) if f.startswith("frame_"))
    if not names:
        sys.exit("no frames in " + FRAMES + " - run ./gradlew runShowcase first")
    last = int(names[-1][6:11])
    filled = 0
    for i in range(last + 1):
        if not os.path.exists(frame_path(i)):
            src = next(frame_path(j) for j in range(i - 1, -1, -1) if os.path.exists(frame_path(j)))
            shutil.copyfile(src, frame_path(i))
            filled += 1
    print("frames: %d (%.1f s), filled %d gaps" % (last + 1, (last + 1) / FPS, filled))
    return last + 1


# ------------------------------------------------------------------ music

def note_hz(name):
    """'A4' -> 440 Hz. Sharps only ('C#4')."""
    names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
    pitch, octave = name[:-1], int(name[-1])
    return 440.0 * 2 ** ((names.index(pitch) + (octave - 4) * 12 - 9) / 12)


def square(freq, n, duty=0.5, vibrato=0.0):
    """Pulse wave; vibrato (as a fraction of the pitch) fades in over the first quarter second."""
    t = np.arange(n) / RATE
    if vibrato:
        phase = np.cumsum(freq * (1 + vibrato * np.sin(2 * np.pi * 5.5 * t) * np.clip(t / 0.25, 0, 1))) / RATE
    else:
        phase = freq * t
    return np.where((phase % 1.0) < duty, 1.0, -1.0)


def envelope(n, attack=0.004, decay=0.15, sustain=0.6, release=0.03):
    t = np.arange(n) / RATE
    dur = n / RATE
    env = np.ones(n) * sustain
    a = t < attack
    env[a] = t[a] / attack
    d = (t >= attack) & (t < attack + decay)
    env[d] = 1 - (1 - sustain) * (t[d] - attack) / decay
    r = t > dur - release
    env[r] *= np.clip((dur - t[r]) / release, 0, 1)
    return env


def kick(n):
    t = np.arange(n) / RATE
    freq = 45 + 110 * np.exp(-t * 28)
    return np.sin(2 * np.pi * np.cumsum(freq) / RATE) * np.exp(-t * 9)


def snare(n, rng):
    t = np.arange(n) / RATE
    noise = rng.uniform(-1, 1, n)
    tone = np.sin(2 * np.pi * 185 * t)
    return (noise * 0.75 + tone * 0.35) * np.exp(-t * 22)


def hat(n, rng, open_=False):
    t = np.arange(n) / RATE
    noise = rng.uniform(-1, 1, n)
    noise = np.diff(noise, prepend=0)  # crude high-pass
    return noise * np.exp(-t * (14 if open_ else 70))


def compose(seconds):
    bpm = 128
    step = 60 / bpm / 4                      # one 16th note
    steps_total = int(seconds / step) + 16
    n_total = int(seconds * RATE) + RATE
    left = np.zeros(n_total)
    right = np.zeros(n_total)
    rng = np.random.default_rng(1998)

    progression_a = [["A2", "C4", "E4", "A4"], ["F2", "A3", "C4", "F4"], ["C3", "E4", "G4", "C5"], ["G2", "B3", "D4", "G4"]]
    progression_b = [["A2", "C4", "E4", "A4"], ["F2", "A3", "C4", "F4"], ["G2", "B3", "D4", "G4"], ["E2", "G#3", "B3", "E4"]]
    # lead melody: (note or None, length in 16ths), 2 bars per line
    melody = [
        ("E5", 2), ("A5", 2), ("G5", 1), ("E5", 1), ("D5", 2), ("E5", 4), (None, 2), ("C5", 2),
        ("D5", 2), ("E5", 2), ("G5", 2), ("A5", 2), ("G5", 4), ("E5", 4),
        ("A5", 2), ("C6", 2), ("B5", 1), ("A5", 1), ("G5", 2), ("E5", 4), (None, 2), ("G5", 2),
        ("F5", 2), ("E5", 2), ("D5", 2), ("B4", 2), ("E5", 8),
    ]
    melody_steps = list(melody)

    def add(buf, start, sig, gain):
        end = min(len(buf), start + len(sig))
        if end > start:
            buf[start:end] += sig[:end - start] * gain

    bars_total = steps_total // 16
    for bar in range(bars_total):
        prog = progression_b if (bar // 8) % 2 == 1 else progression_a
        chord = prog[bar % 4]
        section_intro = bar < 4
        breakdown = 18 <= bar < 22
        outro = bar >= bars_total - 4
        for s in range(16):
            i = bar * 16 + s
            start = int(i * step * RATE)
            # arpeggio: chord tones climbing, every 16th, stereo-offset for width
            tone = chord[1:][s % 3] if s % 8 < 6 else chord[3]
            octave_up = (s // 4) % 2 == 1
            freq = note_hz(tone) * (2 if octave_up else 1)
            n = int(step * RATE * 0.9)
            arp = square(freq, n, 0.25) * envelope(n, decay=0.08, sustain=0.35)
            gain = 0.09 if not outro else 0.09 * max(0.0, 1 - (bar - (bars_total - 4)) / 4)
            add(left, start, arp, gain)
            add(right, start + int(0.012 * RATE), arp, gain * 0.8)
            # bass on 8ths: root, root, octave, root
            if s % 2 == 0 and not section_intro and not outro:
                root = note_hz(chord[0]) * (2 if s % 8 == 4 else 1)
                bn = int(step * 2 * RATE * 0.85)
                bass = square(root, bn, 0.5) * envelope(bn, decay=0.1, sustain=0.7)
                add(left, start, bass, 0.11)
                add(right, start, bass, 0.11)
            # drums
            if not breakdown and not outro:
                if not section_intro and s in (0, 8) or (not section_intro and s == 10 and bar % 2 == 1):
                    k = kick(int(0.18 * RATE))
                    add(left, start, k, 0.5)
                    add(right, start, k, 0.5)
                if not section_intro and s in (4, 12):
                    sn = snare(int(0.14 * RATE), rng)
                    add(left, start, sn, 0.22)
                    add(right, start, sn, 0.22)
                if s % 2 == 0:
                    h = hat(int(0.12 * RATE), rng, open_=(s == 14 and bar % 4 == 3))
                    add(left, start, h, 0.05)
                    add(right, start, h, 0.07)
    # lay the melody over 8-bar blocks after the intro
    melody_len = sum(length for _, length in melody_steps)
    for block_start in range(6 * 16, (bars_total - 4) * 16, melody_len):
        bar = block_start // 16
        if 18 <= bar < 22:
            continue
        pos = block_start
        for note, length in melody_steps:
            if note and pos + length <= (bars_total - 4) * 16:
                start = int(pos * step * RATE)
                n = int(length * step * RATE * 0.95)
                lead = square(note_hz(note), n, 0.125, vibrato=0.006) * envelope(n, decay=0.2, sustain=0.55, release=0.05)
                add(left, start, lead, 0.075)
                add(right, start, lead, 0.075)
            pos += length

    n = int(seconds * RATE)
    mix = np.stack([left[:n], right[:n]], axis=1)
    mix = np.tanh(mix * 1.4) / np.tanh(1.4)
    mix /= max(1e-6, np.abs(mix).max()) / 0.85
    fade_in = int(0.4 * RATE)
    fade_out = int(3.0 * RATE)
    mix[:fade_in] *= np.linspace(0, 1, fade_in)[:, None]
    mix[-fade_out:] *= np.linspace(1, 0, fade_out)[:, None] ** 1.5
    return mix


def write_wav(path, audio):
    data = (audio * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data.tobytes())


# ------------------------------------------------------------------ output

def still(seconds, name, size=(1280, 720)):
    src = frame_path(min(int(seconds * FPS), len(os.listdir(FRAMES)) - 1))
    out = os.path.join(SITE, "img", "shots", name)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    Image.open(src).convert("RGB").resize(size, Image.LANCZOS).save(out, quality=88)


def write_stills():
    for seconds_at, name, _ in STILLS:
        still(seconds_at, name, (480, 270))
    manifest = []
    for seconds_at, name, caption in GALLERY:
        still(seconds_at, name)
        manifest.append({"file": name, "caption": caption})
    with open(os.path.join(SITE, "img", "shots", "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=1)
    print("stills written; run tools/gen_site.py to refresh the gallery page")


# Cloudflare Workers serve static assets up to 25 MiB each, so the video gets a bitrate budget
# instead of a fixed quality: two passes at whatever rate fills about 23.5 MiB.
MAX_BYTES = int(23.5 * 1024 * 1024)
AUDIO_KBPS = 96


def encode(music, seconds, out):
    os.makedirs(os.path.dirname(out), exist_ok=True)
    video_kbps = int(MAX_BYTES * 8 / 1000 / seconds * 0.97) - AUDIO_KBPS
    log = os.path.join(WORK, "x264pass")
    base = ["ffmpeg", "-y", "-loglevel", "error", "-framerate", str(FPS), "-i", os.path.join(FRAMES, "frame_%05d.jpg")]
    video = ["-vf", "scale=1920:1080:flags=lanczos:in_range=pc:out_range=tv", "-c:v", "libx264", "-preset", "slower",
             "-tune", "animation", "-b:v", "%dk" % video_kbps, "-maxrate", "%dk" % (video_kbps * 2), "-bufsize", "%dk" % (video_kbps * 4),
             "-pix_fmt", "yuv420p", "-color_range", "tv", "-passlogfile", log]
    subprocess.run(base + video + ["-pass", "1", "-an", "-f", "mp4", os.devnull], check=True)
    subprocess.run(base + ["-i", music] + video + ["-pass", "2", "-c:a", "aac", "-b:a", "%dk" % AUDIO_KBPS,
                                                   "-shortest", "-movflags", "+faststart", out], check=True)
    for f in os.listdir(WORK):
        if f.startswith("x264pass"):
            os.remove(os.path.join(WORK, f))
    print("video:", out, "%.1f MB at %d kbps" % (os.path.getsize(out) / 1e6, video_kbps))


def main():
    if "--stills" in sys.argv:
        write_stills()
        return
    if "--encode" in sys.argv:
        # re-encode the existing frames and music without recomposing anything
        frames = len([f for f in os.listdir(FRAMES) if f.endswith(".jpg")])
        encode(os.path.join(WORK, "music.wav"), frames / FPS, os.path.join(SITE, "video", "showcase.mp4"))
        return
    frames = fill_gaps()
    seconds = frames / FPS
    music = os.path.join(WORK, "music.wav")
    write_wav(music, compose(seconds))
    print("music: %.1f s" % seconds)

    video_dir = os.path.join(SITE, "video")
    encode(music, frames / FPS, os.path.join(video_dir, "showcase.mp4"))

    Image.open(frame_path(int(6.2 * FPS))).convert("RGB").resize((1280, 720), Image.LANCZOS).save(
        os.path.join(video_dir, "poster.jpg"), quality=88)
    write_stills()


if __name__ == "__main__":
    main()
