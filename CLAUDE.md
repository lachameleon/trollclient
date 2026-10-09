# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Troll Client: a client-only Fabric mod for Minecraft **26.2** (unobfuscated, Mojang names; Loom plugin `net.fabricmc.fabric-loom`). Needs **JDK 25** (`options.release = 25`). The repo also holds a Cloudflare Worker website (`website/`) and Python asset/site generators (`tools/`). There are no tests and no linter configured; verification is compiling plus the in-game harnesses below.

## Commands

```bash
./gradlew build          # jar -> build/libs/troll-client-1.0.0.jar
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

`website/README.md` documents the Worker endpoints, Durable Objects and page generation in detail.

## Architecture

Entry point `TrollClient.onInitializeClient` wires everything to Fabric events; read it first. Registration order matters there (e.g. `MODIFY_CHAT` listeners run Typo, then ChatStyle).

**Modules** (`module/<category>/`): every feature extends `Module` (name, description, `Category`, settings added via `add(...)`) and is registered by hand in `ModuleManager.init()`. Hooks: `onTick`, `onEntityAdded`, `onEntityEvent` (totem pops/deaths), `onDamage`, `onChatMessage`, `onWorldLeave`. `ModuleManager` dispatches these and catches `RuntimeException`s: a throwing module is logged, reported to telemetry and **disabled**, not propagated. Dispatch sources are not all in one place: ticks come from `TrollClient`, packet events from `ClientPacketListenerMixin`, chat from `ChatUtil`, keybinds from `KeyboardHandlerMixin`.

**Settings** (`setting/`): `Bool/Number/Mode/Text/ColorSetting`, each self-serializing (`toJson/fromJson/display/parse`), with `visibleWhen` and `onChange`. The GUI, `.set` command, tooltips and config all work off this generic interface.

**Config** (`config/ConfigManager`): one JSON file `trollclient.json` in the Fabric config dir, **keyed by module name and setting name**, so renaming either silently drops users' saved values. Saves are debounced via `markDirty()`; `isLoading()` suppresses dirtying during load. Macros persist separately in `trollclient-macros.json`.

**Movement and rotation arbitration** (`util/MovementControl`, `util/Rotations`): modules never press keys or set yaw directly. They *request* a movement direction, sneak, jump or silent rotation with a priority (`MovementControl.PRIORITY_*`; highest wins). Requests are reset at `START_CLIENT_TICK`, collected during module ticks, then applied by `KeyboardInputMixin` (after vanilla reads the keyboard) and `LocalPlayerMixin` (swaps the silent yaw in for the whole player tick). Rotations are quantized to mouse steps and no module sends its own rotation packets; actions request a rotation, then act on a later tick once `Rotations.isFacing`/`serverRayHit` confirm it. `pathing/` (`PathFinder`, `PathFollower`, `Walkability`) drives movement through the same API.

**Macros** (`macro/`, `gui/macro/MacroEditorScreen`): a `Macro` is a list of `MacroStep`s run by `MacroRun`, ticked after modules in the same start-tick so macro movement (`PRIORITY_MACRO`) outranks every module. Step kinds are registered in `Steps.ALL` (id, name, category, factory); implementations live in `macro/steps/*Steps` and use `Setting`s like modules do. Step ids are saved to disk.

**Packet gate** (`packet/PacketGate`, `GuiTools`, `ConnectionMixin`): every serverbound packet passes `PacketGate.intercept`, which can drop or queue GUI-related packets (container clicks, chat, etc.) while the GUI Tools module is enabled; keep-alives, movement and container-close always pass. Used by the GUI tools panel and the macro packet steps for de-sync tricks.

**Commands** (`command/Commands`): chat messages starting with a prefix (default `.`, configurable on `ClickGuiModule`) are intercepted in `ALLOW_CHAT` and never sent.

**GUI** (`gui/`): all drawing goes through `Theme` (palette fields recomputed each frame from `ThemeModule`; never hard-code colors) and `Draw`/`Anim`/`Motion`. `clickgui/ClickGuiScreen` is a large single-file window system; setting rows live in `clickgui/components/`. `TitleScreenGuard` replaces any main menu with `TrollTitleScreen` using three redundant mechanisms so other mods can't win.

**Mixins** (`mixin/`): must be listed in `src/main/resources/trollclient.mixins.json` (`client` array). `injectors.defaultRequire = 1`, so a target that disappears in a Minecraft update fails at startup instead of silently doing nothing. Injected members use the `troll$` prefix; `*Accessor` interfaces expose private vanilla fields.

**Telemetry** (`telemetry/Telemetry`): anonymous running-total reports to the website Worker (`website/src/worker.js`, `telemetry.js`); the Worker diffs totals per session. Privacy rules the code holds to: counts only, never chat text, command arguments, coordinates or IPs; identity is a random install id. `telemetry_url` in `gradle.properties` is baked into `fabric.mod.json` at build time (empty = never reports). Override at runtime with `-Dtrollclient.telemetry.url=...` / `-Dtrollclient.telemetry=false`. It is automatically off under Showcase/DevShots.

**Dev harnesses** (`dev/`: `DevShots`, `Showcase`, `CameraRig`, `FrameRecorder`, `Captions`): inert unless the JVM gets `-Dtrollclient.devshots=true` / `-Dtrollclient.showcase=true` (set by the Gradle run configs). A few mixins (`GameRendererMixin`, `LevelExtractorMixin`, `CameraMixin`, `AbstractClientPlayerMixin`) exist mainly for the showcase recorder and stand-in players.

## Website / generator coupling

`tools/gen_site.py` **parses the Java source with regexes** to build the website's module list (`website/public/js/data.js`, `modules.html`). To keep new code visible there:
- register modules as literal `register(new Foo());` lines in `ModuleManager.init()`;
- keep the `super("Name", "description", Category.X)` constructor call form in each module;
- declare settings as `new Bool|Number|Mode|Text|ColorSetting(...)` (optionally `.unit("...")`);
- `Category` enum entries must keep the `NAME("Label", Icon.X, "tagline")` shape.

After adding or changing modules, run `python3 tools/gen_site.py --pages`.

Other website rules: the shared window chrome in `website/partials/` is stamped into every page by `gen_site.py`, so **edit the partials, not the page copies**. `gen_site.py` copies `build/libs/troll-client-1.0.0.jar` (version hard-coded) into `website/public/downloads/` and fills in size/SHA-256, so bumping `version` in `gradle.properties` means updating `gen_site.py` and the download page too. Workers static assets are capped at 25 MiB per file (the showcase video is encoded to fit).

## Conventions

Java uses tabs, Javadoc-style `/** */` comments that explain *why*, and static-utility classes with private constructors for shared state (`MovementControl`, `Rotations`, `Theme`, `ConfigManager`). `run/` and `run-showcase/` are gitignored dev game directories.
