package com.trollclient.util;

/** ARGB helpers. Deliberately avoids java.awt, which misbehaves next to GLFW on macOS. */
public final class ColorUtil {
	private ColorUtil() {
	}

	public static int alpha(int c) {
		return (c >>> 24) & 0xFF;
	}

	public static int red(int c) {
		return (c >> 16) & 0xFF;
	}

	public static int green(int c) {
		return (c >> 8) & 0xFF;
	}

	public static int blue(int c) {
		return c & 0xFF;
	}

	public static int argb(int a, int r, int g, int b) {
		return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
	}

	private static int clamp(int v) {
		return Math.max(0, Math.min(255, v));
	}

	/** Replaces the alpha channel. */
	public static int withAlpha(int c, int a) {
		return (clamp(a) << 24) | (c & 0xFFFFFF);
	}

	/** Multiplies the existing alpha by {@code f} (0..1). */
	public static int fade(int c, float f) {
		return withAlpha(c, Math.round(alpha(c) * Math.max(0, Math.min(1, f))));
	}

	public static int lerp(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		return argb(
				Math.round(alpha(a) + (alpha(b) - alpha(a)) * t),
				Math.round(red(a) + (red(b) - red(a)) * t),
				Math.round(green(a) + (green(b) - green(a)) * t),
				Math.round(blue(a) + (blue(b) - blue(a)) * t));
	}

	public static int brightness(int c, float f) {
		return argb(alpha(c), Math.round(red(c) * f), Math.round(green(c) * f), Math.round(blue(c) * f));
	}

	public static float luminance(int c) {
		return (0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)) / 255f;
	}

	/** Black or white, whichever reads better on top of {@code c}. */
	public static int contrast(int c) {
		return luminance(c) > 0.55f ? 0xFF000000 : 0xFFFFFFFF;
	}

	public static int hsb(float h, float s, float b) {
		h = (h - (float) Math.floor(h)) * 6f;
		float f = h - (float) Math.floor(h);
		float p = b * (1 - s);
		float q = b * (1 - s * f);
		float t = b * (1 - s * (1 - f));
		float r, g, bl;
		switch ((int) h) {
			case 0 -> { r = b; g = t; bl = p; }
			case 1 -> { r = q; g = b; bl = p; }
			case 2 -> { r = p; g = b; bl = t; }
			case 3 -> { r = p; g = q; bl = b; }
			case 4 -> { r = t; g = p; bl = b; }
			default -> { r = b; g = p; bl = q; }
		}
		return argb(255, Math.round(r * 255), Math.round(g * 255), Math.round(bl * 255));
	}

	public static float[] toHsb(int c) {
		float r = red(c) / 255f, g = green(c) / 255f, b = blue(c) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float delta = max - min;
		float h = 0;
		if (delta > 0) {
			if (max == r) {
				h = ((g - b) / delta) % 6f;
			} else if (max == g) {
				h = (b - r) / delta + 2f;
			} else {
				h = (r - g) / delta + 4f;
			}
			h /= 6f;
			if (h < 0) {
				h += 1f;
			}
		}
		float s = max == 0 ? 0 : delta / max;
		return new float[]{h, s, max};
	}
}
