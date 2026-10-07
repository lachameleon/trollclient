package com.trollclient.gui;

import com.trollclient.util.ColorUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

/** All the low-level drawing the client does lives here. */
public final class Draw {
	public static final Identifier ICONS = id("textures/gui/icons.png");
	public static final Identifier GRAIN = id("textures/gui/grain.png");
	public static final Identifier SCANLINES = id("textures/gui/scanlines.png");
	public static final Identifier DITHER = id("textures/gui/dither.png");
	public static final Identifier VIGNETTE = id("textures/gui/vignette.png");
	private static final int OVERLAY_SIZE = 256;

	/** Multiplies the alpha of everything drawn; used to fade whole windows in and out. */
	public static float alpha = 1f;

	private static int fa(int color) {
		return alpha >= 1f ? color : ColorUtil.fade(color, alpha);
	}

	private Draw() {
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("trollclient", path);
	}

	public static Font font() {
		return Minecraft.getInstance().font;
	}

	// ------------------------------------------------------------------ shapes

	public static void rect(GuiGraphicsExtractor g, float x, float y, float w, float h, int color) {
		color = fa(color);
		if (w <= 0 || h <= 0 || ColorUtil.alpha(color) == 0) {
			return;
		}
		g.fill(Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h), color);
	}

	public static void outline(GuiGraphicsExtractor g, float x, float y, float w, float h, int color) {
		rect(g, x, y, w, 1, color);
		rect(g, x, y + h - 1, w, 1, color);
		rect(g, x, y + 1, 1, h - 2, color);
		rect(g, x + w - 1, y + 1, 1, h - 2, color);
	}

	/**
	 * "Marching ants" outline: 3px dashes that crawl around the box as
	 * {@code phase} increases.
	 */
	public static void dashedOutline(GuiGraphicsExtractor g, float x, float y, float w, float h, int color, float phase) {
		int iw = Math.round(w);
		int ih = Math.round(h);
		int offset = Math.floorMod((int) phase, 5);
		// walk the perimeter clockwise; each side draws its dashes as runs
		int along = 0;
		along = dashedSide(g, x, y, iw, 1, 0, along, offset, color);
		along = dashedSide(g, x + iw - 1, y + 1, ih - 1, 0, 1, along, offset, color);
		along = dashedSide(g, x + iw - 2, y + ih - 1, iw - 1, -1, 0, along, offset, color);
		dashedSide(g, x, y + ih - 2, ih - 2, 0, -1, along, offset, color);
	}

	private static int dashedSide(GuiGraphicsExtractor g, float sx, float sy, int length, int dx, int dy, int along, int offset, int color) {
		int runStart = -1;
		for (int t = 0; t <= length; t++) {
			boolean on = t < length && Math.floorMod(along + t - offset, 5) < 3;
			if (on && runStart < 0) {
				runStart = t;
			} else if (!on && runStart >= 0) {
				int a = runStart;
				int b = t - 1;
				float x0 = sx + Math.min(a * dx, b * dx);
				float y0 = sy + Math.min(a * dy, b * dy);
				int len = b - a + 1;
				rect(g, x0, y0, dx != 0 ? len : 1, dy != 0 ? len : 1, color);
				runStart = -1;
			}
		}
		return along + length;
	}

	public static void gradientV(GuiGraphicsExtractor g, float x, float y, float w, float h, int top, int bottom) {
		g.fillGradient(Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h), fa(top), fa(bottom));
	}

	/** Horizontal gradient sampled from {@code colorAt(0..1)} in vertical strips. */
	public static void gradientH(GuiGraphicsExtractor g, float x, float y, float w, float h, java.util.function.IntUnaryOperator colorAt) {
		int segments = Math.max(1, Math.min(96, Math.round(w / 3)));
		float step = w / segments;
		for (int i = 0; i < segments; i++) {
			int c = colorAt.applyAsInt(Math.round((i + 0.5f) / segments * 1000));
			float x0 = x + i * step;
			rect(g, x0, y, (x + (i + 1) * step) - x0 + 0.5f, h, c);
		}
	}

	public static void gradientH(GuiGraphicsExtractor g, float x, float y, float w, float h, int left, int right) {
		gradientH(g, x, y, w, h, p -> ColorUtil.lerp(left, right, p / 1000f));
	}

	/**
	 * Panel honouring the theme's corner style: sharp, notched (1px cut, very
	 * 1998) or pixel-rounded.
	 */
	public static void panel(GuiGraphicsExtractor g, float x, float y, float w, float h, int fill) {
		String style = Theme.settings().corners.get();
		switch (style) {
			case "Notched" -> {
				rect(g, x + 1, y, w - 2, 1, fill);
				rect(g, x, y + 1, w, h - 2, fill);
				rect(g, x + 1, y + h - 1, w - 2, 1, fill);
			}
			case "Round" -> {
				rect(g, x + 2, y, w - 4, 1, fill);
				rect(g, x + 1, y + 1, w - 2, 1, fill);
				rect(g, x, y + 2, w, h - 4, fill);
				rect(g, x + 1, y + h - 2, w - 2, 1, fill);
				rect(g, x + 2, y + h - 1, w - 4, 1, fill);
			}
			default -> rect(g, x, y, w, h, fill);
		}
	}

	/** Border that matches {@link #panel}'s corners. */
	public static void panelOutline(GuiGraphicsExtractor g, float x, float y, float w, float h, int color) {
		String style = Theme.settings().corners.get();
		switch (style) {
			case "Notched" -> {
				rect(g, x + 1, y, w - 2, 1, color);
				rect(g, x + 1, y + h - 1, w - 2, 1, color);
				rect(g, x, y + 1, 1, h - 2, color);
				rect(g, x + w - 1, y + 1, 1, h - 2, color);
			}
			case "Round" -> {
				rect(g, x + 2, y, w - 4, 1, color);
				rect(g, x + 2, y + h - 1, w - 4, 1, color);
				rect(g, x, y + 2, 1, h - 4, color);
				rect(g, x + w - 1, y + 2, 1, h - 4, color);
				rect(g, x + 1, y + 1, 1, 1, color);
				rect(g, x + w - 2, y + 1, 1, 1, color);
				rect(g, x + 1, y + h - 2, 1, 1, color);
				rect(g, x + w - 2, y + h - 2, 1, 1, color);
			}
			default -> outline(g, x, y, w, h, color);
		}
	}

	/** Soft halo made of fading outlines. */
	public static void glow(GuiGraphicsExtractor g, float x, float y, float w, float h, int color, int size) {
		for (int i = 1; i <= size; i++) {
			float f = 1f - (float) i / (size + 1);
			outline(g, x - i, y - i, w + i * 2, h + i * 2, ColorUtil.fade(color, f * f * 0.35f));
		}
	}

	/** Inverts whatever is already drawn underneath. */
	public static void invert(GuiGraphicsExtractor g, float x, float y, float w, float h) {
		if (w <= 0 || h <= 0) {
			return;
		}
		g.fill(RenderPipelines.GUI_INVERT, Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h), 0xFFFFFFFF);
	}

	// ------------------------------------------------------------------ textures

	public static void icon(GuiGraphicsExtractor g, Icon icon, float x, float y, float size, int color) {
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(size / 16f, size / 16f);
		g.blit(RenderPipelines.GUI_TEXTURED, ICONS, 0, 0, icon.u(), icon.v(), 16, 16, Icon.CELL, Icon.CELL,
				Icon.SHEET_WIDTH, Icon.SHEET_HEIGHT, fa(color));
		pose.popMatrix();
	}

	/** Tiles a 256px overlay texture over an area, starting at texture offset (u, v). */
	public static void tile(GuiGraphicsExtractor g, Identifier texture, int x, int y, int w, int h, int u, int v, int color) {
		color = fa(color);
		if (ColorUtil.alpha(color) == 0) {
			return;
		}
		int startU = Math.floorMod(u, OVERLAY_SIZE);
		int startV = Math.floorMod(v, OVERLAY_SIZE);
		for (int ty = 0; ty < h; ) {
			int vv = ty == 0 ? startV : 0;
			int th = Math.min(OVERLAY_SIZE - vv, h - ty);
			for (int tx = 0; tx < w; ) {
				int uu = tx == 0 ? startU : 0;
				int tw = Math.min(OVERLAY_SIZE - uu, w - tx);
				g.blit(RenderPipelines.GUI_TEXTURED, texture, x + tx, y + ty, uu, vv, tw, th, OVERLAY_SIZE, OVERLAY_SIZE, color);
				tx += tw;
			}
			ty += th;
		}
	}

	public static void vignette(GuiGraphicsExtractor g, int w, int h, float strength) {
		g.blit(RenderPipelines.GUI_TEXTURED, VIGNETTE, 0, 0, 0, 0, w, h, OVERLAY_SIZE, OVERLAY_SIZE,
				OVERLAY_SIZE, OVERLAY_SIZE, ColorUtil.argb(Math.round(255 * strength), 255, 255, 255));
	}

	/** Old-school checkerboard drop shadow, offset down-right. */
	public static void ditherShadow(GuiGraphicsExtractor g, float x, float y, float w, float h, int offset, int color) {
		int ix = Math.round(x), iy = Math.round(y), iw = Math.round(w), ih = Math.round(h);
		// right strip and bottom strip; start on an even pixel so the pattern lines up
		tile(g, DITHER, ix + iw, iy + offset, offset, ih, ix + iw, iy + offset, color);
		tile(g, DITHER, ix + offset, iy + ih, iw, offset, ix + offset, iy + ih, color);
	}

	// ------------------------------------------------------------------ text

	public static void text(GuiGraphicsExtractor g, String s, float x, float y, int color) {
		text(g, s, x, y, color, Theme.shadow());
	}

	public static void text(GuiGraphicsExtractor g, String s, float x, float y, int color, boolean shadow) {
		color = fa(color);
		if (ColorUtil.alpha(color) < 5 || s.isEmpty()) {
			return;
		}
		Component c = Theme.styled(s);
		int ix = Math.round(x);
		int iy = Math.round(y);
		g.text(font(), c, ix, iy, color, shadow);
	}

	public static void textCentered(GuiGraphicsExtractor g, String s, float cx, float y, int color) {
		text(g, s, cx - Theme.width(s) / 2f, y, color);
	}

	public static void textRight(GuiGraphicsExtractor g, String s, float right, float y, int color) {
		text(g, s, right - Theme.width(s), y, color);
	}

	public static void textScaled(GuiGraphicsExtractor g, String s, float x, float y, float scale, int color) {
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		text(g, s, 0, 0, color);
		pose.popMatrix();
	}

	/** Trims with an ellipsis so text never spills out of its box. */
	public static String ellipsize(String s, int maxWidth) {
		if (Theme.width(s) <= maxWidth) {
			return s;
		}
		String dots = "..";
		int target = maxWidth - Theme.width(dots);
		StringBuilder sb = new StringBuilder();
		for (char c : s.toCharArray()) {
			if (Theme.width(sb.toString() + c) > target) {
				break;
			}
			sb.append(c);
		}
		return sb + dots;
	}

	/** Simple greedy word wrap using the themed font. */
	public static java.util.List<String> wrap(String s, int maxWidth) {
		java.util.List<String> lines = new java.util.ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : s.split(" ")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (Theme.width(candidate) > maxWidth && !line.isEmpty()) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(candidate);
			}
		}
		if (!line.isEmpty()) {
			lines.add(line.toString());
		}
		return lines;
	}
}
