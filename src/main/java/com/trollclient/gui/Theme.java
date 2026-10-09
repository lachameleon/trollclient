package com.trollclient.gui;

import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.ThemeModule;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/**
 * The current palette, recomputed once per frame from {@link ThemeModule}.
 * Everything that draws reads these fields instead of hard-coding colours.
 */
public final class Theme {
	private static final FontDescription TERMINAL = new FontDescription.Resource(Identifier.fromNamespaceAndPath("trollclient", "terminal"));
	private static final FontDescription UNICODE = new FontDescription.Resource(Identifier.withDefaultNamespace("uniform"));

	/** Screen background behind panels. */
	public static int bg;
	/** Main panel fill. */
	public static int panel;
	/** Raised elements: rows, fields, tracks. */
	public static int panel2;
	public static int border;
	public static int borderHi;
	public static int text;
	public static int textDim;
	/** Base accent; animated variants come from {@link #accent(float)}. */
	public static int accentBase;
	public static boolean light;
	public static float opacity = 1f;
	/** Full-screen background where there's no world behind: top and bottom of a vertical gradient. */
	public static int screenTop;
	public static int screenBottom;
	/** Glassy highlight across the top half of every panel (Frutiger Aero). */
	public static boolean gloss;

	private static FontDescription font = FontDescription.DEFAULT;
	private static boolean shadow;

	private Theme() {
	}

	public static ThemeModule settings() {
		return ModuleManager.get(ThemeModule.class);
	}

