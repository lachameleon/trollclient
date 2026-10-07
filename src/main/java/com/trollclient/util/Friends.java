package com.trollclient.util;

import com.trollclient.config.ConfigManager;
import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Names that every targeting module leaves alone. */
public final class Friends {
	private static final Set<String> NAMES = new TreeSet<>();

	private Friends() {
	}

	public static boolean add(String name) {
		boolean added = NAMES.add(name.toLowerCase(Locale.ROOT));
		if (added) {
			ConfigManager.markDirty();
		}
		return added;
	}

	public static boolean remove(String name) {
		boolean removed = NAMES.remove(name.toLowerCase(Locale.ROOT));
		if (removed) {
			ConfigManager.markDirty();
		}
		return removed;
	}

	public static boolean isFriend(String name) {
		return NAMES.contains(name.toLowerCase(Locale.ROOT));
	}

	public static boolean isFriend(Player player) {
		return isFriend(player.getGameProfile().name());
	}

	public static Set<String> all() {
		return Collections.unmodifiableSet(NAMES);
	}
}
