# Troll Client homepage

A mid-2000s homepage for the client, kept in good repair, running as a Cloudflare Worker. The static
files in `public/` are served straight from Workers static assets; a small Worker (`src/worker.js`)
handles the parts that have to be real: the hit counter, who's online, download counts, the forum,
the poll, skins for the skin animation lab, and three things the client itself talks to: usage stats,
the updater and the chatroom.

```bash
cd website
npm install
npm run dev        # http://localhost:8642, with a local Durable Object
npm run deploy     # wrangler deploy (log in first with npx wrangler login)
```

There are no secrets to set. The salt behind visitor hashes and forum tripcodes is made up by the Worker the first
time it runs and kept in its Durable Object storage. Moderators aren't a password either: they are Minecraft accounts
listed in `MODERATORS` in `wrangler.jsonc` (see "Moderating" below). Durable Objects with SQLite storage work on the
Workers free plan.

## Pages

| Page | What's on it |
| --- | --- |
| `index.html` | title-screen hero (canvas port of the in-game one), the skin animation feature with a 3D model, TROLL-VISION (the showcase video on channel 1, the shaders on 2 to 6), a working copy of the menu, features, the SkinChanger skins in 3D, changelog, theme picker |
| `skin-animation.html` | the skin animation lab: SkinBlink's real settings driving a 3D player, simulated actions for the triggers, a tick-by-tick layer timeline, and any Minecraft player's skin |
| `modules.html` | every module and its settings, generated from the Java source, with live filtering (`?q=` works) |
| `gallery.html` | stills from the showcase recording, with a lightbox that zooms out of the thumbnail |
| `download.html` | the jar, its size and SHA-256, the live download count, install steps, controls, commands, FAQ |
| `forum.html` | the forum the guestbook grew into: boards, threads, replies, search, and the in-game chatroom read live. One page, drawn by `js/forum.js`; `/guestbook` redirects here (`public/_redirects`) |
| `analytics.html` | anonymous usage from the client itself: active installs over time, every module's users and time on, setups (OS, GPU, Java, versions, mods...), when people play, servers, commands, settings, errors, plus what's collected |
| `privacy.html` | what the site and the client collect, in plain words. It describes the code, so change it when the code changes |
| `404.html` | served for anything that doesn't exist |

The window chrome (title bar, tabs, toolbar, ticker, sidebar, status bar, taskbar, start menu) lives in
`partials/` and is stamped into every page by `tools/gen_site.py`, between the `chrome:top` and
`chrome:bottom` markers. Edit the partials, not the copies.

## Search engines

Everything a search engine or a link preview reads is generated too, so none of it is written in the pages:

- **`<head>` tags**: `partials/head.html` is stamped between the `head` markers of every page, filled in from the
  `SEO` table at the top of `tools/gen_site.py`: title, description, canonical link, Open Graph/Twitter preview
  (a screenshot from `img/shots`), and schema.org structured data (the site and page, breadcrumbs, the client as a
  `SoftwareApplication`, the showcase video, the gallery's images, and each FAQ read off the page it's on). The
  module count, version and Minecraft version in them come from the source and `gradle.properties`. To change what a
  page says about itself, edit `SEO` and run `gen_site.py --pages`; a new page needs an entry there as well as in `PAGES`.
- **`sitemap.xml`** lists every page with the date it last changed (its last commit), its screenshots and the video;
  **`robots.txt`** points at it and keeps crawlers off the addresses that count something when fetched (the jar, the
  hit counter) or go on to Mojang (`ROBOTS_DISALLOW`). The rest of `/api/` stays open, because the forum and the
  analytics page are drawn from it; the Worker marks every JSON answer `x-robots-tag: noindex` instead.
- **The forum** is one file that draws every board and thread, so `forum.html` has no canonical link of its own:
  `js/forum.js` sets the canonical address, description and title for whatever view is on screen, adds
  `DiscussionForumPosting` structured data to threads, and marks searches and missing threads `noindex`. Thread links
  only exist once that script has run, so the Worker also serves **`/sitemap-forum.xml`** (every board and visible
  thread, straight from the Durable Object).
- The site's address in all of this is the origin of `telemetry_url` in `gradle.properties`.

After a deploy that changes pages, nothing needs submitting again: Search Console re-reads the sitemaps on its own
(add both there once).

## What's live

- **Hit counter**: `POST /api/hit` once per page view; a visit counts once per browser session (and at most
  once per half hour per visitor). The odometer and the stats in the sidebar update over a WebSocket
  (`/api/live`), so they roll when someone else arrives.
