package com.trollclient.macro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trollclient.macro.steps.FlowSteps;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.Setting;
import com.trollclient.setting.TextSetting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** A named list of steps with a key and a loop count. */
public final class Macro {
	public final TextSetting name = new TextSetting("Name", "What it's called (also what .macro run takes)", "new macro", 32)
			.placeholder("new macro");
	public final NumberSetting loops = new NumberSetting("Loops", "Times it runs from top to bottom (0 = until stopped)", 1, 0, 100, 1)
			.unit("x");
	public final List<MacroStep> steps = new ArrayList<>();
	private int key = GLFW.GLFW_KEY_UNKNOWN;

	public Macro(String name) {
		this.name.set(name);
	}

	public String getName() {
		return name.get().isBlank() ? "unnamed" : name.get();
	}

	public int getKey() {
		return key;
	}

	public void setKey(int key) {
		this.key = key;
		MacroManager.markDirty();
	}

	public List<Setting<?>> settings() {
		return List.of(name, loops);
	}

	/** Index of the first label step called {@code label}, or -1. */
	public int indexOfLabel(String label) {
		for (int i = 0; i < steps.size(); i++) {
			if (steps.get(i) instanceof FlowSteps.Label l && l.name().equalsIgnoreCase(label.trim())) {
				return i;
			}
		}
		return -1;
	}

	public List<String> labels() {
		List<String> out = new ArrayList<>();
		for (MacroStep s : steps) {
			if (s instanceof FlowSteps.Label l && !l.name().isBlank()) {
				out.add(l.name());
			}
		}
		return out;
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("name", name.get());
		o.addProperty("key", key);
		o.addProperty("loops", loops.getInt());
		JsonArray list = new JsonArray();
		for (MacroStep s : steps) {
			list.add(s.toJson());
		}
		o.add("steps", list);
		return o;
	}

	public static Macro fromJson(JsonObject o) {
		Macro m = new Macro(o.has("name") ? o.get("name").getAsString() : "macro");
		if (o.has("key")) {
			m.key = o.get("key").getAsInt();
		}
		if (o.has("loops")) {
			m.loops.set(o.get("loops").getAsDouble());
		}
		m.readSteps(o);
		return m;
	}

	/** Replaces the steps with the ones in {@code o} (undo uses this to roll back in place). */
	public void readSteps(JsonObject o) {
		steps.clear();
		if (o.has("steps")) {
			for (JsonElement e : o.getAsJsonArray("steps")) {
				MacroStep step = MacroStep.fromJson(e.getAsJsonObject());
				if (step != null) {
					steps.add(step);
				}
			}
		}
	}
}
