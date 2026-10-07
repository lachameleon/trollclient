package com.trollclient.dev;

import com.trollclient.gui.Draw;
import com.trollclient.gui.Motion;
import com.trollclient.gui.Theme;
import com.trollclient.gui.title.PixelFont;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

import java.util.List;

/**
 * Showcase overlays, drawn in the client's own style: lower-third captions,
 * a retro arrow cursor (the real one isn't in the framebuffer), click ripples
 * and the closing title card.
 */
public final class Captions {
	private static final float IN = 0.35f;
	private static final float OUT = 0.3f;
	private static final String[] ARROW = {
			"X",
			"XX",
			"X.X",
			"X..X",
			"X...X",
			"X....X",
			"X.....X",
			"X......X",
			"X.......X",
			"X........X",
			"X.....XXXXX",
			"X..X..X",
			"X.X X..X",
			"XX  X..X",
			"X    X..X",
			"     X..X",
			"      XX",
	};

	private static String tag = "";
	private static String title;
	private static String subtitle = "";
	private static float shownAt;
	private static float hideAt = Float.MAX_VALUE;

	private static boolean cursorVisible;
	private static double fromX;
	private static double fromY;
	private static double toX;
	private static double toY;
	private static float moveStart;
	private static float moveSeconds;
	private static float clickAt = -10;
	private static double clickX;
	private static double clickY;

	private static float outroAt = -1;
	private static final List<PixelFont.Pixel> LOGO = PixelFont.layout("TROLL CLIENT");
	private static final int LOGO_W = PixelFont.width("TROLL CLIENT");

	private Captions() {
	}

	public static void show(String smallTag, String bigTitle, String sub, float seconds) {
		tag = smallTag;
		title = bigTitle;
		subtitle = sub;
		shownAt = Motion.time();
		hideAt = shownAt + seconds;
	}

	public static void hide() {
		hideAt = Math.min(hideAt, Motion.time());
	}

	// ------------------------------------------------------------------ cursor

	public static void cursor(boolean visible) {
		cursorVisible = visible;
	}

	/** Glides the cursor to a GUI point; returns the point so callers can click it later. */
	public static double[] glide(double x, double y, float seconds) {
		double[] now = cursorPos();
		fromX = now[0];
		fromY = now[1];
		toX = x;
		toY = y;
		moveStart = Motion.time();
		moveSeconds = Math.max(0.01f, seconds);
		return new double[]{x, y};
	}

	public static void warp(double x, double y) {
		fromX = toX = x;
		fromY = toY = y;
		moveSeconds = 0.01f;
	}

	public static double[] cursorPos() {
		float t = Motion.outCubic(Motion.clamp01((Motion.time() - moveStart) / moveSeconds));
		// a slight arc reads more like a hand than a straight line
		double arc = Math.sin(t * Math.PI) * Math.min(18, Math.hypot(toX - fromX, toY - fromY) * 0.08);
		return new double[]{fromX + (toX - fromX) * t, fromY + (toY - fromY) * t - arc};
	}

	public static void clicked() {
		double[] p = cursorPos();
		clickX = p[0];
		clickY = p[1];
		clickAt = Motion.time();
	}

	public static void outro() {
		outroAt = Motion.time();
	}

	// ------------------------------------------------------------------ drawing

	/** HUD hook: only draws when no screen is open (screens draw it on top of themselves). */
	public static void renderHud(GuiGraphicsExtractor g) {
		if (Minecraft.getInstance().gui.screen() == null) {
			render(g, g.guiWidth(), g.guiHeight());
		}
	}

	public static void render(GuiGraphicsExtractor g, int width, int height) {
		Theme.update();
		g.nextStratum();
		float prev = Draw.alpha;
		Draw.alpha = 1f;
		drawCaption(g, width, height);
		if (outroAt >= 0) {
			drawOutro(g, width, height);
		}
		if (cursorVisible && Minecraft.getInstance().gui.screen() != null) {
			drawCursor(g);
		}
		Draw.alpha = prev;
	}

