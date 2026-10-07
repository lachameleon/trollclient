package com.trollclient.gui.clickgui;

import com.trollclient.gui.Anim;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Motion;
import com.trollclient.gui.Sounds;
import com.trollclient.gui.Theme;
import com.trollclient.gui.clickgui.components.BindRow;
import com.trollclient.gui.clickgui.components.ButtonRow;
import com.trollclient.gui.clickgui.components.ColorRow;
import com.trollclient.gui.clickgui.components.ModeRow;
import com.trollclient.gui.clickgui.components.Row;
import com.trollclient.gui.clickgui.components.SliderRow;
import com.trollclient.gui.clickgui.components.TextRow;
import com.trollclient.gui.clickgui.components.ToggleRow;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ColorSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.Setting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;

/** The right-hand group box: description plus a row per setting. */
public class SettingsPanel {
	private static final float ROW_GAP = 2;

	/** The module this panel belongs to, or null for a free-standing one (macro steps). */
	private final Module module;
	private final String title;
	private final String description;
	private final List<Setting<?>> settings;
	/** Fake shell line logged when "reset" wipes the settings. */
	private final String resetLog;
	private final List<Row> rows = new ArrayList<>();
	private final float openedAt = Motion.time();
	private final Anim scroll = new Anim(16);
	private float scrollTarget;
	private float contentHeight;
	private Row hovered;
	private final Anim resetHover = new Anim(16);
	private float resetArmedAt = -10;
	private boolean overReset;

	private float x, y, w, h;

	public SettingsPanel(Module module) {
		this(module, module.getName(), module.getDescription(), module.getSettings(), moduleRows(module),
				"rm ~/" + module.getCategory().getDisplayName().toLowerCase(java.util.Locale.ROOT) + "/"
						+ module.getName().toLowerCase(java.util.Locale.ROOT) + ".cfg");
	}

	/** A panel for any list of settings, with extra rows (binds, buttons) ahead of them. */
	public SettingsPanel(String title, String description, List<Setting<?>> settings, List<Row> leading, String resetLog) {
		this(null, title, description, settings, leading, resetLog);
	}

	private SettingsPanel(Module module, String title, String description, List<Setting<?>> settings, List<Row> leading, String resetLog) {
		this.module = module;
		this.title = title;
		this.description = description;
		this.settings = settings;
		this.resetLog = resetLog;
		rows.addAll(leading);
		for (Setting<?> s : settings) {
			Row row = switch (s) {
				case BoolSetting b -> new ToggleRow(b);
				case NumberSetting n -> new SliderRow(n);
				case ModeSetting m -> new ModeRow(m);
				case TextSetting t -> new TextRow(t);
				case ColorSetting c -> new ColorRow(c);
				default -> null;
			};
			if (row != null) {
				rows.add(row.describe(s::isModified, s.displayDefault()));
			}
		}
	}

	private static List<Row> moduleRows(Module module) {
		List<Row> rows = new ArrayList<>();
		if (module.isToggleable()) {
			rows.add(new ToggleRow("Enabled", "Turn the module on or off", module::isEnabled, module::toggle, () -> true));
			rows.add(new BindRow(module));
			rows.add(new ToggleRow("Show In List", "List the module on the HUD while it's on",
					() -> !module.isHidden(), () -> module.setHidden(!module.isHidden()), () -> true));
		}
		for (Module.Action a : module.getActions()) {
			rows.add(new ButtonRow(a.label(), a.description(), a.run()));
		}
		return rows;
	}

	public Module getModule() {
		return module;
	}

	public void render(GuiGraphicsExtractor g, float x, float y, float w, float h, float mx, float my, float dt) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		float age = Motion.time() - openedAt;

		// typewriter title
		String title = this.title.toLowerCase();
		int typed = (int) Math.min(title.length(), age * Motion.speed() * 40);
		String shown = title.substring(0, typed) + (typed < title.length() || (int) (age * 2) % 2 == 0 ? "_" : " ");
		ClickGuiScreen.groupBox(g, x, y, w, h, shown);
		drawReset(g, mx, my, dt);

