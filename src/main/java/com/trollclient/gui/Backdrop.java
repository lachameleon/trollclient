package com.trollclient.gui;

import com.trollclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

import java.util.Random;
import java.util.function.BiConsumer;

/**
 * Animated full-screen effects drawn behind windows: binary rain, a retro grid,
 * dust, stars, scope waves, newspaper halftone or drifting fog. "Theme" picks
 * the one that suits the current preset ({@link Theme#scene()}).
 */
public final class Backdrop {
	private static final int COLUMNS = 42;
	private static final int TRAIL = 11;

	private final float[] rainY = new float[COLUMNS];
	private final float[] rainSpeed = new float[COLUMNS];
	private final int[] rainSeed = new int[COLUMNS];
	private final float[] dustX = new float[90];
	private final float[] dustY = new float[90];
	private final float[] dustV = new float[90];
	private final float[] starX = new float[170];
	private final float[] starY = new float[170];
	private final float[] starZ = new float[170];
	private final Random random = new Random();
	private float gridOffset;

	public Backdrop() {
		for (int i = 0; i < COLUMNS; i++) {
			resetColumn(i, true);
		}
		for (int i = 0; i < dustX.length; i++) {
			dustX[i] = random.nextFloat();
			dustY[i] = random.nextFloat();
			dustV[i] = 0.01f + random.nextFloat() * 0.03f;
		}
		for (int i = 0; i < starX.length; i++) {
			respawnStar(i, random.nextFloat());
		}
	}

	private void respawnStar(int i, float z) {
		starX[i] = random.nextFloat() * 2 - 1;
		starY[i] = random.nextFloat() * 2 - 1;
		starZ[i] = Math.max(0.05f, z);
	}

	private void resetColumn(int i, boolean anywhere) {
		rainY[i] = anywhere ? random.nextFloat() * 1.4f - 0.4f : -0.3f - random.nextFloat() * 0.5f;
		rainSpeed[i] = 0.12f + random.nextFloat() * 0.3f;
		rainSeed[i] = random.nextInt();
	}

	public void render(GuiGraphicsExtractor g, String mode, int width, int height, float dt, float alpha) {
		if (alpha <= 0.01f) {
			return;
		}
		switch (Theme.scene(mode)) {
			case "Rain" -> rain(g, width, height, dt, alpha);
			case "Grid" -> grid(g, width, height, dt, alpha);
			case "Dust" -> dust(g, width, height, dt, alpha);
			case "Stars" -> stars(g, width, height, dt, alpha);
			case "Waves" -> waves(g, width, height, alpha);
			case "Halftone" -> scaled(g, width, height, (w, h) -> halftone(g, w, h, alpha));
			case "Fog" -> scaled(g, width, height, (w, h) -> fog(g, w, h, alpha));
			default -> {
			}
		}
	}

	/**
	 * Draws at most 480 virtual pixels wide, scaled up to fill the screen, so the
	 * pixel-heavy effects cost the same (and keep their look) at any GUI scale.
	 */
	private static void scaled(GuiGraphicsExtractor g, int width, int height, BiConsumer<Integer, Integer> draw) {
		float k = Math.max(1f, width / 480f);
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.scale(k, k);
		draw.accept((int) Math.ceil(width / k), (int) Math.ceil(height / k));
		pose.popMatrix();
	}

	/** Pixel circles of 0..5px diameter, as row widths. */
	private static final int[][] DOTS = {{}, {1}, {2, 2}, {1, 3, 1}, {2, 4, 4, 2}, {3, 5, 5, 5, 3}};

	/** Newspaper halftone: a staggered dot screen whose dot sizes follow a slow, drifting tone field. */
	private void halftone(GuiGraphicsExtractor g, int width, int height, float alpha) {
		float time = Motion.time() * 0.35f;
		int spacing = 11;
		int color = ColorUtil.fade(Theme.text, alpha * (Theme.light ? 0.16f : 0.2f));
		for (int row = 0, y = spacing / 2; y < height + spacing; row++, y += spacing) {
			int shift = row % 2 == 0 ? 0 : spacing / 2;
			for (int x = shift; x < width + spacing; x += spacing) {
				// two crossing waves make soft blobs of tone that wander across the page
				float tone = (float) (Math.sin(x * 0.011f + time) + Math.cos(y * 0.017f - time * 0.8f)
						+ Math.sin((x + y) * 0.006f + time * 0.5f)) / 3f;
				int size = Math.round((tone * 0.5f + 0.5f) * (DOTS.length - 1));
				int[] rows = DOTS[Math.max(0, Math.min(DOTS.length - 1, size))];
				for (int r = 0; r < rows.length; r++) {
					Draw.rect(g, x - rows[r] / 2, y - rows.length / 2 + r, rows[r], 1, color);
				}
			}
		}
	}

