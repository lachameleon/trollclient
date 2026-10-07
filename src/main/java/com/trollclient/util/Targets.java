package com.trollclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Player selection shared by every module that picks on somebody. */
public final class Targets {
	private Targets() {
	}

	public static List<Player> playersWithin(double range, boolean ignoreFriends) {
		Minecraft mc = Minecraft.getInstance();
		List<Player> list = new ArrayList<>();
		if (mc.level == null || mc.player == null) {
			return list;
		}
		for (Player p : mc.level.players()) {
			if (p == mc.player || !p.isAlive() || p.isSpectator()) {
				continue;
			}
			if (ignoreFriends && Friends.isFriend(p)) {
				continue;
			}
			if (p.distanceTo(mc.player) <= range) {
				list.add(p);
			}
		}
		list.sort(Comparator.comparingDouble(p -> p.distanceToSqr(mc.player)));
		return list;
	}

	public static Player byName(String name) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || name == null || name.isBlank()) {
			return null;
		}
		for (Player p : mc.level.players()) {
			if (p != mc.player && p.getGameProfile().name().equalsIgnoreCase(name.trim())) {
				return p;
			}
		}
		return null;
	}

	/** Names from the tab list, used for autocompletion in the GUI and commands. */
	public static List<String> onlineNames() {
		Minecraft mc = Minecraft.getInstance();
		List<String> names = new ArrayList<>();
		if (mc.getConnection() == null) {
			return names;
		}
		for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
			String n = info.getProfile().name();
			if (mc.player == null || !n.equals(mc.player.getGameProfile().name())) {
				names.add(n);
			}
		}
		names.sort(String.CASE_INSENSITIVE_ORDER);
		return names;
	}

	/** A named player, or the nearest one inside {@code range} when the name is blank. */
	public static Player resolve(String name, double range, boolean ignoreFriends) {
		if (name != null && !name.isBlank()) {
			Player p = byName(name);
			Minecraft mc = Minecraft.getInstance();
			return p != null && p.isAlive() && mc.player != null && p.distanceTo(mc.player) <= range ? p : null;
		}
		List<Player> near = playersWithin(range, ignoreFriends);
		return near.isEmpty() ? null : near.get(0);
	}

	public static String name(Player p) {
		return p.getGameProfile().name();
	}
}
