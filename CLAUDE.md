# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Troll Client: a client-only Fabric mod for Minecraft **26.3** (unobfuscated, Mojang names; Loom plugin `net.fabricmc.fabric-loom`). Needs **JDK 25** (`options.release = 25`). Only 26.3 is developed: the last 26.2 build (1.0.0, git tag `mc-26.2`) stays downloadable from the website and served by the updater, but gets no new work. The repo also holds a Cloudflare Worker website (`website/`) and Python asset/site generators (`tools/`). There are no tests and no linter configured; verification is compiling plus the in-game harnesses below.

## Commands

```bash
./gradlew build          # jar -> build/libs/troll-client-1.1.0.jar
./gradlew runClient      # dev client (run/); telemetry goes to http://localhost:8642 in dev runs
./gradlew runDevShots    # scripted visual smoke test (-Dtrollclient.devshots=true), screenshots -> run/screenshots
./gradlew runShowcase    # records the showcase video frames -> run-showcase/showcase/frames

npm --prefix website run dev   # website + Worker at http://localhost:8642 (also .claude/launch.json "website")
npm --prefix website run deploy

python3 tools/gen_site.py [--pages]   # regenerate website data/pages/pixel art from the Java source (needs Pillow; --pages skips pixel art)
python3 tools/gen_assets.py           # textures + 5x7 terminal font (stdlib only)
python3 tools/gen_skins.py            # SkinChanger skins/cape
python3 tools/gen_webfont.py          # website woff2 from the bitmap font (needs fontTools)
python3 tools/make_video.py [--encode]  # showcase frames -> website video (needs numpy, Pillow, ffmpeg)
```

`website/README.md` documents the Worker endpoints, Durable Objects, page generation, releasing an update and moderating in detail.

## Architecture

Entry point `TrollClient.onInitializeClient` wires everything to Fabric events; read it first. Registration order matters there (e.g. `MODIFY_CHAT` listeners run Typo, then ChatStyle).

**Modules** (`module/<category>/`): every feature extends `Module` (name, description, `Category`, settings added via `add(...)`) and is registered by hand in `ModuleManager.init()`. Hooks: `onTick`, `onEntityAdded`, `onEntityEvent` (totem pops/deaths), `onDamage`, `onChatMessage`, `onWorldLeave`. `ModuleManager` dispatches these and catches `RuntimeException`s: a throwing module is logged, reported to telemetry and **disabled**, not propagated. Dispatch sources are not all in one place: ticks come from `TrollClient`, packet events from `ClientPacketListenerMixin`, chat from `ChatUtil`, keybinds from `KeyboardHandlerMixin`.

**Settings** (`setting/`): `Bool/Number/Mode/Text/ColorSetting`, each self-serializing (`toJson/fromJson/display/parse`), with `visibleWhen` and `onChange`. The GUI, `.set` command, tooltips and config all work off this generic interface.

**Config** (`config/ConfigManager`): one JSON file `trollclient.json` in the Fabric config dir, **keyed by module name and setting name**, so renaming either silently drops users' saved values. Saves are debounced via `markDirty()`; `isLoading()` suppresses dirtying during load. Macros persist separately in `trollclient-macros.json`.

**Movement and rotation arbitration** (`util/MovementControl`, `util/Rotations`): modules never press keys or set yaw directly. They *request* a movement direction, sneak, jump or silent rotation with a priority (`MovementControl.PRIORITY_*`; highest wins). Requests are reset at `START_CLIENT_TICK`, collected during module ticks, then applied by `KeyboardInputMixin` (after vanilla reads the keyboard) and `LocalPlayerMixin` (swaps the silent yaw in from the start of the player tick until `sendChanges` has sent the movement packet; since 26.3 that packet no longer goes out from `tick`). Rotations are quantized to mouse steps and no module sends its own rotation packets; actions request a rotation, then act on a later tick once `Rotations.isFacing`/`serverRayHit` confirm it. `pathing/` (`PathFinder`, `PathFollower`, `Walkability`) drives movement through the same API.

