package com.trollclient.module.client;

import com.trollclient.gui.Theme;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;

public class TitleScreenModule extends Module {
	public final BoolSetting replaceModded = add(new BoolSetting("Replace Modded Menus",
			"Also replace main menus that other mods put in place of the vanilla one", true));
	public final BoolSetting customMenus = add(new BoolSetting("Custom Menus",
			"Troll Client versions of the singleplayer and multiplayer screens", true));
	public final ModeSetting background = add(new ModeSetting("Background",
			"Behind the menu. Theme picks one to suit the preset (stars for Noir, rain for Terminal...)",
			"Theme", "Theme", "Stars", "Rain", "Grid", "Dust", "Waves", "Halftone", "Fog", "Bubbles", "Snow", "Embers",
			"Petals", "Aurora", "Sunset", "None"));
	public final NumberSetting stars = add(new NumberSetting("Stars", "How many stars", 320, 50, 900, 10))
			.visibleWhen(this::starfield);
	public final BoolSetting warp = add(new BoolSetting("Warp On Hover", "Stars speed up while you hover a button", true))
			.visibleWhen(this::starfield);
	public final BoolSetting parallax = add(new BoolSetting("Parallax", "Background follows the mouse", true));
	public final BoolSetting scroller = add(new BoolSetting("Sine Scroller", "Demoscene-style scrolling message", true));
	public final TextSetting scrollerText = add(new TextSetting("Scroller Text", "Message for the scroller",
			"greetings to everyone getting walled in by nowayhome ... crystalcancel says no ... "
					+ "twerk responsibly ... press right shift in game for the menu ... ", 256)).visibleWhen(scroller::get);
	public final BoolSetting glitch = add(new BoolSetting("Logo Glitch", "Occasional glitches on the logo", true));
	public final BoolSetting splash = add(new BoolSetting("Splash Text", "Show the yellow-ish splash, in monochrome", true));
	public final BoolSetting themeButton = add(new BoolSetting("Theme Button", "A [ theme ] button in the corner that cycles presets (or press T)", true));

	public TitleScreenModule() {
		super("TitleScreen", "Replaces the main menu with the Troll Client title screen.", Category.CLIENT);
		restoreEnabled(true);
	}

	/** The background actually showing, with "Theme" resolved to the preset's scene. */
	public String scene() {
		return Theme.scene(background.get());
	}

	/** The title screen's own warp starfield, rather than one of the shared backdrops. */
	public boolean starfield() {
		return scene().equals("Stars");
	}
}