- **Online now**: the number of open WebSockets. They use the hibernation API, so idle tabs cost nothing.
- **Downloads**: the Worker counts every `GET` of `/downloads/*.jar` that succeeds.
- **Forum** (`src/forum.js`): five fixed boards, threads and posts in TrollHQ's SQLite. No accounts: a post has a name, and
  `name#secret` becomes a tripcode (a salted hash of the secret; the secret isn't kept). Posting returns a random key
  that the browser keeps, which lets that browser edit the post for 30 minutes and remove it whenever (`x-post-key`).
  `GET /api/forum` (boards + latest posts), `/api/forum/board/<id>?page=`, `/api/forum/thread/<id>?page=N|last`,
  `/api/forum/find/<post>` (for `>>123` links), `/api/forum/search?q=`; `POST /api/forum/thread` and
  `/api/forum/thread/<id>/reply`; `PATCH`/`DELETE /api/forum/post/<id>`. One thread per two minutes and one reply per
  20 seconds per visitor, 4000 characters, a honeypot field for bots. The old guestbook's entries were copied into a
  pinned thread on the Guestbook board the first time the forum ran; the `guestbook` table itself is left alone.
- **Chatroom** (`src/chat.js`, the `TrollChat` object): `/api/chat` is a WebSocket every running client holds open
  (`?role=game`); `.chat <message>` in game talks into it and the forum page listens (`?role=web`, read only).
  Listening needs nothing. Talking needs the player's Minecraft name, proven like a server login: the client "joins"
  a one-off id (a hash of the room's challenge and its own nonce) with Mojang's session server, and the Worker asks
  `hasJoined`. No token comes here. Accounts Mojang doesn't vouch for can still talk, marked unverified. 200
  characters, one message per 1.5 s and eight a minute, the last 500 kept. `GET /api/chat/history` is the scrollback.
- **Updates**: `GET /api/update?version=1.1.0&minecraft=26.3` answers from `src/releases.json` with the newest release
  built for that Minecraft version, or `update: false`. The jar itself comes from `GET /api/update/<file>.jar`, which
  serves the same file as `/downloads/` but bumps an `updates` counter instead of the download count.
- **Poll**: `GET/POST /api/poll`, one vote per visitor per day.
- **Tallies**: modules toggled in the menu demo and layer changes played in the skin lab, batched by the
  page into `POST /api/bump` every few seconds.
- **Skins**: `GET /api/skin/<name>` looks a player up with Mojang and returns their skin (cached for an hour).
- **Client telemetry**: the client's Telemetry module posts `POST /api/telemetry` on launch, every five minutes and on quit.
  Each report carries running totals for the session; a second Durable Object, `TrollTelemetry` (`src/telemetry.js`),
  keeps the last totals per session and adds only the difference to per-day tables, so lost or repeated reports never
  double-count. `GET /api/analytics?days=1|7|30|90|all` is what the analytics page draws (public, aggregates only);
  `GET /api/analytics/sessions` (moderators only, see "Moderating") returns the newest raw sessions, and the
  page unlocks that view too. Clients are told apart by a random install id; location is the country, continent and
  region from Cloudflare, never the IP.

The client finds the endpoint in `telemetry_url` in `gradle.properties` (baked into `fabric.mod.json` at build time;
empty means a build that never talks to the site at all: no reports, no updates, no chatroom). Dev runs
(`./gradlew runClient`) use `http://localhost:8642` instead. `-Dtrollclient.telemetry.url=...` or
`-Dtrollclient.telemetry=false` override the reports, `-Dtrollclient.site=...` moves the updater and the chatroom,
and `-Dtrollclient.update=false` / `-Dtrollclient.chatroom=false` switch those off.

## Releasing an update

The client's updater installs whatever `src/releases.json` describes, so that file has to match the jars in
`public/downloads/`. `tools/gen_site.py` writes it:

```bash
# bump `version` in gradle.properties first
./gradlew build
python3 tools/gen_site.py --pages    # copies the jar, records its size, SHA-256 and Minecraft version
npm --prefix website run deploy
```

Every client on that Minecraft version picks it up on its next launch. Older entries stay in the list for as long
as their jar is still in `public/downloads/`, so after moving to a new Minecraft version, clients on the old one
keep getting the last build made for them and are never offered the new one. The download page lists those older
builds too (an "older minecraft versions" box, one per Minecraft version, written by `gen_site.py` from the same list),
so deleting an old jar from `public/downloads/` is what retires it everywhere. The client checks again on its side
before installing: checksum, size, the jar's own `fabric.mod.json` (mod id, version, and every `depends` entry
against what's actually running).

## Moderating

Moderators are Minecraft accounts, listed in the `MODERATORS` var in `wrangler.jsonc`: UUIDs (best, they survive a
name change) or names, comma separated. It's public configuration, not a secret, and empty means nobody moderates.
The chatroom already proves which account a client belongs to (the Mojang check described above), and that proof is
the only authority there is. Don't know your UUID? Type `.mod` in game and the answer includes it. Then:

```
.mod mute <player> [hours]     can't talk in the chatroom (a day at most; ~name for someone unverified)
.mod unmute <player>
.mod unsay <player>            take down what they said in the last hour
.mod post <id>                 remove a forum post (the number after its #)
.mod thread <id> lock|unlock|pin|unpin|hide|show
.mod web                       a key for the website, good for 12 hours (click it in chat to copy)
```

Paste the key under "moderator?" at the bottom of the forum and every post gets a remove button and every thread
gets lock, pin and hide; on the analytics page it unlocks the raw sessions. The same key works as
`authorization: Bearer <key>` on `DELETE /api/forum/post/<id>`, `PATCH /api/forum/thread/<id>`,
`DELETE /api/chat/<id>`, `POST /api/chat/<id>/mute` and `GET /api/analytics/sessions`. Keys stop working after 12
hours or as soon as their account leaves `MODERATORS`, and every `.mod` action is logged with who did it.

Visitors are told apart by a SHA-256 of their IP, the site's salt and the date, so it changes every day. No IP is stored.
If the site is served without the Worker (any plain static host), everything still works except the live
parts, which fall back to a counter that only counts you.

## The look

Seven of the client's black and white palettes plus its Frutiger Aero preset in colour (glass window, a Bliss-ish
wallpaper, a green start button; it also swaps the pixel font for Segoe UI through the `--px` CSS variable). The
pick is remembered per visitor and applied before first paint, and the theme button sparkles until it's been
clicked once. Everything else is in the client's 5x7 terminal font. Things to find: a desktop with a 1-bit dithered wallpaper and icons you can drag, a window
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
python3 tools/gen_site.py           # page chrome, head tags, sitemap, robots, js/data.js, modules table, gallery, jar + checksum, release list, skins, pixel art
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
