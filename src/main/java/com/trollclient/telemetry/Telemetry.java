package com.trollclient.telemetry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.trollclient.TrollClient;
import com.trollclient.config.ConfigManager;
import com.trollclient.dev.DevShots;
import com.trollclient.dev.Showcase;
import com.trollclient.gui.Icon;
import com.trollclient.gui.hud.Notifications;
import com.trollclient.macro.MacroManager;
import com.trollclient.module.Module;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.ClickGuiModule;
import com.trollclient.module.client.TelemetryModule;
import com.trollclient.module.client.ThemeModule;
import com.trollclient.setting.Setting;
import com.trollclient.util.Friends;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Anonymous usage stats for the analytics page on the website. Everything is counted on the
 * client thread and posted as running totals for the session (start, every few minutes, and on
 * quit), so a lost or repeated report never double-counts: the Worker only adds what's new.
 *
 * <p>Identity is a random install id in its own file, never the Minecraft account. No chat text,
 * command arguments, coordinates, IPs or tokens are ever read. Turn it off with the Telemetry
 * module or {@code -Dtrollclient.telemetry=false}.
 */
public final class Telemetry {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int PROTOCOL = 1;
	private static final long BEAT_MS = 5 * 60_000;
	/** Give the first report time for the GPU and the title screen to exist. */
	private static final long START_MAX_WAIT_MS = 30_000;
	private static final long SETTING_DEBOUNCE_MS = 2000;
	private static final int MAX_KEYS = 150;

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private static final class ModuleStats {
		long on, off, ms, key, cmd, err;
	}

	private static final class Timed {
		long n, ms;
	}

	private static final Map<String, ModuleStats> MODULES = new LinkedHashMap<>();
	private static final Map<String, Long> SETTINGS = new LinkedHashMap<>();
	private static final Map<String, Long> COMMANDS = new LinkedHashMap<>();
	private static final Map<String, Long> COUNTS = new LinkedHashMap<>();
	private static final Map<String, Long> ERRORS = new LinkedHashMap<>();
	private static final Map<String, Timed> SCREENS = new LinkedHashMap<>();
	private static final Map<String, Timed> SERVERS = new LinkedHashMap<>();
	private static final Map<Setting<?>, String> SETTING_NAMES = new IdentityHashMap<>();
	private static final Map<Setting<?>, Long> SETTING_LAST = new IdentityHashMap<>();

	private static String install;
	private static String session;
	private static long launches;
	private static long firstLaunch;
	private static long previousLaunch;
	private static boolean told;
	private static JsonObject crashes = new JsonObject();

	private static boolean ready;
	private static boolean started;
	private static long bootAt;
	private static long nextBeat;
	private static int seq;
	private static volatile boolean inFlight;

	private static long lastTick;
	private static long msTotal, msFocused, msIngame, msSingle, msMulti, msMenu, msPaused;
	private static long fpsSum, fpsSamples, fpsMin = Long.MAX_VALUE, fpsMax, nextFpsSample;
	private static Class<?> lastScreen;
	private static String currentServer;
	private static boolean wasDead;
	private static String via;

	private Telemetry() {
	}

	/** After modules and config are loaded. */
	public static void init() {
		session = UUID.randomUUID().toString();
		bootAt = System.currentTimeMillis();
		for (Module m : ModuleManager.all()) {
			for (Setting<?> s : m.getSettings()) {
				SETTING_NAMES.put(s, m.getName() + "/" + s.getName());
			}
		}
		loadState();
		crashes = crashesSince(previousLaunch);
		ready = true;
	}

	/** Off in dev recordings, with -Dtrollclient.telemetry=false, without an endpoint, or with the module off. */
	public static boolean active() {
		if (!ready || Showcase.enabled() || DevShots.enabled() || "false".equals(System.getProperty("trollclient.telemetry"))) {
			return false;
		}
		TelemetryModule module = ModuleManager.get(TelemetryModule.class);
		return module != null && module.isEnabled() && !endpoint().isEmpty();
	}

