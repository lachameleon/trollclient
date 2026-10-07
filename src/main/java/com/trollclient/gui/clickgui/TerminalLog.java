package com.trollclient.gui.clickgui;

/**
 * The fake shell prompt in the ClickGUI footer. Anything can push a line;
 * the footer types the newest one out character by character.
 */
public final class TerminalLog {
	private static String current = "";
	private static long pushedAt;

	private TerminalLog() {
	}

	public static void push(String line) {
		current = line;
		pushedAt = System.currentTimeMillis();
	}

	public static String current() {
		return current;
	}

	/** How long the current line has been sitting there. */
	public static long idleMillis() {
		return System.currentTimeMillis() - pushedAt;
	}

	/** Characters of the current line that should be visible by now. */
	public static int typed(float charsPerSecond) {
		long elapsed = System.currentTimeMillis() - pushedAt;
		return (int) Math.min(current.length(), elapsed * charsPerSecond / 1000f);
	}
}
