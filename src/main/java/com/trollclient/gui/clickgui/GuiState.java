package com.trollclient.gui.clickgui;

import com.google.gson.JsonObject;
import com.trollclient.module.Category;

/** ClickGUI state that survives reopening the menu and restarts. */
public final class GuiState {
	/** Window centre as a fraction of the screen, so it survives resizes. */
	public static float centerX = 0.5f;
	public static float centerY = 0.5f;
	public static Category category = Category.COMBAT;
	public static String selectedModule = "";
	/** GUI tools panel: top-left corner in GUI pixels, folded to its header, and which sections are folded. */
	public static float toolsX = 4;
	public static float toolsY = 4;
	public static boolean toolsCollapsed;
	public static int toolsFolded;

	private GuiState() {
	}

	public static JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("centerX", centerX);
		o.addProperty("centerY", centerY);
		o.addProperty("category", category.name());
		o.addProperty("selectedModule", selectedModule);
		o.addProperty("toolsX", toolsX);
		o.addProperty("toolsY", toolsY);
		o.addProperty("toolsCollapsed", toolsCollapsed);
		o.addProperty("toolsFolded", toolsFolded);
		return o;
	}

	public static void fromJson(JsonObject o) {
		if (o.has("centerX")) {
			centerX = clamp(o.get("centerX").getAsFloat());
		}
		if (o.has("centerY")) {
			centerY = clamp(o.get("centerY").getAsFloat());
		}
		if (o.has("category")) {
			try {
				category = Category.valueOf(o.get("category").getAsString());
			} catch (IllegalArgumentException ignored) {
				category = Category.COMBAT;
			}
		}
		if (o.has("selectedModule")) {
			selectedModule = o.get("selectedModule").getAsString();
		}
		if (o.has("toolsX")) {
			toolsX = o.get("toolsX").getAsFloat();
		}
		if (o.has("toolsY")) {
			toolsY = o.get("toolsY").getAsFloat();
		}
		if (o.has("toolsCollapsed")) {
			toolsCollapsed = o.get("toolsCollapsed").getAsBoolean();
		}
		if (o.has("toolsFolded")) {
			toolsFolded = o.get("toolsFolded").getAsInt();
		}
	}

	private static float clamp(float v) {
		return Math.max(0f, Math.min(1f, v));
	}
}
