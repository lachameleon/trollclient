package com.trollclient.util;

import com.trollclient.module.ModuleManager;
import com.trollclient.module.player.SkinChanger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side skins: your own (via SkinChanger) and fixed ones for specific
 * players (used by the showcase recorder for its stand-in players). Only you
 * see any of this; the server never hears about it.
 */
public final class SkinOverrides {
	private static final Map<UUID, PlayerSkin> FIXED = new ConcurrentHashMap<>();

	private SkinOverrides() {
	}

	public static ClientAsset.ResourceTexture skin(String name) {
		return new ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath("trollclient", "skin/" + name));
	}

	public static ClientAsset.ResourceTexture cape(String name) {
		return new ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath("trollclient", "cape/" + name));
	}

	/** Gives one player a bundled skin (and optionally the Troll Client cape). */
	public static void set(UUID player, String skin, boolean withCape) {
		FIXED.put(player, new PlayerSkin(skin(skin), withCape ? cape("troll") : null, null, PlayerModelType.WIDE, false));
	}

	public static void clear(UUID player) {
		FIXED.remove(player);
	}

	/** Mixin hook: the skin a player should render with. */
	public static PlayerSkin apply(AbstractClientPlayer player, PlayerSkin original) {
		PlayerSkin fixed = FIXED.get(player.getUUID());
		if (fixed != null) {
			return fixed;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !player.getUUID().equals(mc.player.getUUID())) {
			return original;
		}
		SkinChanger changer = ModuleManager.get(SkinChanger.class);
		if (changer == null || !changer.isEnabled()) {
			return original;
		}
		String skin = changer.skinName();
		boolean custom = skin != null;
		return new PlayerSkin(custom ? skin(skin) : original.body(),
				changer.wantsCape() ? cape("troll") : original.cape(),
				original.elytra(),
				custom ? PlayerModelType.WIDE : original.model(),
				original.secure());
	}
}