**Macros** (`macro/`, `gui/macro/MacroEditorScreen`): a `Macro` is a list of `MacroStep`s run by `MacroRun`, ticked after modules in the same start-tick so macro movement (`PRIORITY_MACRO`) outranks every module. Step kinds are registered in `Steps.ALL` (id, name, category, factory); implementations live in `macro/steps/*Steps` and use `Setting`s like modules do. Step ids are saved to disk.

**Packet gate** (`packet/PacketGate`, `GuiTools`, `ConnectionMixin`): every serverbound packet passes `PacketGate.intercept`, which can drop or queue GUI-related packets (container clicks, chat, etc.) while the GUI Tools module is enabled; keep-alives, movement and container-close always pass. Used by the GUI tools panel and the macro packet steps for de-sync tricks.

**Commands** (`command/Commands`): chat messages starting with a prefix (default `.`, configurable on `ClickGuiModule`) are intercepted in `ALLOW_CHAT` and never sent. Each command has a `Spec` in `Commands.SPECS` (name, aliases, argument text, description, completer); `.help` and the chat tips (`command/CommandTips`, drawn and fed keys by `ChatScreenMixin`) are both generated from that list, so a new command needs a `Spec` as well as its `case` in `handle`.

**GUI** (`gui/`): all drawing goes through `Theme` (palette fields recomputed each frame from `ThemeModule`; never hard-code colors) and `Draw`/`Anim`/`Motion`. `clickgui/ClickGuiScreen` is a large single-file window system; setting rows live in `clickgui/components/`. `TitleScreenGuard` replaces any main menu with `TrollTitleScreen` using three redundant mechanisms so other mods can't win. The title screen's corner has the centre buttons (Discord, chatroom, forum, update notes): the two web ones hand a link to the browser, the other two open `centre/TrollCentreScreen`, which reads the room from `Chatroom.log()` (there is no chat to print into without a world) and the notes from `assets/trollclient/notes.txt` inside the jar.

**Input** (26.3 moved from GLFW to SDL3): key codes are SDL scancodes (`InputConstants.KEY_*`, compared with `KeyEvent.key()`; `shortcutKey()` is the layout keycode) and mouse buttons are `InputConstants.MOUSE_BUTTON_*` (left 1, middle 2, right 3), never bare numbers. Unbound is `Keys.NONE`. Both config files carry a `keyCodes: "sdl"` marker; a file without it is a 26.2 save and `Keys.fromGlfw` converts its keys once on load. There is no serverbound swing packet any more: use `util/Swing` (`local` after a use/attack the server swings for, `visible` for a swing other players should see, which sends a punch for the main hand).

**Mixins** (`mixin/`): must be listed in `src/main/resources/trollclient.mixins.json` (`client` array). `injectors.defaultRequire = 1`, so a target that disappears in a Minecraft update fails at startup instead of silently doing nothing. Injected members use the `troll$` prefix; `*Accessor` interfaces expose private vanilla fields.