	public static void update() {
		ThemeModule t = settings();
		int[] p = switch (t.preset.get()) {
			case "Paper" -> new int[]{0xFFE9E9E9, 0xFFF7F7F7, 0xFFE6E6E6, 0xFFC4C4C4, 0xFF9C9C9C, 0xFF101010, 0xFF7C7C7C, 0xFF000000};
			case "Graphite" -> new int[]{0xFF19191B, 0xFF212123, 0xFF2A2A2D, 0xFF39393C, 0xFF56565B, 0xFFD8D8D8, 0xFF86868B, 0xFFC4C4C4};
			case "Terminal" -> new int[]{0xFF000000, 0xFF000000, 0xFF0B0B0B, 0xFF4A4A4A, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF8C8C8C, 0xFFFFFFFF};
			case "Newsprint" -> new int[]{0xFFE4DFD3, 0xFFF1EDE4, 0xFFE2DCCF, 0xFFA8A296, 0xFF7C766B, 0xFF1A1A1A, 0xFF6C685F, 0xFF2A2A2A};
			// pen on paper: pure white, hard black lines
			case "Ink" -> new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0xFFF1F1F1, 0xFF1C1C1C, 0xFF000000, 0xFF000000, 0xFF5E5E5E, 0xFF000000};
			// everything mid-grey, like a foggy CRT turned way down
			case "Fog" -> new int[]{0xFF7E7E7E, 0xFF8B8B8B, 0xFF979797, 0xFF6E6E6E, 0xFF5A5A5A, 0xFF101010, 0xFF3C3C3C, 0xFF0A0A0A};
			// glossy sky-blue glass, aqua accent: Vista-era optimism. Entries 8 and 9 are the screen gradient
			case "Frutiger Aero" -> new int[]{0xFFE2F3FD, 0xFFF6FCFF, 0xFFD0EBFA, 0xFF9CCFEC, 0xFF3E9FD8, 0xFF0A3150, 0xFF5785A3, 0xFF0C9BDC,
					0xFF5DBDF0, 0xFFE9F9FF};
			case "Vaporwave" -> new int[]{0xFF1B0B33, 0xFF240F42, 0xFF2F1553, 0xFF4E2380, 0xFFB24BD8, 0xFFFBE8FF, 0xFFB79AD6, 0xFFFF71CE,
					0xFF12062A, 0xFF4A1257};
			// monochrome monitors: amber and green phosphor
			case "Amber" -> new int[]{0xFF0A0600, 0xFF100A00, 0xFF1A1100, 0xFF4A3000, 0xFFB87A00, 0xFFFFB000, 0xFF9C6B00, 0xFFFFC94A};
			case "Phosphor" -> new int[]{0xFF000A03, 0xFF001105, 0xFF001A09, 0xFF0B4A1C, 0xFF1FBF4D, 0xFF41FF6E, 0xFF1E9440, 0xFF8CFFA8};
			// grey window, white fields, navy title bar, teal desktop
			case "Classic" -> new int[]{0xFFC0C0C0, 0xFFD4D0C8, 0xFFFFFFFF, 0xFF808080, 0xFF404040, 0xFF000000, 0xFF2E2E2E, 0xFF000080,
					0xFF2A9D9D, 0xFF2A9D9D};
			case "Sakura" -> new int[]{0xFFFFF0F5, 0xFFFFF8FB, 0xFFFDE3EC, 0xFFF2B8CC, 0xFFDB7A9D, 0xFF4B1A2C, 0xFFA06A80, 0xFFE84A85,
					0xFFFFE1ED, 0xFFFFF8FB};
			case "Ocean" -> new int[]{0xFF031A2E, 0xFF05233D, 0xFF082E4F, 0xFF0F4770, 0xFF2D86C2, 0xFFD9F2FF, 0xFF6E9DC0, 0xFF2EE6D0,
					0xFF0A3A5E, 0xFF010A14};
			case "Dracula" -> new int[]{0xFF21222C, 0xFF282A36, 0xFF343746, 0xFF44475A, 0xFF6272A4, 0xFFF8F8F2, 0xFF8B8FAF, 0xFFBD93F9};
			case "Nord" -> new int[]{0xFF2E3440, 0xFF3B4252, 0xFF434C5E, 0xFF4C566A, 0xFF7B88A1, 0xFFECEFF4, 0xFF9AA4B6, 0xFF88C0D0};
			case "Solarized" -> new int[]{0xFFEEE8D5, 0xFFFDF6E3, 0xFFF4EEDB, 0xFFD6CDB4, 0xFF93A1A1, 0xFF073642, 0xFF657B83, 0xFF268BD2};
			case "Crimson" -> new int[]{0xFF0D0203, 0xFF150405, 0xFF200709, 0xFF4A0E12, 0xFF9C1C24, 0xFFF6DADA, 0xFFA06A6C, 0xFFFF2E3C,
					0xFF1A0406, 0xFF050001};
			case "Aurora" -> new int[]{0xFF060A1A, 0xFF0B1126, 0xFF111934, 0xFF1D2A50, 0xFF3E5AA8, 0xFFE4EAFF, 0xFF7F8CB8, 0xFF5CFFB1,
					0xFF02040D, 0xFF0E1A3A};
			// the four greens of an old handheld's LCD
			case "Handheld" -> new int[]{0xFF8BAC0F, 0xFF9BBC0F, 0xFF8BAC0F, 0xFF306230, 0xFF0F380F, 0xFF0F380F, 0xFF306230, 0xFF0F380F};
			case "Custom" -> custom(t);
			default -> new int[]{0xFF090909, 0xFF101010, 0xFF181818, 0xFF262626, 0xFF3C3C3C, 0xFFEDEDED, 0xFF7A7A7A, 0xFFFFFFFF};
		};
		bg = p[0];
		panel = p[1];
		panel2 = p[2];
		border = p[3];
		borderHi = p[4];
		text = p[5];
		textDim = p[6];
		accentBase = t.customAccent.get() || t.preset.is("Custom") ? t.accent.get() | 0xFF000000 : p[7];
		screenTop = p.length > 8 ? p[8] : bg;
		screenBottom = p.length > 9 ? p[9] : screenTop;
		light = ColorUtil.luminance(panel) > 0.5f;
		gloss = switch (t.gloss.get()) {
			case "On" -> true;
			case "Off" -> false;
			default -> t.preset.is("Frutiger Aero");
		};
		opacity = t.opacity.getFloat() / 100f;
		font = switch (t.font.get()) {
			case "Terminal" -> TERMINAL;
			case "Unicode" -> UNICODE;
			default -> FontDescription.DEFAULT;
		};
		shadow = t.textShadow.get();
	}

	private static int[] custom(ThemeModule t) {
		int base = t.backgroundColor.get() | 0xFF000000;
		int txt = t.textColor.get() | 0xFF000000;
		boolean isLight = ColorUtil.luminance(base) > 0.5f;
		float step = isLight ? 0.94f : 1.0f;
		int panel = isLight ? ColorUtil.brightness(base, 1.04f) : ColorUtil.lerp(base, 0xFFFFFFFF, 0.03f);
		int panel2 = isLight ? ColorUtil.brightness(base, step * 0.97f) : ColorUtil.lerp(base, 0xFFFFFFFF, 0.07f);
		int border = ColorUtil.lerp(base, txt, 0.18f);
		int borderHi = ColorUtil.lerp(base, txt, 0.32f);
		int dim = ColorUtil.lerp(base, txt, 0.5f);
		return new int[]{base, panel, panel2, border, borderHi, txt, dim, t.accent.get() | 0xFF000000};
	}

	/**
	 * Accent colour at a horizontal position (0..1) — shimmer and rainbow modes
	 * vary across the element, the others ignore {@code pos}.
	 */
	public static int accent(float pos) {
		ThemeModule t = settings();
		float time = Motion.time() * t.accentSpeed.getFloat();
		return switch (t.accentMode.get()) {
			case "Pulse" -> ColorUtil.lerp(accentBase, textDim, (float) (Math.sin(time * 3) * 0.5 + 0.5) * 0.55f);
			case "Shimmer" -> {
				float wave = (float) Math.pow(Math.max(0, Math.cos((pos - time * 0.45f) * Math.PI * 2)), 6);
				yield ColorUtil.lerp(ColorUtil.lerp(accentBase, panel, 0.45f), accentBase, wave);
			}
			case "Rainbow" -> ColorUtil.hsb(pos * 0.6f + time * 0.15f, 0.55f, light ? 0.75f : 1f);
			default -> accentBase;
		};
	}

	public static int accent() {
		return accent(0.5f);
	}

	/** Screen background colour {@code t} of the way down (0 = top, 1 = bottom). */
	public static int screenAt(float t) {
		return ColorUtil.lerp(screenTop, screenBottom, t);
	}

	/**
	 * The animated backdrop that suits the preset, used wherever a backdrop
	 * setting is left on "Theme": stars for Noir, binary rain for Terminal,
	 * halftone dots for Newsprint, pen traces for Ink, bubbles for Aero...
	 */
	public static String scene() {
		return switch (settings().preset.get()) {
			case "Graphite" -> "Grid";
			case "Terminal", "Amber", "Phosphor" -> "Rain";
			case "Paper", "Solarized" -> "Dust";
			case "Newsprint", "Handheld" -> "Halftone";
			case "Ink" -> "Waves";
			case "Fog" -> "Fog";
			case "Frutiger Aero", "Ocean" -> "Bubbles";
			case "Vaporwave" -> "Sunset";
			case "Sakura" -> "Petals";
			case "Nord" -> "Snow";
			case "Crimson" -> "Embers";
			case "Aurora" -> "Aurora";
			// a plain desktop, the way it came out of the box
			case "Classic" -> "None";
			default -> "Stars";
		};
	}

	/** {@code mode}, or the preset's own scene when it's "Theme". */
	public static String scene(String mode) {
		return mode.equals("Theme") ? scene() : mode;
	}

	/** Colour that reads on top of the accent. */
	public static int onAccent() {
		return ColorUtil.contrast(accentBase);
	}

	/** Applies the window opacity to a fill colour. */
	public static int a(int color) {
		return ColorUtil.fade(color, opacity);
	}

	public static MutableComponent styled(String s) {
		MutableComponent c = Component.literal(s);
		if (font != FontDescription.DEFAULT) {
			c = c.withStyle(style -> style.withFont(font));
		}
		return c;
	}

	public static int width(String s) {
		return Minecraft.getInstance().font.width(styled(s));
	}

	public static int lineHeight() {
		return 9;
	}

	public static boolean shadow() {
		return shadow;
	}

	public static FontDescription font() {
		return font;
	}
}
