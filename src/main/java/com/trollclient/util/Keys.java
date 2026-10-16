package com.trollclient.util;

import com.google.gson.JsonObject;

/** Key codes as the config files store them, and the move from GLFW key codes to SDL scancodes. */
public final class Keys {
	/** An unbound key. SDL's own "unknown" scancode is 0, so anything {@code <= 0} also reads as unbound. */
	public static final int NONE = -1;

	/**
	 * Written into each config file that holds key codes. Minecraft 26.3 moved from GLFW to SDL, and every key
	 * got a new number; a file without this marker was saved by a 26.2 build and still has GLFW codes in it.
	 */
	public static final String FORMAT_FIELD = "keyCodes";
	private static final String FORMAT = "sdl";

	/** GLFW key code, SDL scancode pairs: every key Minecraft 26.2 named, plus a few it didn't. */
	private static final int[] GLFW_TO_SDL = {
			32, 44, 39, 52, 44, 54, 45, 45, 46, 55, 47, 56, 48, 39, 49, 30, 50, 31, 51, 32, 52, 33, 53, 34, 54, 35,
			55, 36, 56, 37, 57, 38, 59, 51, 61, 46, 65, 4, 66, 5, 67, 6, 68, 7, 69, 8, 70, 9, 71, 10, 72, 11, 73, 12,
			74, 13, 75, 14, 76, 15, 77, 16, 78, 17, 79, 18, 80, 19, 81, 20, 82, 21, 83, 22, 84, 23, 85, 24, 86, 25,
			87, 26, 88, 27, 89, 28, 90, 29, 91, 47, 92, 49, 93, 48, 96, 53, 161, 100, 256, 41, 257, 40, 258, 43,
			259, 42, 260, 73, 261, 76, 262, 79, 263, 80, 264, 81, 265, 82, 266, 75, 267, 78, 268, 74, 269, 77,
			280, 57, 281, 71, 282, 83, 283, 70, 284, 72, 290, 58, 291, 59, 292, 60, 293, 61, 294, 62, 295, 63,
			296, 64, 297, 65, 298, 66, 299, 67, 300, 68, 301, 69, 302, 104, 303, 105, 304, 106, 305, 107, 306, 108,
			307, 109, 308, 110, 309, 111, 310, 112, 311, 113, 312, 114, 313, 115, 320, 98, 321, 89, 322, 90,
			323, 91, 324, 92, 325, 93, 326, 94, 327, 95, 328, 96, 329, 97, 330, 220, 331, 84, 332, 85, 333, 86,
			334, 87, 335, 88, 336, 103, 340, 225, 341, 224, 342, 226, 343, 227, 344, 229, 345, 228, 346, 230,
			347, 231, 348, 118,
	};

	private Keys() {
	}

	/** Marks a config file's root as holding SDL scancodes. */
	public static void markFormat(JsonObject root) {
		root.addProperty(FORMAT_FIELD, FORMAT);
	}

	/** True if the file was saved before 26.3, so its keys need {@link #fromGlfw}. */
	public static boolean isLegacy(JsonObject root) {
		return !root.has(FORMAT_FIELD) || !FORMAT.equals(root.get(FORMAT_FIELD).getAsString());
	}

	/** The SDL scancode for a GLFW key code; a key with no counterpart comes back unbound rather than wrong. */
	public static int fromGlfw(int glfw) {
		for (int i = 0; i < GLFW_TO_SDL.length; i += 2) {
			if (GLFW_TO_SDL[i] == glfw) {
				return GLFW_TO_SDL[i + 1];
			}
		}
		return NONE;
	}
}
