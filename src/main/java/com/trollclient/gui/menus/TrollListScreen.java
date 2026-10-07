package com.trollclient.gui.menus;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.trollclient.gui.Anim;
import com.trollclient.gui.Backdrop;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Motion;
import com.trollclient.gui.ScreenFx;
import com.trollclient.gui.Sounds;
import com.trollclient.gui.Theme;
import com.trollclient.gui.clickgui.TextField;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.ClickGuiModule;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Shared window for the custom singleplayer and multiplayer menus: the same
 * black and white chrome as the ClickGUI, a searchable, keyboard-driven list,
 * and a row of actions along the bottom. Subclasses supply the rows.
 */
public abstract class TrollListScreen<T> extends Screen {
	protected static final float ROW_H = 36;
	private static final float HEADER = 26;
	private static final float FOOTER = 40;

	/** A button in the action bar. {@code hotkey} is shown next to the label, e.g. "enter". */
	protected record Action(String label, String hotkey, BooleanSupplier enabled, Runnable run) {
	}

	protected final Screen parent;
	private final Backdrop backdrop = new Backdrop();
	private final TextField search = new TextField(32);
	private final Anim open = new Anim(0, 7);
	private final Anim scroll = new Anim(16);
	private final Anim selY = new Anim(20);
	private final Anim searchFocus = new Anim(14);
	private final Map<T, Anim> rowHover = new HashMap<>();
	private final Map<Integer, Anim> actionHover = new HashMap<>();
	private final float openedAt = Motion.time();

	private long lastNanos;
	protected float dt;
	private float scrollTarget;
	private int selected = -1;
	private boolean selectionSnapped;
	private T lastClicked;
	private long lastClickTime;
	private String status = "";
	private float statusAt = -10;
	private boolean closing;

	protected TrollListScreen(Component title, Screen parent) {
		super(title);
		this.parent = parent;
		search.onChange(s -> {
			scrollTarget = 0;
			selected = filtered().isEmpty() ? -1 : 0;
		});
	}

	// ------------------------------------------------------------------ subclass hooks

	protected abstract List<T> entries();

	protected abstract String searchText(T entry);

	protected abstract void drawRow(GuiGraphicsExtractor g, T entry, float x, float y, float w, float h, float hover, boolean selected);

	/** Enter or double click. */
	protected abstract void activate(T entry);

	protected abstract List<Action> actions();

	protected abstract String heading();

	protected abstract String tagline();

	protected abstract String emptyText();

	/** Small text in the header, right of the title (counts, status...). */
	protected String headerInfo() {
		return null;
	}

	/** Delete / Backspace on a row. */
	protected void deleteRequested(T entry) {
	}

	// ------------------------------------------------------------------ helpers for subclasses

	protected T selectedEntry() {
		List<T> list = filtered();
		return selected >= 0 && selected < list.size() ? list.get(selected) : null;
	}

	protected void select(T entry) {
		selected = filtered().indexOf(entry);
	}

	/** One-line message in the footer prompt, typed out like the ClickGUI terminal. */
	protected void status(String text) {
		status = text;
		statusAt = Motion.time();
	}

	protected List<T> filtered() {
		String q = search.getText().trim().toLowerCase(Locale.ROOT);
		List<T> all = entries();
		if (q.isEmpty()) {
			return all;
		}
		List<T> out = new ArrayList<>();
		for (T t : all) {
			if (searchText(t).toLowerCase(Locale.ROOT).contains(q)) {
				out.add(t);
			}
		}
		return out;
	}

	protected String query() {
		return search.getText().trim();
	}

	// ------------------------------------------------------------------ layout

	private float winW() {
		return Math.min(width - 16, 470);
	}

	private float winH() {
		return Math.min(height - 16, 320);
	}

	private float winX() {
		return (width - winW()) / 2f;
	}

	private float winY() {
		return (height - winH()) / 2f;
	}

	private float listX() {
		return winX() + 10;
	}

	private float listY() {
		return winY() + HEADER + 22;
	}

	private float listW() {
		return winW() - 20;
	}