**Talking to the website** (`util/Site`): three things reach the Worker, all at the origin of `telemetry_url` in `gradle.properties` (baked into `fabric.mod.json` at build time; empty = a build that talks to nothing; dev runs use `http://localhost:8642`). All three are off under Showcase/DevShots. `website/public/privacy.html` describes exactly what each one sends, so keep it in step with the code.
- **Telemetry** (`telemetry/Telemetry`): anonymous running-total reports (`website/src/telemetry.js` diffs totals per session). Privacy rules the code holds to: counts only, never chat text, command arguments, coordinates or IPs; identity is a random install id. There is no in-game switch (`TelemetryModule` is settings-only: Copy Report, Reset ID); `-Dtrollclient.telemetry=false` and `-Dtrollclient.telemetry.url=...` still work.
- **Updater** (`update/Updater`, `UpdaterModule`): on launch asks `/api/update` for the newest release for the running Minecraft version, verifies the jar (SHA-256, size, its own `fabric.mod.json` id/version, every `depends` entry against the running game) and drops it next to the current jar. Nothing is swapped in place: Fabric Loader loads the newest of two same-id jars, and the new version deletes older copies of itself at its next launch. Inert when the mod isn't a jar in a mods folder (dev runs). `-Dtrollclient.update=false` disables it.
- **Chatroom** (`chat/Chatroom`, `ChatroomModule`): a WebSocket to `/api/chat` (`website/src/chat.js`). Socket callbacks only queue work; all state is touched on the client thread in `Chatroom.tick`. The Minecraft name is sent (and proven through Mojang's session service) only when the player first talks. Room lines are printed with `ChatUtil.print`, which keeps them away from `onChatMessage` hooks and macro Wait Chat steps: strangers in the room must never be able to trigger either. That Mojang proof is also the site's only notion of authority: moderators are Minecraft accounts listed in the `MODERATORS` var in `website/wrangler.jsonc`, and `.mod ...` acts through the same socket (`.mod web` issues a 12-hour key for the website).

**Dev harnesses** (`dev/`: `DevShots`, `Showcase`, `CameraRig`, `FrameRecorder`, `Captions`): inert unless the JVM gets `-Dtrollclient.devshots=true` / `-Dtrollclient.showcase=true` (set by the Gradle run configs). A few mixins (`GameRendererMixin`, `LevelExtractorMixin`, `CameraMixin`, `AbstractClientPlayerMixin`) exist mainly for the showcase recorder and stand-in players.

## Website / generator coupling

`tools/gen_site.py` **parses the Java source with regexes** to build the website's module list (`website/public/js/data.js`, `modules.html`). To keep new code visible there:
- register modules as literal `register(new Foo());` lines in `ModuleManager.init()`;
- keep the `super("Name", "description", Category.X)` constructor call form in each module;
- declare settings as `new Bool|Number|Mode|Text|ColorSetting(...)` (optionally `.unit("...")`);
- `Category` enum entries must keep the `NAME("Label", Icon.X, "tagline")` shape.

After adding or changing modules, run `python3 tools/gen_site.py --pages`.

**Search engine tags are generated too**: each page's title, description, canonical link, link preview and JSON-LD sit between its `head` markers and come from `website/partials/head.html` plus the `SEO` table in `gen_site.py`, which also writes `sitemap.xml` and `robots.txt` (site origin = the origin of `telemetry_url`). Edit `SEO`, never the tags in the pages; a new page needs entries in both `PAGES` and `SEO`. The forum is the exception: one file draws every thread, so `js/forum.js` sets the canonical link, description and thread structured data per view, and the Worker serves `/sitemap-forum.xml`. `website/README.md` ("Search engines") has the details.

Other website rules: the shared window chrome in `website/partials/` is stamped into every page by `gen_site.py`, so **edit the partials, not the page copies**. `gen_site.py` reads `version` and `minecraft_version` from `gradle.properties`, copies `build/libs/troll-client-<version>.jar` into `website/public/downloads/`, fills in size/SHA-256 on the download page and rewrites `website/src/releases.json`, which is what the Worker tells the client's updater. Older releases stay in `releases.json` (and on the download page's "older minecraft versions" box) for as long as their jar is in `website/public/downloads/`. So a release is: bump `version`, add its section to `src/main/resources/assets/trollclient/notes.txt` (the in-game update notes), `./gradlew build`, `gen_site.py --pages`, deploy; the download page, the partials and the install steps still have the jar name written out and need the new version by hand. The Worker's own pieces: `worker.js` (routes, `TrollHQ`), `forum.js`, `chat.js` (`TrollChat`), `telemetry.js` (`TrollTelemetry`), `util.js`; a new Durable Object class needs a binding and a migration tag in `wrangler.jsonc`. **The Worker uses no secrets and must keep working without any**: the salt is generated and stored by `TrollHQ`, and anything that needs an admin goes through `moderator()` in `util.js`, never a token in the environment. Workers static assets are capped at 25 MiB per file (the showcase video is encoded to fit).

## Conventions

Java uses tabs, Javadoc-style `/** */` comments that explain *why*, and static-utility classes with private constructors for shared state (`MovementControl`, `Rotations`, `Theme`, `ConfigManager`). `run/` and `run-showcase/` are gitignored dev game directories.
