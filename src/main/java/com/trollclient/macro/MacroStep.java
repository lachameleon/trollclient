package com.trollclient.macro;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trollclient.TrollClient;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.Setting;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One step of a macro. Its options are ordinary settings, so the editor shows
 * them with the same rows as module settings and they save the same way.
 * Steps run on the client tick: {@link #tick} is called every tick until it
 * stops returning {@link Result#WAIT}.
 */
public abstract class MacroStep {
	protected static final Minecraft mc = Minecraft.getInstance();

	public enum Result {
		/** Done, go on to the next step (in the same tick). */
		NEXT,
		/** Not done, call again next tick. */
		WAIT,
		/** End the whole macro. */
		STOP
	}

	private final List<Setting<?>> settings = new ArrayList<>();
	private StepType type;
	private boolean enabled = true;

	protected <S extends Setting<?>> S add(S setting) {
		settings.add(setting);
		return setting;
	}

	/** Called when the runner reaches this step, before its first tick. Steps reset their per-run state here. */
	public void start(MacroRun run) {
	}

	public abstract Result tick(MacroRun run);

	/** Called if the macro stops while this step is still going (let go of keys and the like). */
	public void abort() {
	}

	/** What the step is set to, shown on its row: "500ms", "slot 12 · pickup"... */
	public abstract String summary();

	/** For wait steps: give up after {@code seconds} (0 = never), stopping the macro or carrying on. */
	protected static Result timedOut(MacroRun run, NumberSetting seconds, ModeSetting onTimeout) {
		if (seconds.get() <= 0 || run.stepMillis() < seconds.get() * 1000) {
			return Result.WAIT;
		}
		if (onTimeout.is("Stop")) {
			run.fail("timed out");
			return Result.STOP;
		}
		return Result.NEXT;
	}

	public StepType type() {
		return type;
	}

	void setType(StepType type) {
		this.type = type;
	}

	public List<Setting<?>> settings() {
		return Collections.unmodifiableList(settings);
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("type", type.id());
		o.addProperty("enabled", enabled);
		JsonObject values = new JsonObject();
		for (Setting<?> s : settings) {
			values.add(s.getName(), s.toJson());
		}
		o.add("settings", values);
		return o;
	}

	/** A step from its JSON, or null if the type is unknown (from a newer version, say). */
	public static MacroStep fromJson(JsonObject o) {
		StepType type = o.has("type") ? Steps.byId(o.get("type").getAsString()) : null;
		if (type == null) {
			TrollClient.LOGGER.warn("Skipping unknown macro step {}", o);
			return null;
		}
		MacroStep step = type.create();
		if (o.has("enabled")) {
			step.enabled = o.get("enabled").getAsBoolean();
		}
		if (o.has("settings")) {
			JsonObject values = o.getAsJsonObject("settings");
			for (Setting<?> s : step.settings) {
				JsonElement e = values.get(s.getName());
				if (e != null) {
					try {
						s.fromJson(e);
					} catch (RuntimeException ex) {
						TrollClient.LOGGER.warn("Bad value for macro step {}.{}", type.id(), s.getName());
					}
				}
			}
		}
		return step;
	}

	public MacroStep copy() {
		return fromJson(toJson());
	}
}
