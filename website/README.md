# Troll Client homepage

A mid-2000s homepage for the client, kept in good repair, running as a Cloudflare Worker. The static
files in `public/` are served straight from Workers static assets; a small Worker (`src/worker.js`)
handles the parts that have to be real: the hit counter, who's online, download counts, the guestbook,
the poll, and skins for the skin animation lab.

```bash
cd website
npm install
npm run dev        # http://localhost:8642, with a local Durable Object
npm run deploy     # wrangler deploy (log in first with npx wrangler login)
```

Optional secrets: `npx wrangler secret put ADMIN_TOKEN` lets you hide guestbook entries
(`curl -X DELETE -H "authorization: Bearer $TOKEN" https://<site>/api/guestbook/<id>`), and
`npx wrangler secret put SALT` salts the daily visitor hashes. Durable Objects with SQLite storage
work on the Workers free plan.

## Pages

| Page | What's on it |
| --- | --- |
| `index.html` | title-screen hero (canvas port of the in-game one), the skin animation feature with a 3D model, TROLL-VISION (the showcase video on channel 1, the shaders on 2 to 6), a working copy of the menu, features, the SkinChanger skins in 3D, changelog, theme picker |
| `skin-animation.html` | the skin animation lab: SkinBlink's real settings driving a 3D player, simulated actions for the triggers, a tick-by-tick layer timeline, and any Minecraft player's skin |
| `modules.html` | every module and its settings, generated from the Java source, with live filtering (`?q=` works) |
| `gallery.html` | stills from the showcase recording, with a lightbox that zooms out of the thumbnail |
| `download.html` | the jar, its size and SHA-256, the live download count, install steps, controls, commands, FAQ |
| `guestbook.html` | a real guestbook: entries are stored by the Worker and new ones show up live |
| `analytics.html` | anonymous usage from the client itself: active installs over time, every module's users and time on, setups (OS, GPU, Java, versions, mods...), when people play, servers, commands, settings, errors, plus what's collected and how to turn it off |
| `404.html` | served for anything that doesn't exist |

The window chrome (title bar, tabs, toolbar, ticker, sidebar, status bar, taskbar, start menu) lives in
`partials/` and is stamped into every page by `tools/gen_site.py`, between the `chrome:top` and
`chrome:bottom` markers. Edit the partials, not the copies.

## What's live

- **Hit counter**: `POST /api/hit` once per page view; a visit counts once per browser session (and at most
  once per half hour per visitor). The odometer and the stats in the sidebar update over a WebSocket
  (`/api/live`), so they roll when someone else arrives.
- **Online now**: the number of open WebSockets. They use the hibernation API, so idle tabs cost nothing.
- **Downloads**: the Worker counts every `GET` of `/downloads/*.jar` that succeeds.
- **Guestbook**: `GET/POST /api/guestbook`, 500 characters, one entry per 45 seconds per visitor, a honeypot
  field for bots.
- **Poll**: `GET/POST /api/poll`, one vote per visitor per day.
- **Tallies**: modules toggled in the menu demo and layer changes played in the skin lab, batched by the
  page into `POST /api/bump` every few seconds.
- **Skins**: `GET /api/skin/<name>` looks a player up with Mojang and returns their skin (cached for an hour).
- **Client telemetry**: the client's Telemetry module posts `POST /api/telemetry` on launch, every five minutes and on quit.
  Each report carries running totals for the session; a second Durable Object, `TrollTelemetry` (`src/telemetry.js`),
  keeps the last totals per session and adds only the difference to per-day tables, so lost or repeated reports never
  double-count. `GET /api/analytics?days=1|7|30|90|all` is what the analytics page draws (public, aggregates only);
  `GET /api/analytics/sessions` (with `authorization: Bearer $ADMIN_TOKEN`) returns the newest raw sessions, and the
  page unlocks that view too. Clients are told apart by a random install id; location is the country, continent and
  region from Cloudflare, never the IP.

The client finds the endpoint in `telemetry_url` in `gradle.properties` (baked into `fabric.mod.json` at build time;
empty means a build that never reports). Dev runs (`./gradlew runClient`) report to `http://localhost:8642` instead, and
`-Dtrollclient.telemetry.url=...` or `-Dtrollclient.telemetry=false` override both.

Visitors are told apart by a SHA-256 of their IP and a salt that changes every day. No IP is stored.
If the site is served without the Worker (any plain static host), everything still works except the live
parts, which fall back to a counter that only counts you.

## The look

Same seven palettes as the client (remembered per visitor, applied before first paint) and the client's 5x7
terminal font. Things to find: a desktop with a 1-bit dithered wallpaper and icons you can drag, a window
that springs back when you drag its title bar, minimize to the taskbar, a close button whose "Yes" runs away,
a start menu with "turn off computer", tray balloons for live events, an LCD ticker, a mechanical odometer,
tabs that decrypt when hovered, theme changes that wipe in from where you clicked, view transitions between
pages, a status bar that shows where links go, a rocket back to the top, opt-in chiptune sounds, a cursor
trail, a Konami code, and something that happens if you type "skinblink". Animations respect
`prefers-reduced-motion`.

## Regenerating

Most of `public/` comes from the repo:

```bash
./gradlew build                     # the jar the download page links to
./gradlew runShowcase               # records the showcase video's frames (about 2 minutes, in a real client)
python3 tools/make_video.py         # frames + generated chiptune -> public/video/showcase.mp4, poster, stills
python3 tools/make_video.py --encode   # just re-encode the existing frames
python3 tools/gen_site.py           # page chrome, js/data.js, modules table, gallery, jar + checksum, skins, pixel art
python3 tools/gen_site.py --pages   # the same without redrawing the pixel art
python3 tools/gen_webfont.py        # fonts/troll-terminal.woff2 from the client's bitmap font
```

Workers static assets are limited to 25 MiB per file, so `make_video.py` encodes the video in two passes at
whatever bitrate fills about 23.5 MiB.

The showcase recorder (`com.trollclient.dev.Showcase`) creates a fresh creative world, builds a stage, spawns
client-side stand-in players wearing the SkinChanger skins, and films each module with a free camera, a different
shader per scene, and captions drawn in the client's own style. Nothing in it runs unless the JVM gets
`-Dtrollclient.showcase=true`. The soundtrack is synthesized from scratch by `make_video.py` (square waves and noise),
so the video has no licensing strings attached.
