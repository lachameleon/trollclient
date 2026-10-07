package com.trollclient.module;

import com.trollclient.gui.Icon;

public enum Category {
	COMBAT("Combat", Icon.COMBAT, "keep the crystals and arrows off you"),
	MOVEMENT("Movement", Icon.MOVEMENT, "go places, or get away from them"),
	TROLL("Troll", Icon.TROLL, "the reason you installed this"),
	CHAT("Chat", Icon.CHAT, "say things. lots of things"),
	PLAYER("Player", Icon.PLAYER, "grabby hands"),
	CLIENT("Client", Icon.CLIENT, "make it yours");

	private final String displayName;
	private final Icon icon;
	private final String tagline;

	Category(String displayName, Icon icon, String tagline) {
		this.displayName = displayName;
		this.icon = icon;
		this.tagline = tagline;
	}

	public String getDisplayName() {
		return displayName;
	}

	public Icon getIcon() {
		return icon;
	}

	public String getTagline() {
		return tagline;
	}
}