		float innerX = x + 6;
		float innerW = w - 12;
		float top = y + 8;
		float bottom = y + h - 4;

		g.enableScissor(Math.round(x + 1), Math.round(top - 2), Math.round(x + w - 1), Math.round(bottom));
		float cursor = top - scroll.update(dt);

		// description fades in after the title finishes
		float descAlpha = Motion.range(age * Motion.speed(), 0.08f, 0.35f);
		for (String line : Draw.wrap(description, (int) innerW - 4)) {
			Draw.text(g, line, innerX + 2, cursor, ColorUtil.fade(Theme.textDim, descAlpha));
			cursor += 10;
		}
		cursor += 3;
		Draw.rect(g, innerX, cursor, innerW * Motion.outCubic(descAlpha), 1, ColorUtil.fade(Theme.border, 0.9f));
		cursor += 5;

		hovered = null;
		int index = 0;
		Matrix3x2fStack pose = g.pose();
		for (Row row : rows) {
			float vis = row.shown.target(row.isVisible()).update(dt);
			if (vis <= 0.001f) {
				continue;
			}
			row.layout(innerX, cursor, innerW);
			float rowH = row.height() * Motion.outCubic(vis);
			// staggered entrance: each row slides in a beat after the one above
			float appear = Motion.outCubic(Motion.range(age * Motion.speed(), 0.1f + index * 0.035f, 0.38f + index * 0.035f));
			float prevAlpha = Draw.alpha;
			Draw.alpha *= appear * vis;
			pose.pushMatrix();
			pose.translate((1 - appear) * 18, 0);
			boolean clip = vis < 1;
			if (clip) {
				g.enableScissor(Math.round(innerX - 2), Math.round(cursor), Math.round(innerX + innerW + 2), Math.round(cursor + rowH));
			}
			boolean inside = (my >= top - 2 && my < bottom) || row.isDragging();
			float rmx = inside ? mx : -9999;
			float rmy = inside ? my : -9999;
			if (row.isHovered(rmx, rmy)) {
				hovered = row;
				Draw.rect(g, innerX - 2, cursor, innerW + 4, row.height(), ColorUtil.fade(Theme.panel2, row.hover.get() * 0.7f));
			}
			row.render(g, rmx, rmy, dt);
			if (row.isModified()) {
				// changed from the default: a small accent pip in the margin
				Draw.rect(g, innerX - 4, cursor + 6, 2, 3, Theme.accent());
			}
			if (clip) {
				g.disableScissor();
			}
			pose.popMatrix();
			Draw.alpha = prevAlpha;
			cursor += rowH + ROW_GAP * vis;
			index++;
		}
		g.disableScissor();

		contentHeight = cursor + scroll.get() - top;
		float maxScroll = Math.max(0, contentHeight - (bottom - top));
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
		scroll.target(scrollTarget);