	/** -Dtrollclient.telemetry.url wins; dev runs talk to `npm run dev`; releases use the URL baked into fabric.mod.json. */
	public static String endpoint() {
		String override = System.getProperty("trollclient.telemetry.url");
		if (override != null) {
			return override.trim();
		}
		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return "http://localhost:8642/api/telemetry";
		}
		return FabricLoader.getInstance().getModContainer(TrollClient.MOD_ID)
				.map(c -> c.getMetadata().getCustomValue("trollclient:telemetry"))
				.map(v -> v.getAsString().trim())
				.orElse("");
	}

	public static String installId() {
		return install;
	}

	// ------------------------------------------------------------ hooks

	/** End of every client tick: time accounting, FPS samples, screens, and the report schedule. */
	public static void tick(Minecraft mc) {
		if (!ready) {
			return;
		}
		long now = System.nanoTime();
		// a frozen window or a breakpoint shouldn't count as an hour of play
		long dt = lastTick == 0 ? 0 : Math.min(1000, (now - lastTick) / 1_000_000);
		lastTick = now;
		// nothing is even counted while it's off
		if (!active()) {
			lastScreen = null;
			return;
		}

		msTotal += dt;
		if (mc.isWindowActive()) {
			msFocused += dt;
		}
		boolean inWorld = mc.player != null && mc.level != null;
		if (inWorld) {
			msIngame += dt;
			if (mc.isPaused()) {
				msPaused += dt;
			}
			if (mc.hasSingleplayerServer()) {
				msSingle += dt;
			} else {
				msMulti += dt;
				if (currentServer != null) {
					timed(SERVERS, currentServer).ms += dt;
				}
			}
			for (Module m : ModuleManager.all()) {
				// it's always on for anyone who reports, so its time says nothing
				if (m.isToggleable() && m.isEnabled() && !(m instanceof TelemetryModule)) {
					module(m).ms += dt;
				}
			}
			long wall = System.currentTimeMillis();
			if (wall >= nextFpsSample) {
				nextFpsSample = wall + 1000;
				long fps = mc.getFps();
				if (fps > 0) {
					fpsSum += fps;
					fpsSamples++;
					fpsMin = Math.min(fpsMin, fps);
					fpsMax = Math.max(fpsMax, fps);
				}
			}
			boolean dead = mc.player.isDeadOrDying();
			if (dead && !wasDead) {
				count("deaths");
			}
			wasDead = dead;
		} else {
			msMenu += dt;
			wasDead = false;
		}

		Screen screen = mc.gui.screen();
		Class<?> type = screen == null ? null : screen.getClass();
		if (type != lastScreen && type != null) {
			timed(SCREENS, screenName(type)).n++;
		}
		lastScreen = type;
		if (type != null) {
			timed(SCREENS, screenName(type)).ms += dt;
		}

		long wall = System.currentTimeMillis();
		if (!started) {
			boolean gpuUp = RenderSystem.tryGetDevice() != null && screen != null;
			if (gpuUp || wall - bootAt > START_MAX_WAIT_MS) {
				started = true;
				nextBeat = wall + BEAT_MS;
				send("start", false);
			}
		} else if (wall >= nextBeat) {
			nextBeat = wall + BEAT_MS;
			send("beat", false);
		}
	}

	public static void joined(Minecraft mc) {
		if (!active()) {
			return;
		}
		if (mc.hasSingleplayerServer()) {
			currentServer = null;
			count("world_joins");
		} else {
			currentServer = serverName(mc.getCurrentServer());
			timed(SERVERS, currentServer).n++;
			count("server_joins");
		}
		if (!told) {
			told = true;
			saveState();
			Notifications.post("Telemetry", "anonymous usage stats are on. Client > Telemetry turns them off", Icon.EYE, false);
		}
	}

	public static void left() {
		currentServer = null;
		wasDead = false;
		count("disconnects");
	}

	/** Runs {@code action} with toggles inside it credited to {@code source} ("key", "command"). */
	public static void via(String source, Runnable action) {
		String before = via;
		via = source;
		try {
			action.run();
		} finally {
			via = before;
		}
	}

	public static void moduleToggled(Module m, boolean enabled) {
		if (!active()) {
			return;
		}
		ModuleStats s = module(m);
		if (enabled) {
			s.on++;
		} else {
			s.off++;
		}
		if ("key".equals(via)) {
			s.key++;
		} else if ("command".equals(via)) {
			s.cmd++;
		}
	}

	public static void moduleError(Module m, Throwable e) {
		if (!active()) {
			return;
		}
		module(m).err++;
		add(ERRORS, m.getName() + ": " + e.getClass().getSimpleName(), 1);
	}

	public static void settingChanged(Setting<?> setting) {
		if (ConfigManager.isLoading() || !active()) {
			return;
		}
		String name = SETTING_NAMES.get(setting);
		if (name == null) {
			return;
		}
		// a slider drag or a colour picker is one change, not two hundred
		long now = System.currentTimeMillis();
		Long last = SETTING_LAST.put(setting, now);
		if (last == null || now - last > SETTING_DEBOUNCE_MS) {
			add(SETTINGS, name, 1);
		}
	}

	/** Client commands by name only; whatever came after it never leaves the game. */
	public static void command(String name, boolean known) {
		if (active()) {
			add(COMMANDS, known ? name : "(unknown)", 1);
		}
	}

	public static void count(String event) {
		if (active()) {
			add(COUNTS, event, 1);
		}
	}

	/** Last report on the way out; waits a moment so it actually leaves before the JVM does. */
	public static void shutdown() {
		if (active() && started) {
			send("end", true);
		}
	}

	// ------------------------------------------------------------ the report

	/** What would be sent right now, for the module's "Copy Report" button. */
	public static String preview() {
		return GSON.toJson(payload("preview"));
	}

	private static void send(String kind, boolean blocking) {
		if (inFlight && !blocking) {
			return;
		}
		String body;
		try {
			body = new Gson().toJson(payload(kind));
		} catch (RuntimeException e) {
			TrollClient.LOGGER.debug("Telemetry report failed to build", e);
			return;
		}
		HttpRequest request;
		try {
			request = HttpRequest.newBuilder(URI.create(endpoint()))
					.timeout(Duration.ofSeconds(blocking ? 3 : 10))
					.header("content-type", "application/json")
					.header("user-agent", "TrollClient/" + version(TrollClient.MOD_ID))
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();
		} catch (IllegalArgumentException e) {
			return;
		}
		inFlight = true;
		var future = HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding())
				.whenComplete((res, err) -> {
					inFlight = false;
					if (err != null) {
						TrollClient.LOGGER.debug("Telemetry report didn't go through: {}", err.toString());
					}
				});
		if (blocking) {
			try {
				future.get(3, java.util.concurrent.TimeUnit.SECONDS);
			} catch (Exception e) {
				// quitting anyway
			}
		}
	}

	private static JsonObject payload(String kind) {
		JsonObject root = new JsonObject();
		root.addProperty("v", PROTOCOL);
		root.addProperty("kind", kind);
		root.addProperty("install", install);
		root.addProperty("session", session);
		root.addProperty("seq", ++seq);
		root.addProperty("sentAt", System.currentTimeMillis());
		root.add("env", environment());

		JsonObject time = new JsonObject();
		time.addProperty("total", msTotal);
		time.addProperty("focused", msFocused);
		time.addProperty("ingame", msIngame);
		time.addProperty("singleplayer", msSingle);
		time.addProperty("multiplayer", msMulti);
		time.addProperty("menus", msMenu);
		time.addProperty("paused", msPaused);
		root.add("time", time);

		JsonObject fps = new JsonObject();
		fps.addProperty("sum", fpsSum);
		fps.addProperty("n", fpsSamples);
		fps.addProperty("min", fpsSamples == 0 ? 0 : fpsMin);
		fps.addProperty("max", fpsMax);
		root.add("fps", fps);

		JsonObject modules = new JsonObject();
		MODULES.forEach((name, s) -> {
			JsonObject o = new JsonObject();
			o.addProperty("on", s.on);
			o.addProperty("off", s.off);
			o.addProperty("ms", s.ms);
			o.addProperty("key", s.key);
			o.addProperty("cmd", s.cmd);
			o.addProperty("err", s.err);
			modules.add(name, o);
		});
		root.add("modules", modules);
		root.add("settings", counts(SETTINGS));
		root.add("commands", counts(COMMANDS));
		root.add("counts", counts(COUNTS));
		root.add("errors", counts(ERRORS));
		root.add("screens", timed(SCREENS));
		root.add("servers", timed(SERVERS));
		return root;
	}

	/** The machine and the client's setup. Sent with every report; the latest one wins. */
	private static JsonObject environment() {
		Minecraft mc = Minecraft.getInstance();
		JsonObject env = new JsonObject();
		env.addProperty("client", version(TrollClient.MOD_ID));
		env.addProperty("minecraft", version("minecraft"));
		env.addProperty("loader", version("fabricloader"));
		env.addProperty("fabricApi", version("fabric-api"));
		env.addProperty("dev", FabricLoader.getInstance().isDevelopmentEnvironment());

		env.addProperty("os", System.getProperty("os.name", "?"));
		env.addProperty("osVersion", System.getProperty("os.version", "?"));
		env.addProperty("arch", System.getProperty("os.arch", "?"));
		env.addProperty("java", System.getProperty("java.version", "?"));
		env.addProperty("javaVendor", System.getProperty("java.vendor", "?"));
		env.addProperty("cpus", Runtime.getRuntime().availableProcessors());
		env.addProperty("heapMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
		if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
			env.addProperty("ramMb", os.getTotalMemorySize() / (1024 * 1024));
		}
		env.addProperty("locale", Locale.getDefault().toLanguageTag());
		env.addProperty("timezone", ZoneId.systemDefault().getId());

		GpuDevice device = RenderSystem.tryGetDevice();
		if (device != null) {
			try {
				DeviceInfo info = device.getDeviceInfo();
				env.addProperty("gpu", info.name());
				env.addProperty("gpuVendor", info.vendorName());
				env.addProperty("gpuDriver", info.driverInfo());
				env.addProperty("gpuBackend", info.backendName());
				env.addProperty("gpuType", String.valueOf(info.type()));
			} catch (RuntimeException e) {
				// not every backend fills everything in
			}
		}
		Window window = mc.getWindow();
		if (window != null) {
			env.addProperty("window", window.getScreenWidth() + "x" + window.getScreenHeight());
			env.addProperty("fullscreen", window.isFullscreen());
			env.addProperty("refreshRate", window.getRefreshRate());
			env.addProperty("guiScale", window.getGuiScale());
		}
		if (mc.options != null) {
			env.addProperty("language", mc.options.languageCode);
			env.addProperty("renderDistance", mc.options.renderDistance().get());
			env.addProperty("simulationDistance", mc.options.simulationDistance().get());
			env.addProperty("fpsLimit", mc.options.framerateLimit().get());
			env.addProperty("vsync", mc.options.enableVsync().get());
			env.addProperty("graphics", mc.options.graphicsPreset().get().getSerializedName());
			env.addProperty("fov", mc.options.fov().get());
		}

		JsonArray mods = new JsonArray();
		for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
			String id = c.getMetadata().getId();
			// jar-in-jar libraries and Fabric API's own pieces aren't interesting
			if (c.getContainingMod().isPresent() || id.equals(TrollClient.MOD_ID) || id.equals("minecraft") || id.equals("java")
					|| id.equals("fabricloader") || id.equals("mixinextras") || id.startsWith("fabric-") || id.equals("fabric")) {
				continue;
			}
			if (mods.size() < 100) {
				mods.add(id);
			}
		}
		env.add("mods", mods);

		env.addProperty("launches", launches);
		env.addProperty("firstLaunch", firstLaunch);
		env.add("crashes", crashes);

		ThemeModule theme = ModuleManager.get(ThemeModule.class);
		env.addProperty("theme", theme.preset.get());
		env.addProperty("font", theme.font.get());
		env.addProperty("prefix", ModuleManager.get(ClickGuiModule.class).prefix.get());
		JsonArray enabled = new JsonArray();
		int bound = 0;
		int hidden = 0;
		int modified = 0;
		for (Module m : ModuleManager.all()) {
			if (m.isToggleable() && m.isEnabled() && !(m instanceof TelemetryModule)) {
				enabled.add(m.getName());
			}
			if (m.getKey() > 0) {
				bound++;
			}
			if (m.isHidden()) {
				hidden++;
			}
			for (Setting<?> s : m.getSettings()) {
				if (s.isModified()) {
					modified++;
				}
			}
		}
		env.add("enabled", enabled);
		env.addProperty("moduleCount", ModuleManager.all().size());
		env.addProperty("boundModules", bound);
		env.addProperty("hiddenModules", hidden);
		env.addProperty("modifiedSettings", modified);
		env.addProperty("macros", MacroManager.macros().size());
		env.addProperty("friends", Friends.all().size());
		return env;
	}

	// ------------------------------------------------------------ helpers

	private static ModuleStats module(Module m) {
		return MODULES.computeIfAbsent(m.getName(), k -> new ModuleStats());
	}

	private static Timed timed(Map<String, Timed> map, String key) {
		Timed t = map.get(key);
		if (t == null) {
			// past the cap, new names pool together instead of growing the report forever
			t = map.computeIfAbsent(map.size() < MAX_KEYS ? key : "(other)", k -> new Timed());
		}
		return t;
	}

	private static void add(Map<String, Long> map, String key, long n) {
		String k = map.containsKey(key) || map.size() < MAX_KEYS ? key : "(other)";
		map.merge(k, n, Long::sum);
	}

	private static JsonObject counts(Map<String, Long> map) {
		JsonObject o = new JsonObject();
		map.forEach(o::addProperty);
		return o;
	}

	private static JsonObject timed(Map<String, Timed> map) {
		JsonObject o = new JsonObject();
		map.forEach((k, t) -> {
			JsonObject e = new JsonObject();
			e.addProperty("n", t.n);
			e.addProperty("ms", t.ms);
			o.add(k, e);
		});
		return o;
	}

	private static String version(String modId) {
		return FabricLoader.getInstance().getModContainer(modId)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("");
	}

	private static String screenName(Class<?> type) {
		String name = type.getSimpleName();
		if (name.isEmpty()) {
			String full = type.getName();
			name = full.substring(full.lastIndexOf('.') + 1);
		}
		return name;
	}

	/** Public hostnames only. Bare IPs, LAN and Realms are reported as just that. */
	static String serverName(ServerData data) {
		if (data == null) {
			return "(unknown)";
		}
		if (data.isRealm()) {
			return "(realms)";
		}
		if (data.isLan()) {
			return "(lan)";
		}
		String host = data.ip == null ? "" : data.ip.trim().toLowerCase(Locale.ROOT);
		if (host.startsWith("[") || host.chars().filter(c -> c == ':').count() > 1) {
			return "(ip address)";
		}
		int colon = host.indexOf(':');
		if (colon >= 0) {
			host = host.substring(0, colon);
		}
		if (host.isEmpty()) {
			return "(unknown)";
		}
		if (host.matches("[0-9.]+")) {
			return "(ip address)";
		}
		if (host.equals("localhost") || !host.contains(".") || host.matches(".*\\.(local|lan|home|internal|localdomain)$")) {
			return "(lan)";
		}
		return host.length() > 64 ? host.substring(0, 64) : host;
	}

	/** Crash reports written since the last launch, and how many of them mention us. */
	private static JsonObject crashesSince(long since) {
		JsonObject out = new JsonObject();
		int total = 0;
		int ours = 0;
		Path dir = FabricLoader.getInstance().getGameDir().resolve("crash-reports");
		if (since > 0 && Files.isDirectory(dir)) {
			try (Stream<Path> files = Files.list(dir)) {
				for (Path p : (Iterable<Path>) files::iterator) {
					if (!p.getFileName().toString().endsWith(".txt") || Files.getLastModifiedTime(p).toMillis() <= since) {
						continue;
					}
					total++;
					if (Files.size(p) < 4_000_000 && Files.readString(p, StandardCharsets.UTF_8).contains("com.trollclient")) {
						ours++;
					}
				}
			} catch (IOException | RuntimeException e) {
				// no crash count is fine
			}
		}
		out.addProperty("total", total);
		out.addProperty("ours", ours);
		return out;
	}

	private static Path stateFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("trollclient-telemetry.json");
	}

	private static void loadState() {
		Path path = stateFile();
		try {
			if (Files.exists(path)) {
				JsonObject o = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
				install = o.has("id") ? o.get("id").getAsString() : null;
				launches = o.has("launches") ? o.get("launches").getAsLong() : 0;
				firstLaunch = o.has("firstLaunch") ? o.get("firstLaunch").getAsLong() : 0;
				previousLaunch = o.has("lastLaunch") ? o.get("lastLaunch").getAsLong() : 0;
				told = o.has("told") && o.get("told").getAsBoolean();
			}
		} catch (IOException | RuntimeException e) {
			TrollClient.LOGGER.warn("Telemetry state unreadable, starting a new one");
		}
		if (install == null || !install.matches("[0-9a-f-]{36}")) {
			install = UUID.randomUUID().toString();
		}
		if (firstLaunch <= 0) {
			firstLaunch = bootAt;
		}
		launches++;
		saveState();
	}

	private static void saveState() {
		JsonObject o = new JsonObject();
		o.addProperty("id", install);
		o.addProperty("launches", launches);
		o.addProperty("firstLaunch", firstLaunch);
		o.addProperty("lastLaunch", bootAt);
		o.addProperty("told", told);
		try {
			Files.createDirectories(stateFile().getParent());
			Files.writeString(stateFile(), GSON.toJson(o), StandardCharsets.UTF_8);
		} catch (IOException e) {
			TrollClient.LOGGER.warn("Couldn't save telemetry state", e);
		}
	}

	/** A fresh install id: past reports can no longer be tied to anything this client sends next. */
	public static void resetId() {
		install = UUID.randomUUID().toString();
		clear();
		saveState();
	}

	/**
	 * Forgets everything counted so far and starts a new session, so turning telemetry off and on
	 * again never sends what happened in between.
	 */
	public static void clear() {
		MODULES.clear();
		SETTINGS.clear();
		COMMANDS.clear();
		COUNTS.clear();
		ERRORS.clear();
		SCREENS.clear();
		SERVERS.clear();
		msTotal = msFocused = msIngame = msSingle = msMulti = msMenu = msPaused = 0;
		fpsSum = fpsSamples = fpsMax = 0;
		fpsMin = Long.MAX_VALUE;
		session = UUID.randomUUID().toString();
		seq = 0;
		started = false;
		bootAt = System.currentTimeMillis();
	}
}
