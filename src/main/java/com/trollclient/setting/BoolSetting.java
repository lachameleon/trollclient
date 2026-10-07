package com.trollclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class BoolSetting extends Setting<Boolean> {
	public BoolSetting(String name, String description, boolean defaultValue) {
		super(name, description, defaultValue);
	}

	public void toggle() {
		set(!value);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement element) {
		if (element.isJsonPrimitive()) {
			set(element.getAsBoolean());
		}
	}

	@Override
	public String display() {
		return value ? "on" : "off";
	}

	@Override
	public boolean parse(String input) {
		switch (input.toLowerCase()) {
			case "true", "on", "yes", "1" -> set(true);
			case "false", "off", "no", "0" -> set(false);
			case "toggle" -> toggle();
			default -> {
				return false;
			}
		}
		return true;
	}
}