		if (maxScroll > 0) {
			float trackH = bottom - top;
			float thumbH = Math.max(12, trackH * trackH / contentHeight);
			float thumbY = top + (trackH - thumbH) * (scroll.get() / maxScroll);
			Draw.rect(g, x + w - 4, thumbY, 2, thumbH, ColorUtil.fade(Theme.text, 0.35f));
			// fade the content into the box edges so cut rows don't look broken (over their text, too)
			g.nextStratum();
			// stop short of the scrollbar, like the module list, so the thumb doesn't fade out at the ends
			if (scroll.get() > 1) {
				Draw.gradientV(g, x + 1, top - 2, w - 6, 8, Theme.a(Theme.panel), ColorUtil.withAlpha(Theme.panel, 0));
			}
			if (scroll.get() < maxScroll - 1) {
				Draw.gradientV(g, x + 1, bottom - 8, w - 6, 8, ColorUtil.withAlpha(Theme.panel, 0), Theme.a(Theme.panel));
			}
		}
	}

	private boolean armed() {
		return Motion.time() - resetArmedAt < 2.5f;
	}

	private float[] resetBox() {
		float tw = Theme.width(armed() ? "sure?" : "reset");
		return new float[]{x + w - tw - 14, y - 5, tw + 8, 10};
	}

	/** "reset" sits in the top border like the title; first click arms it, the second wipes the settings. */
	private void drawReset(GuiGraphicsExtractor g, float mx, float my, float dt) {
		if (settings.isEmpty()) {
			overReset = false;
			return;
		}
		float[] b = resetBox();
		overReset = mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
		float hv = resetHover.target(overReset).update(dt);
		boolean armed = armed();
		String label = armed ? "sure?" : "reset";
		Draw.rect(g, b[0] - 2, y, b[2] + 4, 1, Theme.a(Theme.bg));
		if (armed || hv > 0.01f) {
			int fill = armed ? Theme.accent() : ColorUtil.fade(Theme.panel2, hv);
			Draw.panel(g, b[0], b[1], b[2], b[3], fill);
		}
		int color = armed ? Theme.onAccent() : ColorUtil.lerp(Theme.textDim, Theme.text, hv);
		Draw.text(g, label, b[0] + 4, b[1] + 1, color, false);
	}

	public boolean isResetHovered() {
		return overReset;
	}

	public String resetTooltip() {
		return armed() ? "Click again to reset every setting of " + title + "." : "Reset all settings to their defaults.";
	}

	/** Tooltip for a row: its description, plus the default value when it has been changed. */
	public String tooltipFor(Row row) {
		String text = row.getDescription();
		if (row.getDefaultText() != null && row.isModified()) {
			text += "\nchanged (default: " + row.getDefaultText() + ")";
		}
		return text;
	}

	/** Window-local point on a row ({@code fraction} across, vertically centred), for scripted input. */
	public float[] rowPoint(String label, float fraction) {
		for (Row row : rows) {
			if (row.getLabel().equalsIgnoreCase(label) && row.isVisible()) {
				return row.point(fraction);
			}
		}
		return null;
	}

	public Row getHovered() {
		return hovered;
	}

	private boolean inside(float mx, float my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	public boolean mouseClicked(float mx, float my, int button) {
		if (overReset && !settings.isEmpty()) {
			if (armed()) {
				for (Setting<?> setting : settings) {
					setting.reset();
				}
				resetArmedAt = -10;
				Sounds.toggle(false);
				TerminalLog.push(resetLog);
			} else {
				resetArmedAt = Motion.time();
				Sounds.click();
			}
			return true;
		}
		boolean handled = false;
		for (Row row : rows) {
			if (!row.isVisible()) {
				continue;
			}
			boolean in = inside(mx, my);
			if (in && !handled && row.mouseClicked(mx, my, button)) {
				handled = true;
			} else if (row.isCapturing() && !row.isHovered(mx, my)) {
				row.unfocus();
			}
		}
		return handled || inside(mx, my);
	}

	public void mouseReleased(float mx, float my, int button) {
		for (Row row : rows) {
			row.mouseReleased(mx, my, button);
		}
	}

	public boolean mouseScrolled(float mx, float my, double amount) {
		if (!inside(mx, my)) {
			return false;
		}
		for (Row row : rows) {
			if (row.isVisible() && row.mouseScrolled(mx, my, amount)) {
				return true;
			}
		}
		scrollTarget -= (float) amount * 18;
		return true;
	}

	public boolean keyPressed(KeyEvent event) {
		for (Row row : rows) {
			if (row.isCapturing() && row.keyPressed(event)) {
				return true;
			}
		}
		return false;
	}

	public boolean charTyped(String chars) {
		for (Row row : rows) {
			if (row.isCapturing() && row.charTyped(chars)) {
				return true;
			}
		}
		return false;
	}

	public boolean isCapturing() {
		for (Row row : rows) {
			if (row.isCapturing()) {
				return true;
			}
		}
		return false;
	}

	public void unfocusAll() {
		for (Row row : rows) {
			row.unfocus();
		}
	}
}
