package com.trollclient.macro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.trollclient.TrollClient;
import com.trollclient.gui.clickgui.TerminalLog;
import com.trollclient.macro.steps.FlowSteps;
import com.trollclient.telemetry.Telemetry;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Every macro, the ones running right now, and their own config file. */
public final class MacroManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long SAVE_DELAY_MS = 1000;
	private static final int CHAT_HISTORY = 50;

	private static final List<Macro> MACROS = new ArrayList<>();
	private static final List<MacroRun> RUNS = new ArrayList<>();
	/** Recent chat lines for Wait Chat steps; {@link #chatCount} only ever grows, so steps can ask "anything new?". */
	private static final List<String> CHAT = new ArrayList<>();
	private static int chatCount;
	private static long dirtySince = -1;
	/** The macro open in the editor, for Goto's label suggestions. */
	private static Macro editing;

	private MacroManager() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("trollclient-macros.json");
	}

	// ------------------------------------------------------------------ library

	public static List<Macro> macros() {
		return Collections.unmodifiableList(MACROS);
	}

	public static Macro byName(String name) {
		for (Macro m : MACROS) {
			if (m.getName().equalsIgnoreCase(name.trim())) {
				return m;
			}
		}
		return null;
	}

	public static List<String> names() {
		List<String> out = new ArrayList<>();
		for (Macro m : MACROS) {
			out.add(m.getName());
		}
		return out;
	}

	/** A new, empty macro with a name nobody has yet. */
	public static Macro create() {
		String name = "macro";
		for (int i = 2; byName(name) != null; i++) {
			name = "macro " + i;
		}
		Macro m = new Macro(name);
		MACROS.add(m);
		markDirty();
		return m;
	}

	public static Macro duplicate(Macro source) {
		Macro copy = Macro.fromJson(source.toJson());
		String name = source.getName() + " copy";
		for (int i = 2; byName(name) != null; i++) {
			name = source.getName() + " copy " + i;
		}
		copy.name.set(name);
		copy.setKey(-1);
		MACROS.add(MACROS.indexOf(source) + 1, copy);
		markDirty();
		return copy;
	}

	public static void remove(Macro m) {
		stop(m);
		MACROS.remove(m);
		markDirty();
	}

	public static void setEditing(Macro m) {
		editing = m;
	}

	public static List<String> editingLabels() {
		return editing == null ? List.of() : editing.labels();
	}

	// ------------------------------------------------------------------ running

	public static List<MacroRun> runs() {
		return Collections.unmodifiableList(RUNS);
	}

	public static MacroRun runOf(Macro m) {
		for (MacroRun r : RUNS) {
			if (r.macro() == m && !r.isFinished()) {
				return r;
			}
		}
		return null;
	}

	public static boolean isRunning(Macro m) {
		return runOf(m) != null;
	}

	/** Starts {@code m} from the top (restarting it if it was already going). */
	public static MacroRun start(Macro m) {
		stop(m);
		MacroRun run = new MacroRun(m);
		RUNS.add(run);
		Telemetry.count("macro_runs");
		TerminalLog.push("macro --run \"" + m.getName().toLowerCase(Locale.ROOT) + "\"");
		return run;
	}

	public static void stop(Macro m) {
		for (MacroRun r : RUNS) {
			if (r.macro() == m) {
				r.stop();
			}
		}
		RUNS.removeIf(MacroRun::isFinished);
	}

	/** Start it if it's idle, stop it if it's running: what a macro's key does. */
	public static void toggle(Macro m) {
		if (isRunning(m)) {
			stop(m);
		} else {
			start(m);
		}
	}

	public static int stopAll() {
		int n = 0;
		for (MacroRun r : RUNS) {
			if (!r.isFinished()) {
				r.stop();
				n++;
			}
		}
		RUNS.clear();
		return n;
	}

	/** Start of every client tick in a world, after the modules (so a macro's movement wins). */
	public static void tick() {
		// a step can start another macro, so walk a copy
		for (MacroRun r : new ArrayList<>(RUNS)) {
			r.tick();
		}
		RUNS.removeIf(MacroRun::isFinished);
	}

	/** Runs every macro bound to {@code key}. Returns true if anything matched. */
	public static boolean keyPressed(int key) {
		boolean any = false;
		for (Macro m : MACROS) {
			if (key > 0 && m.getKey() == key) {
				toggle(m);
				any = true;
			}
		}
		return any;
	}

	public static void worldLeft() {
		stopAll();
		CHAT.clear();
	}

	// ------------------------------------------------------------------ chat for Wait Chat

	public static void onChat(String line) {
		CHAT.add(line);
		chatCount++;
		while (CHAT.size() > CHAT_HISTORY) {
			CHAT.remove(0);
		}
	}

	public static int chatCount() {
		return chatCount;
	}

	/** True if a line received after {@code since} (a {@link #chatCount()} value) contains {@code text}. */
	public static boolean chatSince(int since, String text) {
		String needle = text.toLowerCase(Locale.ROOT);
		int newLines = Math.min(CHAT.size(), chatCount - since);
		for (int i = CHAT.size() - newLines; i < CHAT.size(); i++) {
			if (CHAT.get(i).toLowerCase(Locale.ROOT).contains(needle)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ storage

	public static void markDirty() {
		if (dirtySince < 0) {
			dirtySince = System.currentTimeMillis();
		}
	}

	/** Every client tick, in a world or not: writes the file once edits settle. */
	public static void saveTick() {
		if (dirtySince >= 0 && System.currentTimeMillis() - dirtySince > SAVE_DELAY_MS) {
			save();
		}
	}

	public static void save() {
		dirtySince = -1;
		JsonArray list = new JsonArray();
		for (Macro m : MACROS) {
			list.add(m.toJson());
		}
		JsonObject root = new JsonObject();
		root.add("macros", list);
		Path path = file();
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			TrollClient.LOGGER.error("Failed to save macros", e);
		}
	}

	public static void load() {
		MACROS.clear();
		Path path = file();
		if (!Files.exists(path)) {
			MACROS.add(example());
			return;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.has("macros")) {
				for (JsonElement e : root.getAsJsonArray("macros")) {
					MACROS.add(Macro.fromJson(e.getAsJsonObject()));
				}
			}
		} catch (IOException | RuntimeException e) {
			TrollClient.LOGGER.error("Failed to load macros", e);
		}
		dirtySince = -1;
	}

	/** First run: one harmless macro so the editor isn't empty. Hops five times. */
	private static Macro example() {
		Macro m = new Macro("bunny");
		MacroStep repeat = Steps.byId("repeat").create();
		((FlowSteps.Repeat) repeat).set(2, 5);
		m.steps.add(repeat);
		m.steps.add(Steps.byId("jump").create());
		MacroStep wait = Steps.byId("delay").create();
		((FlowSteps.Delay) wait).setTicks(15);
		m.steps.add(wait);
		MacroStep tell = Steps.byId("notify").create();
		((FlowSteps.Notify) tell).set("boing x5");
		m.steps.add(tell);
		markDirty();
		return m;
	}
}
