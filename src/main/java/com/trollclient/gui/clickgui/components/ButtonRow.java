package com.trollclient.gui.clickgui.components;

import com.trollclient.gui.Draw;
import com.trollclient.gui.Motion;
import com.trollclient.gui.Sounds;
import com.trollclient.gui.Theme;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.BooleanSupplier;

/** A full-width button among the settings rows. Danger buttons want a second click within a couple of seconds. */
public class ButtonRow extends Row {
	private final Runnable action;
	private final boolean danger;
	private float pressedAt = -10;
	private float armedAt = -10;

	public ButtonRow(String label, String description, Runnable action, boolean danger, BooleanSupplier visible) {
		super(label, description, visible);
		this.action = action;
		this.danger = danger;
	}

	public ButtonRow(String label, String description, Runnable action) {
		this(label, description, action, false, () -> true);
	}

	private boolean armed() {
		return danger && Motion.time() - armedAt < 2.5f;
	}

	@Override
	public float height() {
		return 17;
	}

	@Override
	public void render(GuiGraphicsExtractor g, float mx, float my, float dt) {
		float hv = hover.target(isHovered(mx, my)).update(dt);
		float press = Motion.clamp01(1f - (Motion.time() - pressedAt) * 5f);
		boolean armed = armed();
		float bx = x + 1, by = y + 1, bw = w - 2, bh = 14;
		int fill = armed ? Theme.accent() : ColorUtil.lerp(Theme.panel2, Theme.accent(), press * 0.8f);
		Draw.panel(g, bx, by, bw, bh, fill);
		if (danger && !armed) {
			// danger buttons get crawling ants instead of a solid border
			Draw.dashedOutline(g, bx, by, bw, bh, ColorUtil.lerp(Theme.border, Theme.borderHi, hv), Motion.time() * 8);
		} else {
			Draw.panelOutline(g, bx, by, bw, bh, ColorUtil.lerp(Theme.border, Theme.borderHi, hv));
		}
		String text = armed ? "sure? click again" : label.toLowerCase(java.util.Locale.ROOT);
		int color = armed || press > 0.5f ? Theme.onAccent() : ColorUtil.lerp(Theme.textDim, Theme.text, hv);
		Draw.textCentered(g, text, bx + bw / 2f + hv * 2, by + 3, color);
		Draw.text(g, ">", bx + bw - 9 + hv * 2, by + 3, ColorUtil.fade(color, 0.4f + hv * 0.6f));
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (button != 0 || !isHovered(mx, my)) {
			return false;
		}
		if (danger && !armed()) {
			armedAt = Motion.time();
			Sounds.click();
			return true;
		}
		armedAt = -10;
		pressedAt = Motion.time();
		Sounds.toggle(true);
		action.run();
		return true;
	}
}
