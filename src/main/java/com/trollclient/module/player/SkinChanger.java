package com.trollclient.module.player;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;

import java.util.Locale;

/** Wear one of the bundled black & white skins (and the Troll Client cape). Only you can see it. */
public class SkinChanger extends Module {
	private final ModeSetting skin = add(new ModeSetting("Skin", "Which skin to wear (Off keeps your own)", "Troll",
			"Off", "Troll", "Mime", "Referee", "Ghost", "Hacker"));
	private final BoolSetting cape = add(new BoolSetting("Cape", "Wear the Troll Client cape", true));

	public SkinChanger() {
		super("SkinChanger", "Client-side skins: troll, mime, referee, ghost or hacker, plus the Troll Client cape.", Category.PLAYER);
	}

	/** Texture name under textures/skin, or null to keep your real skin. */
	public String skinName() {
		return skin.is("Off") ? null : skin.get().toLowerCase(Locale.ROOT);
	}

	public boolean wantsCape() {
		return cape.get();
	}

	@Override
	public String getInfo() {
		return skin.get();
	}
}
