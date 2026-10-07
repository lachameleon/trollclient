package com.trollclient.gui.clickgui.components;

import com.trollclient.gui.Anim;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Motion;
import com.trollclient.gui.Sounds;
import com.trollclient.gui.Theme;
import com.trollclient.setting.ColorSetting;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Swatch that unfolds into hue / saturation / brightness sliders. */
public class ColorRow extends Row {
	private static final float COLLAPSED = 16;
	private static final float EXPANDED = 16 + 40;

	private final ColorSetting setting;
	private final Anim open = new Anim(14);
	private boolean expanded;
	private int dragging = -1;
	private float hue;

	public ColorRow(ColorSetting setting) {
		super(setting.getName(), setting.getDescription(), setting::isVisible);
		this.setting = setting;
		this.hue = setting.hsb()[0];
	}

	@Override
	public float height() {
		return COLLAPSED + (EXPANDED - COLLAPSED) * Motion.outCubic(open.get());
	}

	@Override
	public void render(GuiGraphicsExtractor g, float mx, float my, float dt) {
		float o = open.target(expanded).update(dt);
		float hv = hover.target(mx >= x && mx < x + w && my >= y && my < y + COLLAPSED).update(dt);
		Draw.text(g, label, x + 2 + hv * 2, y + 4, ColorUtil.lerp(Theme.textDim, Theme.text, Math.max(hv, o)));

		int color = setting.get() | 0xFF000000;
		Draw.textRight(g, setting.display(), x + w - 18, y + 4, ColorUtil.fade(Theme.textDim, 0.4f + hv * 0.6f));
		Draw.rect(g, x + w - 14, y + 3, 11, 10, color);
		Draw.outline(g, x + w - 15, y + 2, 13, 12, ColorUtil.lerp(Theme.border, Theme.text, hv));

		if (o <= 0.01f) {
			return;
		}
		float[] hsb = setting.hsb();
		if (hsb[1] > 0.02f && hsb[2] > 0.02f) {
			hue = hsb[0]; // grey has no hue; keep the last one the user picked
		}
		if (dragging >= 0) {
			drag(mx);
		}
		float prev = Draw.alpha;
		Draw.alpha *= Motion.clamp01(o * 1.5f - 0.3f);
		float tx = x + 3, tw = w - 6;
		float baseY = y + COLLAPSED + 2;
		bar(g, tx, baseY, tw, p -> ColorUtil.hsb(p, 1f, 1f), hue, "H");
		bar(g, tx, baseY + 13, tw, p -> ColorUtil.hsb(hue, p, Math.max(0.15f, hsb[2])), hsb[1], "S");
		bar(g, tx, baseY + 26, tw, p -> ColorUtil.hsb(hue, hsb[1], p), hsb[2], "B");
		Draw.alpha = prev;
	}

	private interface Gradient {
		int at(float p);
	}

	private void bar(GuiGraphicsExtractor g, float bx, float by, float bw, Gradient gradient, float value, String tag) {
		Draw.text(g, tag, bx, by, Theme.textDim);
		float gx = bx + 9;
		float gw = bw - 9;
		Draw.gradientH(g, gx, by + 1, gw, 6, p -> gradient.at(p / 1000f));
		Draw.outline(g, gx - 1, by, gw + 2, 8, Theme.border);
		float kx = gx + gw * value;
		Draw.rect(g, kx - 1, by - 1, 3, 10, Theme.text);
		Draw.rect(g, kx, by, 1, 8, Theme.panel);
	}

	private void drag(float mx) {
		float gx = x + 3 + 9;
		float gw = w - 6 - 9;
		float p = Motion.clamp01((mx - gx) / gw);
		float[] hsb = setting.hsb();
		switch (dragging) {
			case 0 -> {
				hue = p;
				setting.setHsb(p, Math.max(hsb[1], 0.01f), Math.max(hsb[2], 0.01f));
			}
			case 1 -> setting.setHsb(hue, p, hsb[2]);
			case 2 -> setting.setHsb(hue, hsb[1], p);
			default -> {
			}
		}
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (!isHovered(mx, my)) {
			return false;
		}
		if (my < y + COLLAPSED) {
			if (button == 1) {
				setting.reset();
			} else {
				expanded = !expanded;
			}
			Sounds.click();
			return true;
		}
		if (expanded && button == 0) {
			float rel = my - (y + COLLAPSED + 2);
			int bar = (int) (rel / 13);
			if (bar >= 0 && bar <= 2) {
				dragging = bar;
				drag(mx);
			}
			return true;
		}
		return true;
	}

	@Override
	public void mouseReleased(float mx, float my, int button) {
		dragging = -1;
	}

	@Override
	public boolean isDragging() {
		return dragging >= 0;
	}

	@Override
	public String getDescription() {
		return description + "  (click to edit, right click to reset)";
	}
}
