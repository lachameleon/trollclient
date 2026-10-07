package com.trollclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.trollclient.util.ColorUtil;

/** ARGB colour stored as an int, edited in HSB space by the GUI. */
public class ColorSetting extends Setting<Integer> {
	public ColorSetting(String name, String description, int defaultArgb) {
		super(name, description, defaultArgb);
	}

	public float[] hsb() {
		return ColorUtil.toHsb(value);
	}

	public void setHsb(float h, float s, float b) {
		int rgb = ColorUtil.hsb(h, s, b) & 0xFFFFFF;
		set((value & 0xFF000000) | rgb);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(String.format("#%08X", value));
	}

	@Override
	public void fromJson(JsonElement element) {
		if (element.isJsonPrimitive()) {
			parse(element.getAsString());
		}
	}

	@Override
	public String display() {
		return String.format("#%06X", value & 0xFFFFFF);
	}

	@Override
	public boolean parse(String input) {
		String hex = input.startsWith("#") ? input.substring(1) : input;
		try {
			long parsed = Long.parseLong(hex, 16);
			set(hex.length() <= 6 ? (int) (0xFF000000L | parsed) : (int) parsed);
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}
}
