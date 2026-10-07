package com.trollclient.gui;

import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.ClickGuiModule;

/** Easing curves plus the global animation-speed multiplier. */
public final class Motion {
	private Motion() {
	}

	public static float speed() {
		ClickGuiModule gui = ModuleManager.get(ClickGuiModule.class);
		return gui == null ? 1f : gui.speed();
	}

	/** Seconds since the client started, scaled by the animation speed. */
	public static float time() {
		return (System.nanoTime() / 1_000_000_000f) % 100_000f;
	}

	public static float clamp01(float t) {
		return t < 0 ? 0 : (t > 1 ? 1 : t);
	}

	public static float outCubic(float t) {
		t = clamp01(t);
		float f = 1 - t;
		return 1 - f * f * f;
	}

	public static float inCubic(float t) {
		t = clamp01(t);
		return t * t * t;
	}

	public static float inOutCubic(float t) {
		t = clamp01(t);
		return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
	}

	public static float outBack(float t) {
		t = clamp01(t);
		float c1 = 1.70158f;
		float c3 = c1 + 1;
		return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
	}

	public static float outElastic(float t) {
		t = clamp01(t);
		if (t == 0 || t == 1) {
			return t;
		}
		float c4 = (float) (2 * Math.PI) / 3;
		return (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * c4) + 1);
	}

	public static float outBounce(float t) {
		t = clamp01(t);
		float n1 = 7.5625f, d1 = 2.75f;
		if (t < 1 / d1) {
			return n1 * t * t;
		} else if (t < 2 / d1) {
			t -= 1.5f / d1;
			return n1 * t * t + 0.75f;
		} else if (t < 2.5 / d1) {
			t -= 2.25f / d1;
			return n1 * t * t + 0.9375f;
		}
		t -= 2.625f / d1;
		return n1 * t * t + 0.984375f;
	}

	/** Maps t from [a, b] to [0, 1], clamped. */
	public static float range(float t, float a, float b) {
		return clamp01((t - a) / (b - a));
	}

	/** Cheap deterministic hash noise in [0, 1). */
	public static float hash(int n) {
		n = (n << 13) ^ n;
		n = n * (n * n * 15731 + 789221) + 1376312589;
		return (n & 0x7fffffff) / (float) 0x7fffffff;
	}
}
