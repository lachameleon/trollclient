package com.trollclient.gui;

import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.ClickGuiModule;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * The ClickGUI's tooltip for other windows: shows after a short hover,
 * types itself out, and sits on a dithered shadow. Call {@link #reset()}
 * at the start of a frame, {@link #hover} for whatever is under the mouse,
 * and {@link #render} last.
 */
public final class Tooltip {
	private final Anim anim = new Anim(14);
	private Object key;
	private String text;
	private Object lastKey;
	private float hoverSince;
	private String shown;
	private float shownSince;

	public void reset() {
		key = null;
		text = null;
	}

	public void hover(Object key, String text) {
		this.key = key;
		this.text = text;
	}

	public void render(GuiGraphicsExtractor g, float mx, float my, float dt, float maxX, float maxY) {
		ClickGuiModule gui = ModuleManager.get(ClickGuiModule.class);
		float time = Motion.time();
		if (key != lastKey) {
			lastKey = key;
			hoverSince = time;
		}
		boolean show = gui.tooltips.get() && key != null && text != null && time - hoverSince > 0.45f;
		if (show && !text.equals(shown)) {
			shown = text;
			shownSince = time;
		}
		float a = anim.target(show).update(dt);
		if (a <= 0.01f || shown == null) {
			return;
		}
		List<String> lines = new ArrayList<>();
		for (String part : shown.split("\n")) {
			lines.addAll(Draw.wrap(part, 170));
		}
		int maxW = 0;
		for (String l : lines) {
			maxW = Math.max(maxW, Theme.width(l));
		}
		float bw = maxW + 10;
		float bh = lines.size() * 10 + 7;
		float bx = mx + 10;
		float by = my + 12;
		if (bx + bw > maxX) {
			bx = mx - bw - 6;
		}
		bx = Math.max(2, bx);
		if (by + bh > maxY) {
			by = my - bh - 6;
		}
		g.nextStratum();
		float prev = Draw.alpha;
		Draw.alpha = a;
		g.pose().pushMatrix();
		g.pose().translate(0, (1 - a) * 4);
		Draw.ditherShadow(g, bx, by, bw, bh, 3, ColorUtil.withAlpha(0x000000, 160));
		Draw.panel(g, bx, by, bw, bh, Theme.panel2);
		Draw.panelOutline(g, bx, by, bw, bh, Theme.borderHi);
		Draw.rect(g, bx + 1, by + 1, 1, bh - 2, Theme.accent());
		int budget = (int) ((time - shownSince) * 220 * Motion.speed());
		float ty = by + 4;
		for (int i = 0; i < lines.size() && budget > 0; i++) {
			String l = lines.get(i);
			Draw.text(g, l.substring(0, Math.min(l.length(), budget)), bx + 6, ty, i == 0 ? Theme.text : Theme.textDim);
			budget -= l.length();
			ty += 10;
		}
		g.pose().popMatrix();
		Draw.alpha = prev;
	}
}