	/**
	 * Slow banks of dithered fog. Each bank is a row of narrow strips whose
	 * thickness ripples along it; the ripples roll sideways, so the banks drift.
	 */
	private void fog(GuiGraphicsExtractor g, int width, int height, float alpha) {
		float time = Motion.time();
		int banks = 5;
		int strip = 6;
		int color = ColorUtil.withAlpha(Theme.text, Math.round(255 * alpha * (Theme.light ? 0.11f : 0.09f)));
		for (int b = 0; b < banks; b++) {
			float centre = height * (b + 0.5f) / banks + (float) Math.sin(time * 0.17f + b * 1.9f) * height * 0.05f;
			float thick = height * (0.07f + 0.05f * Motion.hash(b * 31 + 7));
			float speed = (0.25f + 0.2f * Motion.hash(b * 11 + 2)) * (b % 2 == 0 ? 1 : -1);
			for (int x = 0; x < width; x += strip) {
				float ripple = (float) (Math.sin(x * 0.018f + time * speed + b * 2.3f) * 0.6f
						+ Math.sin(x * 0.043f - time * speed * 1.7f + b) * 0.4f);
				// two nested layers: a faint outer one, and a denser core where the ripple swells
				for (int layer = 0; layer < 2; layer++) {
					float h = thick * (layer == 0 ? 0.75f + 0.35f * ripple : 0.35f + 0.3f * ripple);
					if (h < 1) {
						continue;
					}
					int top = Math.round(centre - h / 2);
					// texture offset = screen position keeps the checkerboard locked to the pixel grid
					Draw.tile(g, Draw.DITHER, x, top, Math.min(strip, width - x), Math.round(h), x, top, color);
				}
			}
		}
	}

	private void rain(GuiGraphicsExtractor g, int width, int height, float dt, float alpha) {
		float colW = width / (float) COLUMNS;
		int step = 10;
		for (int i = 0; i < COLUMNS; i++) {
			rainY[i] += rainSpeed[i] * dt * Motion.speed();
			if (rainY[i] - TRAIL * step / (float) height > 1.05f) {
				resetColumn(i, false);
			}
			float headY = rainY[i] * height;
			int headCell = (int) (headY / step);
			for (int t = 0; t < TRAIL; t++) {
				float y = (headCell - t) * step;
				if (y < -step || y > height) {
					continue;
				}
				// flicker digits deterministically per cell, re-rolled a few times a second
				int flick = (int) (Motion.time() * 6) + t * 7;
				boolean one = Motion.hash(rainSeed[i] + (headCell - t) * 31 + (t == 0 ? flick : 0)) > 0.5f;
				float fade = 1f - (float) t / TRAIL;
				int base = t == 0 ? Theme.text : Theme.textDim;
				int color = ColorUtil.fade(base, alpha * fade * fade * (t == 0 ? 0.55f : 0.32f));
				Draw.text(g, one ? "1" : "0", i * colW + colW / 2f - 2, y, color, false);
			}
		}
	}

	private void grid(GuiGraphicsExtractor g, int width, int height, float dt, float alpha) {
		gridOffset = (gridOffset + dt * 0.35f * Motion.speed()) % 1f;
		float horizon = height * 0.55f;
		int lineColor = Theme.textDim;
		// horizontal lines rushing towards the viewer
		int rows = 14;
		float[] ys = new float[rows + 1];
		for (int r = 0; r <= rows; r++) {
			float t = (r + gridOffset) / rows;
			ys[r] = horizon + (height - horizon) * t * t;
			float a = alpha * 0.08f + alpha * 0.25f * t;
			Draw.rect(g, 0, ys[r], width, 1, ColorUtil.fade(lineColor, a));
		}
		// converging verticals as stair-stepped segments between rows
		int verticals = 22;
		for (int v = 0; v <= verticals; v++) {
			float bottomX = (v / (float) verticals - 0.5f) * width * 2.2f + width / 2f;
			float topX = width / 2f + (bottomX - width / 2f) * 0.08f;
			int segments = 40;
			for (int s = 0; s < segments; s++) {
				float t0 = s / (float) segments;
				float y0 = horizon + (height - horizon) * t0;
				float x0 = topX + (bottomX - topX) * t0;
				float a = alpha * (0.06f + 0.22f * t0);
				Draw.rect(g, x0, y0, 1, (height - horizon) / segments + 1, ColorUtil.fade(lineColor, a));
			}
		}
		Draw.gradientV(g, 0, horizon - 40, width, 40, 0, ColorUtil.fade(Theme.text, alpha * 0.06f));
	}

