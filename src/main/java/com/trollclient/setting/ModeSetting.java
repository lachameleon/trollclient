package com.trollclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.List;

public class ModeSetting extends Setting<String> {
	private final List<String> modes;

	public ModeSetting(String name, String description, String defaultValue, String... modes) {
		super(name, description, defaultValue);
		this.modes = List.of(modes);
	}

	public List<String> getModes() {
		return modes;
	}

	public boolean is(String mode) {
		return value.equalsIgnoreCase(mode);
	}

	public int index() {
		return Math.max(0, modes.indexOf(value));
	}

	public void cycle(int direction) {
		int next = Math.floorMod(index() + direction, modes.size());
		set(modes.get(next));
	}

	@Override
	protected String sanitize(String v) {
		for (String mode : modes) {
			if (mode.equalsIgnoreCase(v)) {
				return mode;
			}
		}
		return value;
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement element) {
		if (element.isJsonPrimitive()) {
			set(element.getAsString());
		}
	}

	@Override
	public String display() {
		return value;
	}

	@Override
	public boolean parse(String input) {
		String before = value;
		set(input);
		return !before.equals(value) || before.equalsIgnoreCase(input);
	}
}
