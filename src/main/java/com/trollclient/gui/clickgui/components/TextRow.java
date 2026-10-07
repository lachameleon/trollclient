package com.trollclient.gui.clickgui.components;

import com.trollclient.gui.Anim;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Sounds;
import com.trollclient.gui.Theme;
import com.trollclient.gui.clickgui.TextField;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;

/** Label with an input box underneath; Tab completes from the setting's suggestions. */
public class TextRow extends Row {
	private final TextSetting setting;
	private final TextField field;
	private final Anim focus = new Anim(16);

	public TextRow(TextSetting setting) {
		super(setting.getName(), setting.getDescription(), setting::isVisible);
		this.setting = setting;
		this.field = new TextField(setting.getMaxLength())
				.onChange(setting::set)
				.suggestions(setting::getSuggestions);
		this.field.setText(setting.get());
	}

	@Override
	public float height() {
		return 31;
	}

	@Override
	public void render(GuiGraphicsExtractor g, float mx, float my, float dt) {
		if (!field.isFocused() && !field.getText().equals(setting.get())) {
			field.setText(setting.get());
		}
		float hv = hover.target(isHovered(mx, my)).update(dt);
		float f = focus.target(field.isFocused()).update(dt);
		Draw.text(g, label, x + 2 + hv * 2, y + 3, ColorUtil.lerp(Theme.textDim, Theme.text, Math.max(hv, f)));
		if (f > 0.5f && !setting.getSuggestions().isEmpty()) {
			Draw.textRight(g, "tab ->", x + w - 2, y + 3, ColorUtil.fade(Theme.textDim, f));
		}
		float fx = x + 2, fy = y + 14, fw = w - 4, fh = 14;
		Draw.panel(g, fx, fy, fw, fh, Theme.panel2);
		Draw.panelOutline(g, fx, fy, fw, fh, ColorUtil.lerp(ColorUtil.lerp(Theme.border, Theme.borderHi, hv), Theme.accent(), f));
		// focus underline sweeps out from the centre
		float lw = fw * f;
		Draw.rect(g, fx + (fw - lw) / 2f, fy + fh - 1, lw, 1, Theme.accent());
		g.enableScissor(Math.round(fx + 1), Math.round(fy), Math.round(fx + fw - 1), Math.round(fy + fh));
		field.render(g, fx + 4, fy + 3, fw - 8, setting.getPlaceholder().isEmpty() ? "type..." : setting.getPlaceholder(), 1f);
		g.disableScissor();
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		boolean inside = isHovered(mx, my);
		if (inside && button == 1) {
			field.setText("");
			setting.set("");
			Sounds.click();
			return true;
		}
		if (inside != field.isFocused()) {
			field.setFocused(inside);
			if (inside) {
				Sounds.click();
			}
		}
		return inside;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return field.keyPressed(event);
	}

	@Override
	public boolean charTyped(String chars) {
		return field.charTyped(chars);
	}

	@Override
	public boolean isCapturing() {
		return field.isFocused();
	}

	@Override
	public void unfocus() {
		field.setFocused(false);
	}

	@Override
	public String getDescription() {
		return description + "  (right click to clear)";
	}
}
