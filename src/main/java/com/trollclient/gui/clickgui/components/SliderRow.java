package com.trollclient.gui.clickgui.components;

import com.trollclient.gui.Anim;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Motion;
import com.trollclient.gui.Sounds;
import com.trollclient.gui.Theme;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Label + value on top, slider underneath. Drag, click, or scroll to change. */
public class SliderRow extends Row {
	private final NumberSetting setting;
	private final Anim fill;
	private final Anim bubble = new Anim(18);
	private boolean dragging;

	public SliderRow(NumberSetting setting) {
		super(setting.getName(), setting.getDescription(), setting::isVisible);
		this.setting = setting;
		this.fill = new Anim((float) setting.getPercent(), 20);
	}

	@Override
	public float height() {
		return 24;
	}

	private float trackX() {
		return x + 3;
	}

	private float trackW() {
		return w - 6;
	}

	@Override
	public void render(GuiGraphicsExtractor g, float mx, float my, float dt) {
		boolean hovered = isHovered(mx, my);
		float hv = hover.target(hovered || dragging).update(dt);
		if (dragging) {
			setFromMouse(mx);
		}
		float pct = fill.target((float) setting.getPercent()).update(dt);
		float b = bubble.target(dragging).update(dt);

		Draw.text(g, label, x + 2 + hv * 2, y + 3, ColorUtil.lerp(Theme.textDim, Theme.text, hv));
		String value = setting.display();
		Draw.textRight(g, value, x + w - 2, y + 3, ColorUtil.lerp(Theme.textDim, Theme.accent(), Math.max(hv, b)));

		float tx = trackX(), tw = trackW();
		float ty = y + 15;
		float th = 4 + hv;
		Draw.rect(g, tx, ty, tw, th, Theme.panel2);
		// ticks every 25% give it that old hardware-fader look
		for (int i = 1; i < 4; i++) {
			Draw.rect(g, tx + tw * i / 4f, ty + th, 1, 2, ColorUtil.fade(Theme.border, 0.9f));
		}
		float fw = tw * pct;
		Draw.gradientH(g, tx, ty, fw, th, p -> Theme.accent(p / 1000f * pct));
		Draw.outline(g, tx - 1, ty - 1, tw + 2, th + 2, ColorUtil.lerp(Theme.border, Theme.borderHi, hv));
		// knob
		float kx = tx + fw - 1.5f;
		Draw.rect(g, kx, ty - 2, 3, th + 4, Theme.text);
		Draw.rect(g, kx + 1, ty - 1, 1, th + 2, Theme.panel);

		if (b > 0.02f) {
			// value bubble pops up above the knob while dragging
			float bw = Theme.width(value) + 6;
			float by = ty - 13 - 4 * Motion.outBack(b);
			float bx = Math.max(x, Math.min(x + w - bw, kx - bw / 2f + 1.5f));
			float prev = Draw.alpha;
			Draw.alpha *= b;
			g.nextStratum(); // otherwise the label text underneath shows through the bubble
			Draw.panel(g, bx, by, bw, 11, Theme.accent());
			Draw.text(g, value, bx + 3, by + 2, Theme.onAccent(), false);
			Draw.alpha = prev;
		}
	}

	private void setFromMouse(float mx) {
		double before = setting.get();
		setting.setPercent((mx - trackX()) / trackW());
		if (setting.get() != before) {
			Sounds.tick();
		}
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (button == 0 && isHovered(mx, my)) {
			dragging = true;
			setFromMouse(mx);
			return true;
		}
		if (button == 1 && isHovered(mx, my)) {
			setting.reset();
			Sounds.click();
			return true;
		}
		return false;
	}

	@Override
	public void mouseReleased(float mx, float my, int button) {
		dragging = false;
	}

	@Override
	public boolean isDragging() {
		return dragging;
	}

	@Override
	public boolean mouseScrolled(float mx, float my, double amount) {
		if (isHovered(mx, my)) {
			setting.increment(amount > 0 ? 1 : -1);
			Sounds.tick();
			return true;
		}
		return false;
	}

	@Override
	public String getDescription() {
		return description + "  (right click to reset)";
	}
}
