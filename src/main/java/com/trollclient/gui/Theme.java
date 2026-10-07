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
		light = ColorUtil.luminance(panel) > 0.5f;
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

	/**
	 * The animated backdrop that suits the preset, used wherever a backdrop
	 * setting is left on "Theme": stars for Noir, binary rain for Terminal,
	 * halftone dots for Newsprint, pen traces for Ink...
	 */
	public static String scene() {
		return switch (settings().preset.get()) {
			case "Graphite" -> "Grid";
			case "Terminal" -> "Rain";
			case "Paper" -> "Dust";
			case "Newsprint" -> "Halftone";
			case "Ink" -> "Waves";
			case "Fog" -> "Fog";
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
