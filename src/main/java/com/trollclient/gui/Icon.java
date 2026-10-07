package com.trollclient.gui;

/** Cells of {@code textures/gui/icons.png} (32px cells, 4 per row). Order matches tools/gen_assets.py. */
public enum Icon {
	COMBAT, MOVEMENT, TROLL, PLAYER,
	WORLD, CLIENT, SEARCH, GEAR,
	CLOSE, CHECK, KEYBOARD, PALETTE,
	CHEVRON, STAR, EYE, POWER,
	CHAT, SKULL, RADAR, CUBE;

	public static final int CELL = 32;
	public static final int SHEET_WIDTH = 128;
	public static final int SHEET_HEIGHT = 160;

	public int u() {
		return (ordinal() % 4) * CELL;
	}

	public int v() {
		return (ordinal() / 4) * CELL;
	}
}
