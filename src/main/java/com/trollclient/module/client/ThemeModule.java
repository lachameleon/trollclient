package com.trollclient.module.client;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ColorSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;

public class ThemeModule extends Module {
	public final ModeSetting preset = add(new ModeSetting("Preset", "Base palette", "Noir",
			"Noir", "Paper", "Graphite", "Terminal", "Newsprint", "Ink", "Fog", "Frutiger Aero", "Vaporwave", "Amber", "Phosphor",
			"Classic", "Sakura", "Ocean", "Dracula", "Nord", "Solarized", "Crimson", "Aurora", "Handheld", "Custom"));
	public final ModeSetting font = add(new ModeSetting("Font", "Typeface for the client UI", "Terminal",
			"Minecraft", "Terminal", "Unicode"));
	public final ModeSetting accentMode = add(new ModeSetting("Accent Mode", "How the accent colour behaves",
			"Shimmer", "Static", "Pulse", "Shimmer", "Rainbow"));
	public final NumberSetting accentSpeed = add(new NumberSetting("Accent Speed", "Speed of the accent animation", 1.0, 0.1, 4.0, 0.1)
			.unit("x")).visibleWhen(() -> !accentMode.is("Static"));
	public final BoolSetting customAccent = add(new BoolSetting("Custom Accent", "Override the preset's accent colour", false));
	public final ColorSetting accent = add(new ColorSetting("Accent", "Accent colour", 0xFFFFFFFF))
			.visibleWhen(() -> customAccent.get() || preset.is("Custom"));
	public final ColorSetting backgroundColor = add(new ColorSetting("Background", "Window background (Custom preset)", 0xFF0C0C0C))
			.visibleWhen(() -> preset.is("Custom"));
	public final ColorSetting textColor = add(new ColorSetting("Text", "Text colour (Custom preset)", 0xFFF0F0F0))
			.visibleWhen(() -> preset.is("Custom"));
	public final ModeSetting corners = add(new ModeSetting("Corners", "Panel corner style", "Notched", "Sharp", "Notched", "Round"));
	public final ModeSetting gloss = add(new ModeSetting("Gloss", "Glassy shine on panels and buttons (Theme = only on glossy presets like Frutiger Aero)",
			"Theme", "Theme", "On", "Off"));
	public final NumberSetting opacity = add(new NumberSetting("Opacity", "Window opacity", 96, 40, 100, 1).unit("%"));
	public final BoolSetting textShadow = add(new BoolSetting("Text Shadow", "Drop shadow under text", false));
	public final BoolSetting ditherShadow = add(new BoolSetting("Dither Shadow", "Old-school checkerboard drop shadow", true));
	public final BoolSetting glow = add(new BoolSetting("Glow", "Soft halo around the window", false));
	public final BoolSetting scanlines = add(new BoolSetting("Scanlines", "CRT scanlines over the menu", true));
	public final NumberSetting scanlineStrength = add(new NumberSetting("Scanline Strength", "Darkness of the scanlines", 18, 2, 60, 1)
			.unit("%")).visibleWhen(scanlines::get);
	public final BoolSetting grain = add(new BoolSetting("Film Grain", "Animated noise over the menu", true));
	public final BoolSetting vignette = add(new BoolSetting("Vignette", "Darken the screen edges", true));
	public final BoolSetting flash = add(new BoolSetting("Invert Flash", "Negative flash when the menu opens", true));

	public ThemeModule() {
		super("Theme", "Colours, type and all the old-school screen effects.", Category.CLIENT);
	}

	@Override
	public boolean isToggleable() {
		return false;
	}
}