	private static void drawCaption(GuiGraphicsExtractor g, int width, int height) {
		if (title == null) {
			return;
		}
		float now = Motion.time();
		float age = now - shownAt;
		float out = Motion.clamp01((now - hideAt) / OUT);
		if (out >= 1) {
			title = null;
			return;
		}
		float in = Motion.outCubic(Motion.clamp01(age / IN));
		float titleScale = 2f;
		float tw = Theme.width(title) * titleScale;
		float bw = Math.max(Math.max(tw, Theme.width(subtitle)), Theme.width(tag)) + 26;
		float bh = 50;
		float x = 14 - out * (bw + 30);
		float y = height - bh - 30;

		float prev = Draw.alpha;
		Draw.alpha = 1f - out * 0.6f;
		// the box wipes open from its left edge
		g.enableScissor(Math.round(x - 4), Math.round(y - 4), Math.round(x + (bw + 8) * in), Math.round(y + bh + 8));
		Draw.ditherShadow(g, x, y, bw, bh, 3, ColorUtil.withAlpha(0x000000, 170));
		Draw.panel(g, x, y, bw, bh, ColorUtil.withAlpha(Theme.panel, 236));
		Draw.panelOutline(g, x, y, bw, bh, Theme.borderHi);
		Draw.gradientH(g, x + 1, y + 1, bw - 2, 2, p -> Theme.accent(p / 1000f));
		Draw.rect(g, x + 1, y + 3, 3, bh - 4, Theme.accent());

		String shownTag = tag.substring(0, Math.min(tag.length(), (int) (age * 60)));
		Draw.text(g, shownTag, x + 12, y + 7, Theme.textDim);
		int typed = (int) Math.max(0, Math.min(title.length(), (age - 0.12f) * 32));
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(x + 12, y + 17);
		pose.scale(titleScale, titleScale);
		Draw.text(g, title.substring(0, typed), 0, 0, Theme.text);
		if (typed < title.length() || (int) (age * 2.4f) % 2 == 0) {
			Draw.rect(g, Theme.width(title.substring(0, typed)) + 1, -1, 4, 9, ColorUtil.fade(Theme.text, 0.8f));
		}
		pose.popMatrix();
		int subTyped = (int) Math.max(0, Math.min(subtitle.length(), (age - 0.12f - title.length() / 32f) * 70));
		Draw.text(g, subtitle.substring(0, subTyped), x + 12, y + 37, Theme.textDim);
		g.disableScissor();
		if (age > 0.04f && age < 0.11f) {
			Draw.invert(g, x, y, bw * in, bh);
		}
		Draw.alpha = prev;
	}

	private static void drawCursor(GuiGraphicsExtractor g) {
		double[] p = cursorPos();
		float x = (float) p[0];
		float y = (float) p[1];
		float since = Motion.time() - clickAt;
		if (since < 0.35f) {
			float k = Motion.outCubic(since / 0.35f);
			float r = 3 + k * 11;
			Draw.outline(g, (float) clickX - r, (float) clickY - r, r * 2, r * 2, ColorUtil.fade(0xFFFFFFFF, 1 - k));
			Draw.outline(g, (float) clickX - r - 1, (float) clickY - r - 1, r * 2 + 2, r * 2 + 2, ColorUtil.fade(0xFF000000, (1 - k) * 0.6f));
		}
		// pressed cursors sink a pixel, like the old ones did
		float press = since < 0.12f ? 1 : 0;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y + press);
		pose.scale(0.75f, 0.75f);
		for (int row = 0; row < ARROW.length; row++) {
			String line = ARROW[row];
			for (int col = 0; col < line.length(); col++) {
				char c = line.charAt(col);
				if (c == 'X') {
					Draw.rect(g, col, row, 1, 1, 0xFF000000);
				} else if (c == '.') {
					Draw.rect(g, col, row, 1, 1, 0xFFFFFFFF);
				}
			}
		}
		pose.popMatrix();
	}

	private static void drawOutro(GuiGraphicsExtractor g, int width, int height) {
		float age = Motion.time() - outroAt;
		float fade = Motion.clamp01(age / 0.6f);
		Draw.rect(g, 0, 0, width, height, ColorUtil.fade(0xFF000000 | Theme.bg, fade));
		if (age < 0.5f) {
			return;
		}
		float t = age - 0.5f;
		int ps = Math.max(2, Math.min(7, (int) (width * 0.7f / LOGO_W)));
		float lx = (width - LOGO_W * ps) / 2f;
		float ly = height * 0.3f;
		int depth = Math.max(2, ps / 2 + 1);
		for (int pass = 0; pass < 2; pass++) {
			for (PixelFont.Pixel p : LOGO) {
				float delay = 0.5f * p.x() / LOGO_W + Motion.hash(p.x() * 7 + p.y() * 13) * 0.1f;
				float k = Motion.range(t, delay, delay + 0.5f);
				if (k <= 0) {
					continue;
				}
				float drop = (1 - Motion.outBounce(k)) * (ly + 40);
				float px = lx + p.x() * ps;
				float py = ly + p.y() * ps - drop;
				if (pass == 0) {
					for (int d = depth; d >= 1; d--) {
						Draw.rect(g, px + d, py + d, ps, ps, ColorUtil.lerp(Theme.bg, Theme.textDim, 0.4f + 0.4f * (depth - d) / depth));
					}
				} else {
					Draw.rect(g, px, py, ps, ps, p.y() > 4 ? ColorUtil.lerp(Theme.text, Theme.bg, 0.12f) : Theme.text);
				}
			}
		}
		String[] lines = {"black & white & read all over", "27 modules  /  5 shaders  /  fabric  /  minecraft 26.2", "free download"};
		float y = ly + 7 * ps + 20;
		for (int i = 0; i < lines.length; i++) {
			float lt = t - 1.0f - i * 0.35f;
			if (lt <= 0) {
				break;
			}
			String s = lines[i].substring(0, Math.min(lines[i].length(), (int) (lt * 45)));
			int color = i == lines.length - 1 ? Theme.text : Theme.textDim;
			Draw.text(g, s, (width - Theme.width(lines[i])) / 2f, y, color);
			y += 13;
		}
	}
}
