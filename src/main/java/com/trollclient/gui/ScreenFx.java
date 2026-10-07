package com.trollclient.gui;

import com.trollclient.module.client.ThemeModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Full-screen CRT-ish post effects shared by the ClickGUI and the title screen. */
public final class ScreenFx {
	private ScreenFx() {
	}

	public static void overlays(GuiGraphicsExtractor g, int width, int height, float alpha) {
		if (alpha <= 0.01f) {
			return;
		}
		ThemeModule t = Theme.settings();
		// text is drawn after shapes within a stratum; start a new one so the effects cover text too
		g.nextStratum();
		float prev = Draw.alpha;
		Draw.alpha = 1f;
		if (t.vignette.get()) {
			Draw.vignette(g, width, height, 0.75f * alpha);
		}
		if (t.scanlines.get()) {
			float lightFactor = Theme.light ? 0.45f : 1f; // black lines on white read far stronger
			int a = Math.round(255 * t.scanlineStrength.getFloat() / 100f * alpha * lightFactor);
			int roll = (int) (Motion.time() * 6);
			Draw.tile(g, Draw.SCANLINES, 0, 0, width, height, 0, roll, (a << 24) | 0xFFFFFF);
		}
		if (t.grain.get()) {
			// jump to a new random offset 24 times a second, like real film
			int frame = (int) (Motion.time() * 24);
			int u = (int) (Motion.hash(frame) * 256);
			int v = (int) (Motion.hash(frame * 7 + 3) * 256);
			int a = Math.round(255 * 0.07f * alpha);
			Draw.tile(g, Draw.GRAIN, 0, 0, width, height, u, v, (a << 24) | 0xFFFFFF);
		}
		Draw.alpha = prev;
	}
}