	/** A gentle version of the title screen's warp starfield. */
	private void stars(GuiGraphicsExtractor g, int width, int height, float dt, float alpha) {
		float cx = width / 2f;
		float cy = height / 2f;
		float fov = Math.max(width, height) * 0.45f;
		for (int i = 0; i < starX.length; i++) {
			float oldZ = starZ[i];
			starZ[i] -= 0.08f * dt * Motion.speed();
			if (starZ[i] <= 0.05f) {
				respawnStar(i, 1f);
				continue;
			}
			float z = starZ[i];
			float sx = cx + starX[i] / z * fov;
			float sy = cy + starY[i] / z * fov;
			if (sx < 0 || sy < 0 || sx > width || sy > height) {
				respawnStar(i, 1f);
				continue;
			}
			float depth = 1f - z;
			int color = ColorUtil.fade(Theme.text, alpha * (0.1f + depth * 0.6f));
			float tx = cx + starX[i] / Math.min(1f, oldZ + 0.02f) * fov;
			float ty = cy + starY[i] / Math.min(1f, oldZ + 0.02f) * fov;
			Draw.rect(g, (sx + tx) / 2f, (sy + ty) / 2f, 1, 1, ColorUtil.fade(color, 0.5f));
			Draw.rect(g, sx, sy, depth > 0.8f ? 2 : 1, depth > 0.8f ? 2 : 1, color);
		}
	}

	/** Oscilloscope traces drifting across the screen, each a sum of two sines. */
	private void waves(GuiGraphicsExtractor g, int width, int height, float alpha) {
		float time = Motion.time();
		int traces = 5;
		int step = 5;
		for (int w = 0; w < traces; w++) {
			float baseY = height * (0.2f + 0.6f * w / (traces - 1f));
			float amp = 10 + 14 * Motion.hash(w * 13 + 1);
			float freq = 0.012f + 0.01f * Motion.hash(w * 7 + 3);
			float speed = 0.8f + Motion.hash(w * 5 + 9) * 1.4f;
			float prevY = Float.NaN;
			for (int x = 0; x <= width; x += step) {
				float y = baseY + (float) (Math.sin(x * freq + time * speed + w) * amp
						+ Math.sin(x * freq * 2.7f - time * speed * 0.6f) * amp * 0.35f);
				// brightest in the middle of the screen, fading to the edges
				float edge = 1f - Math.abs(x / (float) width - 0.5f) * 1.6f;
				int color = ColorUtil.fade(w % 2 == 0 ? Theme.text : Theme.textDim, alpha * Math.max(0.05f, edge) * 0.35f);
				if (!Float.isNaN(prevY)) {
					float top = Math.min(prevY, y);
					Draw.rect(g, x - step, y, step, 1, color);
					Draw.rect(g, x - step, top, 1, Math.abs(y - prevY) + 1, color);
				}
				prevY = y;
			}
		}
	}

	private void dust(GuiGraphicsExtractor g, int width, int height, float dt, float alpha) {
		float time = Motion.time();
		for (int i = 0; i < dustX.length; i++) {
			dustY[i] -= dustV[i] * dt * Motion.speed();
			dustX[i] += (float) Math.sin(time * 0.6f + i) * 0.0004f;
			if (dustY[i] < -0.02f) {
				dustY[i] = 1.02f;
				dustX[i] = random.nextFloat();
			}
			float twinkle = 0.5f + 0.5f * (float) Math.sin(time * 2.3f + i * 1.7f);
			float size = i % 7 == 0 ? 2 : 1;
			Draw.rect(g, dustX[i] * width, dustY[i] * height, size, size, ColorUtil.fade(Theme.text, alpha * (0.15f + 0.45f * twinkle)));
		}
	}
}
