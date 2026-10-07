package com.trollclient.gui.clickgui.components;

import com.trollclient.gui.Anim;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Motion;
import com.trollclient.gui.Sounds;
import com.trollclient.gui.Theme;
import com.trollclient.setting.BoolSetting;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.BooleanSupplier;

/** On/off switch with a sliding knob. */
public class ToggleRow extends Row {
	private final BooleanSupplier getter;
	private final Runnable toggler;
	private final Anim on;
	private float pressTime = -10;

	public ToggleRow(String label, String description, BooleanSupplier getter, Runnable toggler, BooleanSupplier visible) {
		super(label, description, visible);
		this.getter = getter;
		this.toggler = toggler;
		this.on = new Anim(getter.getAsBoolean() ? 1 : 0, 16);
	}

	public ToggleRow(BoolSetting setting) {
		this(setting.getName(), setting.getDescription(), setting::get, setting::toggle, setting::isVisible);
	}

	@Override
	public float height() {
		return 16;
	}

	@Override
	public void render(GuiGraphicsExtractor g, float mx, float my, float dt) {
		float t = on.target(getter.getAsBoolean()).update(dt);
		float hv = hover.target(isHovered(mx, my)).update(dt);

		Draw.text(g, label, x + 2 + hv * 2, y + 4, ColorUtil.lerp(Theme.textDim, Theme.text, Math.max(t, hv)));

		float sw = 20, sh = 10;
		float sx = x + w - sw - 2;
		float sy = y + 3;
		// spelled-out state next to the switch, so nobody has to decode which side the knob is on
		float e = Motion.outCubic(t);
		Draw.textRight(g, "off", sx - 4, y + 4 - e * 3, ColorUtil.fade(Theme.textDim, (1 - e) * (0.55f + hv * 0.45f)));
		Draw.textRight(g, "on", sx - 4, y + 4 + (1 - e) * 3, ColorUtil.fade(Theme.text, e * (0.7f + hv * 0.3f)));

		// off: hollow track; on: solid accent track that fills from the left
		Draw.panel(g, sx, sy, sw, sh, Theme.panel2);
		Draw.rect(g, sx + 1, sy + 1, (sw - 2) * e, sh - 2, Theme.accent(t));
		Draw.panelOutline(g, sx, sy, sw, sh, ColorUtil.lerp(ColorUtil.lerp(Theme.border, Theme.borderHi, hv), Theme.accent(t), e));

		// knob slides over with a little overshoot, squashes right after a click
		float squash = 1f - 0.35f * Motion.clamp01(1f - (Motion.time() - pressTime) * 6f);
		float kw = 6 * squash + 1;
		float kh = sh - 4;
		float kx = sx + 2 + (sw - 4 - kw) * Motion.outBack(t);
		float ky = sy + 2;
		if (t > 0.5f) {
			Draw.rect(g, kx, ky, kw, kh, Theme.onAccent());
		} else {
			int knob = ColorUtil.lerp(Theme.textDim, Theme.text, hv);
			Draw.outline(g, kx, ky, kw, kh, knob);
		}
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (button == 0 && isHovered(mx, my)) {
			toggler.run();
			pressTime = Motion.time();
			Sounds.toggle(getter.getAsBoolean());
			return true;
		}
		return false;
	}
}
