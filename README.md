# Troll Client

A black & white Fabric client for Minecraft **26.2**, with a CS:GO-cheat-menu style GUI, a demoscene title
screen, five black & white shaders, client-side skins, thirty-five modules for being mildly annoying, GUI packet
tools on every container, and a macro editor.

There's a 2000s homepage for it in [`website/`](website/README.md), a Cloudflare Worker with the showcase video, a
working browser copy of the menu, a skin animation lab for SkinBlink, and a real hit counter, guestbook and poll.

## Install

1. Install Fabric Loader `0.19.5+` for Minecraft 26.2 and drop
   [Fabric API](https://modrinth.com/mod/fabric-api) `0.161.0+26.2` into `mods/`.
2. Build the client with `./gradlew build` (needs Java 25) and copy `build/libs/troll-client-1.0.0.jar` into `mods/`.
3. In game, press **Right Shift** to open the menu.

## The menu

| Input | Does |
| --- | --- |
| Left click a module | toggle it |
| Right click a module | open its settings on the right (right click again to close) |
| Middle click a module | bind a key to it right there (Esc clears the bind) |
| Just start typing | search modules by name, description or setting name; matches are highlighted, Enter opens the selected hit |
| ↑ / ↓ | move the keyboard cursor through the module list |
| Space / Enter | toggle the module under the cursor / open its settings |
| ← / → or Tab | switch category |
| Drag the header | move the window (position is remembered); double click it to recenter |
| Ctrl + scroll | resize the menu |
| Right click a slider / text box / colour | reset or clear it |
| `reset` on the settings box | reset every setting of that module (click twice) |
| Palette button (top right) | cycle theme presets (right click goes backwards) |
| Power button (bottom left) | panic: switch off every module |
| Esc or Right Shift | close |

Things to look at: the CRT power-on open animation (also Assemble, where the window builds itself piece by
piece, plus Zoom, Drop, Glitch and Fade), a glint that sweeps across a module when you switch it on, the sliding tab
indicator, category name tags that slide out of the sidebar, LED pips showing how many modules are on per
category, equalizer bars and live status (current mode, target...) on enabled modules, a marching-ants
keyboard cursor, staggered settings rows, switches that spell out on/off, markers on settings you've changed
(the tooltip shows the default), segmented controls for short option lists, typewriter tooltips, and the fake shell prompt in the footer that logs what you do (and drops tips when
it's idle).

### Theming (Client → Theme)

- **Presets**: Noir (default), Paper, Graphite, Terminal, Newsprint, Ink (pure white, hard black lines), Fog
  (everything mid-grey), Custom (pick background, text and accent).
- **Font**: Minecraft, Terminal (a bundled 5×7 bitmap font) or Unicode.
- **Accent mode**: Static, Pulse, Shimmer or Rainbow, with a custom accent colour if you want one.
- **Corners**: Sharp, Notched or Round. Opacity, text shadow, glow.
- **Screen effects**: scanlines (with strength), film grain, vignette, dithered drop shadows, invert flash on open.

The theme reaches everything the client draws, title screen included: each preset has its own scene there
(warp starfield for Noir, a retro grid for Graphite, binary rain for Terminal, dust for Paper, newspaper
halftone for Newsprint, pen-plotter traces for Ink, rolling dithered fog for Fog), and glow, dither shadows,
corners, accent mode and the invert flash all apply to the logo and menu.

Client → ClickGUI holds the layout options: scale, open animation, animation speed, background (dim, blur,
both, none), backdrop (binary rain, retro grid, dust, starfield, oscilloscope waves, halftone, fog, or Theme to
follow the preset), sounds, tooltips and the command prefix.

### Title screen

Replaces the main menu (Client → TitleScreen, on by default): a background that follows the theme (on Noir, a
warp starfield that speeds up when you hover a button), a chunky 3D pixel logo that drops in letter by letter, a
demoscene sine scroller with editable text, monochrome splash text, menu items that "decrypt" out of noise when you
hover them, a fake BIOS boot log on first launch, and a keyboard-driven menu (↑/↓, Enter, or the number keys).
Click the background for a shockwave. `[ theme: noir ]` in the corner (or **T**, Shift+T to go back) cycles theme
presets right there, crossfading to the new preset's scene; Background picks a fixed scene instead of Theme.
`[ vanilla menu ]` shows the normal title screen, which gets a "Troll Client" button to come back.

It holds its ground against other mods: subclasses of the vanilla title screen are replaced too, modded main
menus (classes named `...TitleScreen` / `...MainMenu`) are replaced unless you turn off "Replace Modded Menus",
and a per-tick check catches mods that set the screen without going through the usual method. If a mod keeps
forcing its own menu back, Troll Client backs off after a few seconds instead of fighting it forever. With
[Mod Menu](https://modrinth.com/mod/modmenu) installed, a **mods** entry opens its mod list.

## GUI tools

Every inventory and container gets a small Troll Client window in the corner (Client → GuiTools, on by default).
Drag it by its header, fold it up with `-`, fold single sections by clicking their names.

| Button | Does |
| --- | --- |
| **send** | Send GUI packets. Off: container clicks, buttons, trades, renames, signs, books, item use, drops, chat and commands are thrown away instead of sent (the panel counts them). |
| **delay** | Hold those packets in a queue instead. The close packet, movement and keep-alives still go out, so the server sees you close the GUI before the clicks. Turning delay off sends the queue (Flush On Release). |
| **flush** / **clear** | Send the queue now, in order, or throw it away. The queue is listed under the buttons; click a packet to drop just that one. |
| **close** | Close the GUI on your side only. The server still thinks it's open. |
| **de-sync** | The opposite: tell the server the GUI closed while it stays open here. |
| **save** / **load** | Remember the GUI (screen and container) and bring it back later without asking the server. |
| **title** | Copy the GUI's title. |
| **disc+send** | Send the queue and disconnect right behind it. |
| **editor** / **stop** | Open the macro editor (closing it brings you back to the GUI) / stop every running macro. Your macros are listed underneath: click one to run or stop it, right click to edit it. |

There's a chat line too (with delay on, what you type waits in the queue as well), and a readout of the container's
state id (`rev`), its id, and the number of the slot under the mouse, which is what the Click Slot step wants. A badge
at the top of the HUD says when packets are being held or dropped, and when macros are running. Leaving a world puts
everything back to normal (Reset On Leave).

## Macros

A macro is a list of steps you run with a key, from the GUI tools panel, or with `.macro run <name>`. Open the editor
from the GUI tools, Client → Macros, or `.macro`: macros down the left, the selected macro's steps in the middle (drag
to reorder, right click or Space to switch one off, Delete, Ctrl+D, Ctrl+Z, Alt+↑/↓), and on the right the macro's
settings (name, key, loops), the selected step's settings, or **+ add**, the step palette. A running macro shows its
current step with crawling ants.

| Category | Steps |
| --- | --- |
| flow | Delay (ms or ticks), Repeat (the next N steps K times), Label, Goto (a few times, or forever), Stop, Chat (`{player}`, `{me}`, `{count}`), Notify, Module (on/off/toggle), Run Macro (and wait for it) |
| packets | Send Packets, Delay Packets, Flush Queue, Clear Queue, De-sync, Close GUI (with or without a packet), Save GUI, Restore GUI, Disconnect (after a flush) |
| inventory | Click Slot (pickup, quick move, swap, throw, clone, pickup all), Select Slot (by number or item name), Drop, Swap Hands, Open Inventory |
| interact | Use Item (or hold it), Use Block, Attack, Swing, Rotate (absolute, relative, silent), Look At Player |
| movement | Sneak (tap, hold, release), Jump, Sprint, Walk |
| wait | Wait GUI (a container opens, closes, or has a title), Wait Chat (with timeouts that stop or carry on) |

The GUI tools and macro editor follow the feature set of [AUTISM Client](https://github.com/AutismDevelopment/Autism-Client)'s
GUI tools and macro editor (themselves in the UI Utils tradition), redone in Troll Client's style; the code here is
written from scratch.

Steps run back to back within a tick until one waits; a full pass of the macro, or a jump backwards, always takes at
least a tick, so a loop can't freeze the game or fire a thousand packets at once. Macro keys also work inside container
GUIs. Macros live in `config/trollclient-macros.json`; the first run adds a harmless example, "bunny".

## Modules

| Module | Category | What it does |
| --- | --- | --- |
| **Twerk** | Troll | Silent server-side rotations (Twerk, Spin, Headbang, Jitter, Worm, Chaos), shift spam, arm flailing, on-the-spot sway. Visible to you in F5. |
| **CrystalCancel** | Combat | When another player's end crystal spawns, places the cheapest full block in your hotbar on the line between the crystal and your body. Delay is fixed or random (min/max). Ignores your own crystals. |
| **PlayerAvoid** | Movement | Runs from players inside a radius. Samples escape points, then runs an A* search that treats ground near players as expensive. Handles step-ups and drops up to a set height, avoids lava/fire/cactus etc. |
| **ArrowDodge** | Combat | Watches for bows/crossbows/tridents pointed at you and simulates arrows in flight, then strafes perpendicular to the shot. Never steps where there's no floor (pillar safe), sneaks near edges, ducks head-height shots, optionally jumps feet-height ones. |
| **PlayerFollow** | Movement | Follows a named player (tab-completes from the tab list) or the nearest one, pathfinding around obstacles and keeping a set distance. Can stare at them. |
| **ItemPickup** | Player | Notices items dropped by players (attributed by where nearby players' hands and feet were) and pathfinds over to grab them. |
| **WindAnnoy** | Troll | Throws wind charges at players in range, with target prediction, line-of-sight checks and fixed or random delays. |
| **NoWayHome** | Troll | Walks along the direction a player is facing and places a block in front of them at their Y level (and head level for a 2-high wall), trying every block in their line of sight that you can reach. |
| **FoodAnnoy** | Troll | Starts eating, stops a few ticks before the last bite, repeats. All of the noise, none of the food. |
| **PopCounter** | Combat | Counts totem pops per player. HUD pop-ups, and announces pops and deaths ("died after popping 3 totems") to just you or the whole chat. |
| **Orbit** | Movement | Walks circles around a player at a set radius, clockwise, counter-clockwise or switching. Reverses at ledges, walls and lava. Optional bunny hop and stare. |
| **Mimic** | Troll | Copies a player's crouching, jumping, arm swings and head direction, and optionally their steps. Mirror mode reflects everything as if there were a mirror halfway between you. |
| **Morse** | Troll | Spells a message in Morse code by crouching (or swinging), with a configurable unit length. Can wait until someone's close enough to read it. |
| **Parrot** | Chat | Repeats other players' chat back at them: mOcKiNg, echo, reversed, uwu, SHOUTED or whispered. Works with signed chat and with plugin-formatted chat. Delay, cooldown and chance settings keep you from getting kicked. |
| **Taunt** | Troll | When a nearby player dies (or pops a totem) it says something from your list of lines, walks over and teabags the spot. |
| **Racket** | Troll | Flaps every door, trapdoor, fence gate and lever in reach, rings bells, presses buttons and plays note blocks, by default only near other players. |
| **Gifter** | Troll | Throws junk from your hotbar (or your cheapest block, or whatever you're holding) at nearby players. |
| **Trapper** | Combat | Boxes a standing-still player in with your cheapest blocks: walls, head height and a lid (or just the walls, or just the lid). |
| **Stalker** | Movement | Creeps along right behind a player, out of their field of view, and crouches once it's there. Angel mode only moves while they aren't looking and freezes the moment they turn round. |
| **Pelter** | Troll | Throws snowballs and eggs at players, solving the throw's arc (flat or lobbed) and leading moving targets. |
| **Confetti** | Troll | Launches firework rockets at players' feet, around you, or as a volley when someone nearby dies. |
| **ChatStyle** | Chat | Rewrites your own messages: ꜱᴍᴀʟʟ ᴄᴀᴘꜱ, ｆｕｌｌｗｉｄｔｈ, ⓑⓤⓑⓑⓛⓔ, l33t, mOcK, uwu or reversed, plus a prefix and a signature. |
| **Spammer** | Chat | Sends lines from your list on a timer, with a random tag so duplicate filters let it through and an optional stop-after count. |
| **Announcer** | Chat | "I just mined 32 stone thanks to Troll Client!" Brags about blocks mined and placed, distance walked and jumps, publicly or just to you. |
| **SkinBlink** | Player | Flickers your hat, jacket, sleeves, trousers and cape (blink, wave, random or strobe) so your skin strobes for everyone, always or only while in the air, moving, standing still, sneaking, sprinting, hurt or using an item. Always puts your skin settings back. |
| **SkinChanger** | Player | Wear one of five bundled black & white skins (troll, mime, referee, ghost, hacker) and the Troll Client cape. Client-side only. |
| **ItemFlex** | Player | Cycles your hotbar so your held item strobes for everyone: cycle, bounce, random or flash patterns. Pauses while you're busy and puts your slot back afterwards. |
| **Grudge** | Combat | Whoever hurts you (a punch, an arrow, a trident) becomes the Target of every module that has one, until it wears off or they die. Optional payback module (Orbit, Stalker, Mimic, Goalie...) switched on for the duration, and a "noted, {player}" line to them or just you. |
| **Goalie** | Movement | Plants itself in front of a player, along where they're walking (led a few ticks ahead) or where they're looking, and matches every sidestep. Jumps when they jump, never walks off ledges, optional crouch spam. |
| **Graffiti** | Troll | Places standing signs with your messages where a nearby player will read the front, then fills in the text without ever showing the sign editor. `/` breaks lines, long lines wrap, `{player}` and `{me}` work. |
| **Honk** | Troll | Blows a goat horn at players who walk up to you, whenever someone's near, or nonstop, pointing it at them. |
| **Greeter** | Chat | Welcomes players who join, says bye when they leave, and says hi (with a wave) to players who walk up. Public or just to you, with a cooldown and a cap so a server restart doesn't become a minute of greetings. |
| **AutoReply** | Chat | Answers people who say your name or ask a question: Magic 8-Ball, your own lines, or "no u". Delay, cooldown and chance settings. |
| **Typo** | Chat | Misspells one word in your messages (swapped, fat-fingered, doubled or dropped letters), then sends a `*correction` a moment later. Leaves lines other modules send alone. |
| **Juggle** | Player | Tosses your held item back and forth between your hands so everyone sees you juggling. Pauses while you're busy and always ends with both hands holding what they started with. |

Client modules: **GuiTools** and **Macros** (above), **Shaders** (post-processing for the world: film noir, monochrome CRT, 1-bit ordered dither,
newspaper halftone, pen & ink; menus and HUD stay sharp), **ClickGUI**, **HUD** (watermark styles, animated array list that ducks under vanilla toasts,
notifications, coordinates, FPS, a sweep **radar** of nearby players, and a **target HUD** card with the face,
segmented health bar and distance of whoever a module is targeting), **Theme**, **TitleScreen**.

## Chat commands

Prefix defaults to `.` (change it under ClickGUI).

```
.toggle <module>                 .bind <module> <key|none>
.follow [player]                 .friend add|remove|list [name]
.target <player|nearest>         .morse <message>
.set <module> [setting] [value]  .modules   .gui   .save   .load
.macro [list|run|stop|edit] [name]
```

`.target` sets the Target of every module that has one (PlayerFollow, Orbit, Stalker, Goalie, Mimic, Trapper, Parrot),
and so does Grudge, automatically. Friends are
ignored by every targeting module that has an "Ignore Friends" option.

Chat templates (PopCounter, Taunt, Spammer, Grudge, Greeter, AutoReply, Graffiti) take several options split with `|` and fill in `{player}`, `{count}`,
`{s}` (plural "s") and `{me}`. A line starting with `/` is sent as a command.

## Config

Everything (module states, binds, settings, friends, window positions) lives in `config/trollclient.json` (macros in
`config/trollclient-macros.json`) and
saves itself a moment after you change something.

## Development

```bash
./gradlew build          # jar in build/libs
./gradlew runClient      # dev client
./gradlew runDevShots    # scripted smoke test: menus, themes, a test world, screenshots in run/screenshots
./gradlew runShowcase    # records the showcase video's frames (run-showcase/showcase/frames)
python3 tools/make_video.py   # frames + generated chiptune -> website/public/video/showcase.mp4, plus stills
python3 tools/gen_site.py     # website pages and data (modules from the source), pixel art, gallery, download info
cd website && npm run dev     # the website as a Worker, on localhost:8642
python3 tools/gen_assets.py   # regenerate textures, icons and the terminal font
python3 tools/gen_skins.py    # regenerate the SkinChanger skins and cape
python3 tools/gen_webfont.py  # the terminal font as a web font for the website
```

Minecraft 26.x ships unobfuscated, so the code uses Mojang's names directly (no Yarn). Notable 26.2
renames if you're porting: `GuiGraphics` → `GuiGraphicsExtractor` (`drawString` → `text`), `render` →
`extractRenderState`, `ResourceLocation` → `Identifier`, and screens are opened with
`minecraft.gui.setScreen(...)`.

Source layout:

```
com.trollclient
├── module/      Module base, categories, manager, and the modules themselves
├── setting/     Bool / Number / Mode / Text / Color settings
├── pathing/     walking-only A* pathfinder + path follower
├── util/        silent rotations, movement override, block placement, targeting
├── packet/      the GUI packet gate (drop / delay / flush) and the screen tricks
├── macro/       macros, the step registry, the runner; steps/ has the steps by category
├── gui/         theme, drawing helpers, animations, backdrop + screen effects
│   ├── clickgui/  the menu and its setting rows
│   ├── title/     title screen + pixel logo font
│   ├── tools/     the GUI tools panel drawn over containers
│   ├── macro/     the macro editor
│   └── hud/       HUD and notifications
├── command/     chat commands
├── config/      JSON config
├── mixin/       input, rotation, title screen, keybind, entity/damage event, sign editor, packet gate, placement and toast hooks
└── dev/         DevShots smoke test and the Showcase recorder (camera rig, captions, frame capture);
                 only active with -Dtrollclient.devshots=true / -Dtrollclient.showcase=true
```

Use it on servers where this kind of thing is welcome. Plenty of servers consider automated movement,
silent rotations and auto-placing blocks cheating.
