package com.trollclient.gui.hud;

import com.trollclient.gui.Draw;
import com.trollclient.gui.Icon;
import com.trollclient.gui.Motion;
import com.trollclient.gui.Theme;
import com.trollclient.gui.clickgui.TerminalLog;
import com.trollclient.module.Module;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.HudModule;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Toasts that slide in from the right edge when modules toggle. */
public final class Notifications {
	private static final float LIFETIME = 2.2f;
	private static final float SLIDE = 0.3f;
	private static final List<Note> NOTES = new ArrayList<>();

	private static final class Note {
		final String title;
		final String body;
		final Icon icon;
		final boolean on;
		final float born = Motion.time();
		float y = Float.NaN;

		Note(String title, String body, Icon icon, boolean on) {
			this.title = title;
			this.body = body;
			this.icon = icon;
			this.on = on;
		}
	}

	private Notifications() {
	}

	public static void moduleToggled(Module module) {
		TerminalLog.push(module.getName().toLowerCase(Locale.ROOT) + (module.isEnabled() ? " --enable" : " --disable"));
		post(module.getName(), module.isEnabled() ? "enabled" : "disabled", module.isEnabled());
	}

	public static void post(String title, String body, boolean on) {
		post(title, body, on ? Icon.CHECK : Icon.CLOSE, on);
	}

	/** {@code highlight} picks the accent colour for the stripe and icon instead of the dim one. */
	public static void post(String title, String body, Icon icon, boolean highlight) {
		// replace an existing toast for the same thing instead of stacking duplicates
		NOTES.removeIf(n -> n.title.equals(title));
		NOTES.add(new Note(title, body, icon, highlight));
		while (NOTES.size() > 5) {
			NOTES.remove(0);
		}
	}

	public static void render(GuiGraphicsExtractor g, int width, int height, float dt) {
		HudModule hud = ModuleManager.get(HudModule.class);
		if (hud == null || !hud.isEnabled() || !hud.notifications.get() || NOTES.isEmpty()) {
			return;
		}
		float now = Motion.time();
		float baseY = height - 44;
		Iterator<Note> it = NOTES.iterator();
		while (it.hasNext()) {
			if (now - it.next().born > LIFETIME + SLIDE) {
				it.remove();
			}
		}
		float cursor = baseY;
		for (int i = NOTES.size() - 1; i >= 0; i--) {
			Note n = NOTES.get(i);
			float w = Math.max(Theme.width(n.title), Theme.width(n.body)) + 30;
			float h = 24;
			float targetY = cursor - h;
			n.y = Float.isNaN(n.y) ? targetY : n.y + (targetY - n.y) * (1 - (float) Math.exp(-14 * dt));
			float age = now - n.born;
			float in = Motion.outBack(Motion.range(age, 0, SLIDE));
			float out = Motion.inCubic(Motion.range(age, LIFETIME, LIFETIME + SLIDE));
			float x = width - 6 - w + (1 - in) * (w + 10) + out * (w + 10);

			Draw.ditherShadow(g, x, n.y, w, h, 3, ColorUtil.withAlpha(0x000000, 140));
			Draw.panel(g, x, n.y, w, h, Theme.a(Theme.panel));
			Draw.panelOutline(g, x, n.y, w, h, Theme.border);
			Draw.rect(g, x + 1, n.y + 1, 2, h - 2, n.on ? Theme.accent() : Theme.textDim);
			Draw.icon(g, n.icon, x + 7, n.y + 7, 10, n.on ? Theme.accent() : Theme.textDim);
			Draw.text(g, n.title, x + 21, n.y + 4, Theme.text);
			Draw.text(g, n.body, x + 21, n.y + 13, Theme.textDim);
			float life = 1f - Motion.clamp01(age / LIFETIME);
			Draw.rect(g, x + 3, n.y + h - 2, (w - 4) * life, 1, ColorUtil.fade(Theme.accent(), 0.8f));
			cursor = targetY - 4;
		}
	}
}