	private float listH() {
		return winH() - HEADER - 22 - FOOTER - 6;
	}

	private float[] searchBox() {
		float w = 110 + 50 * Motion.outCubic(searchFocus.get());
		return new float[]{winX() + winW() - w - 26, winY() + 6, w, 14};
	}

	private float[] closeBox() {
		return new float[]{winX() + winW() - 21, winY() + 6, 14, 14};
	}

	private List<float[]> actionBoxes(List<Action> actions) {
		List<float[]> boxes = new ArrayList<>();
		float gap = 5;
		float total = 0;
		float[] widths = new float[actions.size()];
		for (int i = 0; i < actions.size(); i++) {
			Action a = actions.get(i);
			widths[i] = Theme.width(a.label()) + (a.hotkey() == null ? 0 : Theme.width(a.hotkey()) + 6) + 16;
			total += widths[i] + (i > 0 ? gap : 0);
		}
		// squeeze evenly if the window is narrow
		float avail = winW() - 20;
		float k = total > avail ? avail / total : 1;
		float x = winX() + (winW() - Math.min(total, avail)) / 2f;
		float y = winY() + winH() - FOOTER + 6;
		for (float w : widths) {
			boxes.add(new float[]{x, y, w * k, 16});
			x += (w + gap) * k;
		}
		return boxes;
	}

	private static boolean in(double mx, double my, float[] b) {
		return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		Theme.update();
		Draw.rect(g, 0, 0, width, height, 0xFF000000 | Theme.bg);
		backdrop.render(g, ModuleManager.get(ClickGuiModule.class).backdrop.get(), width, height, dt, Motion.outCubic(open.get()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		long now = System.nanoTime();
		dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
		lastNanos = now;
		Theme.update();
		float o = open.target(closing ? 0 : 1).update(dt);
		if (closing && o < 0.02f) {
			minecraft.gui.setScreen(parent);
			return;
		}
		tickScreen();

		float x = winX(), y = winY(), w = winW(), h = winH();
		// the window unrolls from its middle, like the ClickGUI's CRT opening
		float e = Motion.outCubic(o);
		float visible = Math.max(2, h * e);
		float prev = Draw.alpha;
		Draw.alpha = Motion.clamp01(o * 1.5f);
		g.enableScissor(Math.round(x - 8), Math.round(y + (h - visible) / 2f), Math.round(x + w + 8), Math.round(y + (h + visible) / 2f));

		if (Theme.settings().ditherShadow.get()) {
			Draw.ditherShadow(g, x - 1, y - 1, w + 2, h + 2, 5, ColorUtil.withAlpha(0x000000, 170));
		}
		if (Theme.settings().glow.get()) {
			Draw.glow(g, x - 1, y - 1, w + 2, h + 2, Theme.accent(), 8);
		}
		Draw.panel(g, x - 1, y - 1, w + 2, h + 2, Theme.a(Theme.light ? 0xFF505050 : 0xFF000000));
		Draw.panel(g, x, y, w, h, Theme.a(Theme.borderHi));
		Draw.rect(g, x + 1, y + 1, w - 2, h - 2, Theme.a(Theme.bg));
		Draw.gradientH(g, x + 1, y + 1, w - 2, 2, p -> Theme.accent(p / 1000f));

		drawHeader(g, mouseX, mouseY);
		drawList(g, mouseX, mouseY);
		drawFooter(g, mouseX, mouseY);

		g.disableScissor();
		if (o < 0.6f && o > 0.05f) {
			// the bright scan line while it opens
			Draw.rect(g, x, y + (h - visible) / 2f, w, 1, ColorUtil.fade(Theme.text, 1 - o));
			Draw.rect(g, x, y + (h + visible) / 2f - 1, w, 1, ColorUtil.fade(Theme.text, 1 - o));
		}
		Draw.alpha = prev;
		ScreenFx.overlays(g, width, height, e);
	}

	/** Per-frame hook for subclasses (polling pings, refreshing LAN lists...). */
	protected void tickScreen() {
	}

	private void drawHeader(GuiGraphicsExtractor g, int mx, int my) {
		float x = winX(), y = winY(), w = winW();
		Draw.rect(g, x + 1, y + 3, w - 2, HEADER - 3, Theme.a(Theme.panel));
		Draw.rect(g, x + 1, y + HEADER, w - 2, 1, Theme.a(Theme.border));
		float tagW = Theme.width("TROLL") + 6;
		Draw.panel(g, x + 8, y + 8, tagW, 12, Theme.accent(0.05f));
		Draw.text(g, "TROLL", x + 11, y + 10, Theme.onAccent(), false);
		Draw.text(g, heading(), x + tagW + 12, y + 10, Theme.text);

		float sf = searchFocus.target(search.isFocused() || !search.getText().isEmpty()).update(dt);
		float[] s = searchBox();
		Draw.panel(g, s[0], s[1], s[2], s[3], Theme.a(Theme.panel2));
		Draw.panelOutline(g, s[0], s[1], s[2], s[3], ColorUtil.lerp(in(mx, my, s) ? Theme.borderHi : Theme.border, Theme.accent(), sf));
		g.enableScissor(Math.round(s[0] + 3), Math.round(s[1]), Math.round(s[0] + s[2] - 3), Math.round(s[1] + s[3]));
		search.render(g, s[0] + 5, s[1] + 3, s[2] - 10, sf > 0.5f ? "" : "type to search", 1f);
		g.disableScissor();

		float[] c = closeBox();
		boolean overClose = in(mx, my, c);
		if (overClose) {
			Draw.panel(g, c[0], c[1], c[2], c[3], Theme.panel2);
			g.requestCursor(CursorTypes.POINTING_HAND);
		}
		Draw.text(g, "x", c[0] + 4, c[1] + 3, overClose ? Theme.text : Theme.textDim);

		// sub-header: tagline and info
		float age = (Motion.time() - openedAt) * Motion.speed();
		String tag = tagline();
		String shown = tag.substring(0, (int) Math.min(tag.length(), Math.max(0, age - 0.2f) * 50));
		Draw.text(g, shown, listX() + 2, y + HEADER + 8, Theme.textDim);
		String info = headerInfo();
		if (info != null) {
			Draw.textRight(g, info, listX() + listW() - 2, y + HEADER + 8, Theme.textDim);
		}
	}

	private void drawList(GuiGraphicsExtractor g, int mx, int my) {
		float lx = listX(), ly = listY(), lw = listW(), lh = listH();
		Draw.panel(g, lx, ly, lw, lh, Theme.a(Theme.panel));
		Draw.panelOutline(g, lx, ly, lw, lh, Theme.a(Theme.border));

		List<T> list = filtered();
		if (selected >= list.size()) {
			selected = list.size() - 1;
		}
		float content = list.size() * ROW_H;
		float maxScroll = Math.max(0, content - (lh - 4));
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
		float sc = scroll.target(scrollTarget).update(dt);
		float top = ly + 2;
		float age = (Motion.time() - openedAt) * Motion.speed();

		g.enableScissor(Math.round(lx + 1), Math.round(ly + 1), Math.round(lx + lw - 1), Math.round(ly + lh - 1));
		if (selected >= 0) {
			float target = top + selected * ROW_H - sc;
			if (!selectionSnapped) {
				selY.snap(target);
				selectionSnapped = true;
			}
			float sy = selY.target(target).update(dt);
			Draw.rect(g, lx + 2, sy, lw - 4, ROW_H - 2, Theme.a(Theme.panel2));
			Draw.rect(g, lx + 2, sy + 2, 2, ROW_H - 6, Theme.accent());
			Draw.panelOutline(g, lx + 2, sy, lw - 4, ROW_H - 2, Theme.border);
		}
		boolean mouseInList = mx >= lx && mx < lx + lw && my >= ly && my < ly + lh;
		for (int i = 0; i < list.size(); i++) {
			T entry = list.get(i);
			float ry = top + i * ROW_H - sc;
			boolean over = mouseInList && my >= ry && my < ry + ROW_H - 2;
			float hv = rowHover.computeIfAbsent(entry, k -> new Anim(16)).target(over).update(dt);
			if (ry + ROW_H < ly || ry > ly + lh) {
				continue;
			}
			// rows slide in one after another when the screen opens
			float appear = Motion.outCubic(Motion.range(age, 0.15f + i * 0.04f, 0.45f + i * 0.04f));
			float prev = Draw.alpha;
			Draw.alpha = prev * appear;
			if (hv > 0.01f && i != selected) {
				Draw.rect(g, lx + 2, ry, (lw - 4) * Motion.outCubic(hv), ROW_H - 2, ColorUtil.fade(Theme.panel2, 0.6f * hv));
			}
			drawRow(g, entry, lx + 8 + (1 - appear) * 16 + hv * 2, ry, lw - 16, ROW_H - 2, hv, i == selected);
			Draw.alpha = prev;
			if (over) {
				g.requestCursor(CursorTypes.POINTING_HAND);
			}
		}
		g.disableScissor();

		if (list.isEmpty()) {
			String text = query().isEmpty() ? emptyText() : "nothing matches \"" + query() + "\"";
			Draw.textCentered(g, text, lx + lw / 2f, ly + lh / 2f - 4, Theme.textDim);
		}
		if (maxScroll > 0) {
			float th = Math.max(12, (lh - 4) * (lh - 4) / content);
			float ty = ly + 2 + (lh - 4 - th) * (sc / maxScroll);
			Draw.rect(g, lx + lw - 4, ty, 2, th, ColorUtil.fade(Theme.text, 0.35f));
			if (sc > 1) {
				Draw.gradientV(g, lx + 1, ly + 1, lw - 6, 8, Theme.a(Theme.panel), ColorUtil.withAlpha(Theme.panel, 0));
			}
			if (sc < maxScroll - 1) {
				Draw.gradientV(g, lx + 1, ly + lh - 9, lw - 6, 8, ColorUtil.withAlpha(Theme.panel, 0), Theme.a(Theme.panel));
			}
		}
	}

	private void drawFooter(GuiGraphicsExtractor g, int mx, int my) {
		float x = winX(), y = winY() + winH() - FOOTER, w = winW();
		Draw.rect(g, x + 1, y, w - 2, 1, Theme.a(Theme.border));
		Draw.rect(g, x + 1, y + 1, w - 2, FOOTER - 2, Theme.a(Theme.panel));
		List<Action> actions = actions();
		List<float[]> boxes = actionBoxes(actions);
		for (int i = 0; i < actions.size(); i++) {
			Action a = actions.get(i);
			float[] b = boxes.get(i);
			boolean enabled = a.enabled().getAsBoolean();
			boolean over = enabled && in(mx, my, b);
			float hv = actionHover.computeIfAbsent(i, k -> new Anim(18)).target(over).update(dt);
			Draw.panel(g, b[0], b[1], b[2], b[3], Theme.a(Theme.panel2));
			if (hv > 0.01f) {
				// fill sweeps in from the left on hover
				Draw.rect(g, b[0] + 1, b[1] + 1, (b[2] - 2) * Motion.outCubic(hv), b[3] - 2, Theme.accent());
			}
			Draw.panelOutline(g, b[0], b[1], b[2], b[3], ColorUtil.lerp(Theme.border, Theme.accent(), hv));
			int fg = !enabled ? ColorUtil.fade(Theme.textDim, 0.5f) : ColorUtil.lerp(Theme.text, Theme.onAccent(), hv);
			float tx = b[0] + 8;
			g.enableScissor(Math.round(b[0] + 1), Math.round(b[1]), Math.round(b[0] + b[2] - 1), Math.round(b[1] + b[3]));
			Draw.text(g, a.label(), tx, b[1] + 4, fg, false);
			if (a.hotkey() != null) {
				Draw.text(g, a.hotkey(), tx + Theme.width(a.label()) + 6, b[1] + 4,
						!enabled ? ColorUtil.fade(Theme.textDim, 0.4f) : ColorUtil.lerp(Theme.textDim, Theme.onAccent(), hv * 0.7f), false);
			}
			g.disableScissor();
			if (over) {
				g.requestCursor(CursorTypes.POINTING_HAND);
			}
		}
		// fake shell prompt: the last thing that happened
		String user = minecraft.getUser().getName().toLowerCase(Locale.ROOT);
		String prompt = user + "@troll:~$ ";
		float py = y + 26;
		Draw.text(g, prompt, x + 8, py, Theme.accent(0.15f));
		float since = (Motion.time() - statusAt) * Motion.speed();
		String typed = status.substring(0, (int) Math.min(status.length(), Math.max(0, since) * 60));
		typed = Draw.ellipsize(typed, (int) (w - 24 - Theme.width(prompt)));
		float tx = x + 8 + Theme.width(prompt);
		Draw.text(g, typed, tx, py, Theme.text);
		if ((int) (Motion.time() * 2.2f) % 2 == 0) {
			Draw.rect(g, tx + Theme.width(typed) + 1, py - 1, 4, 9, ColorUtil.fade(Theme.text, 0.75f));
		}
	}

	// ------------------------------------------------------------------ input

	@Override
	public void onClose() {
		if (!closing) {
			closing = true;
			Sounds.click();
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (closing) {
			return true;
		}
		double mx = event.x(), my = event.y();
		float[] s = searchBox();
		if (in(mx, my, s)) {
			if (event.button() == 1) {
				search.setText("");
			}
			search.setFocused(true);
			return true;
		}
		search.setFocused(false);
		if (in(mx, my, closeBox())) {
			onClose();
			return true;
		}
		List<Action> actions = actions();
		List<float[]> boxes = actionBoxes(actions);
		for (int i = 0; i < actions.size(); i++) {
			if (in(mx, my, boxes.get(i)) && actions.get(i).enabled().getAsBoolean()) {
				Sounds.click();
				actions.get(i).run().run();
				return true;
			}
		}
		float lx = listX(), ly = listY(), lw = listW(), lh = listH();
		if (mx >= lx && mx < lx + lw && my >= ly && my < ly + lh) {
			int index = (int) ((my - ly - 2 + scroll.get()) / ROW_H);
			List<T> list = filtered();
			if (index >= 0 && index < list.size()) {
				T entry = list.get(index);
				long now = System.currentTimeMillis();
				boolean twice = entry == lastClicked && now - lastClickTime < 350;
				selected = index;
				lastClicked = entry;
				lastClickTime = now;
				Sounds.tick();
				if (twice || doubleClick) {
					activate(entry);
				}
			}
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scrollTarget -= (float) scrollY * ROW_H;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (closing) {
			return true;
		}
		int key = event.key();
		List<T> list = filtered();
		if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
			if (!list.isEmpty()) {
				selected = selected < 0 ? 0 : Math.floorMod(selected + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), list.size());
				float rowTop = selected * ROW_H;
				if (rowTop < scrollTarget) {
					scrollTarget = rowTop;
				} else if (rowTop + ROW_H > scrollTarget + listH() - 4) {
					scrollTarget = rowTop + ROW_H - (listH() - 4);
				}
				Sounds.tick();
			}
			return true;
		}
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
			T entry = selectedEntry();
			if (entry != null) {
				activate(entry);
			}
			return true;
		}
		if (search.isFocused()) {
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				if (search.getText().isEmpty()) {
					search.setFocused(false);
				} else {
					search.setText("");
				}
				return true;
			}
			search.keyPressed(event);
			return true;
		}
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			onClose();
			return true;
		}
		if ((key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) && selectedEntry() != null) {
			deleteRequested(selectedEntry());
			return true;
		}
		if (event.hasControlDown() && key == GLFW.GLFW_KEY_F) {
			search.setFocused(true);
			return true;
		}
		return extraKey(event);
	}

	/** Subclass shortcuts (F5 refresh and so on). */
	protected boolean extraKey(KeyEvent event) {
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (closing) {
			return true;
		}
		if (!search.isFocused()) {
			if (!Character.isLetterOrDigit(event.codepoint())) {
				return true;
			}
			// type-to-search, same as the ClickGUI
			search.setFocused(true);
		}
		return search.charTyped(event.codepointAsString());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
