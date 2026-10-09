package com.trollclient.gui.clickgui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.trollclient.config.ConfigManager;
import com.trollclient.gui.Anim;
import com.trollclient.gui.Backdrop;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Icon;
import com.trollclient.gui.Motion;
import com.trollclient.gui.ScreenFx;
import com.trollclient.gui.Sounds;
import com.trollclient.gui.Theme;
import com.trollclient.gui.clickgui.components.BindRow;
import com.trollclient.gui.clickgui.components.Row;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.ClickGuiModule;
import com.trollclient.module.client.ThemeModule;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The CS:GO-cheat-menu style window: tabs down the left, module list in the
 * middle, settings on the right, a fake shell prompt along the bottom.
 * Everything is laid out in window-local units and scaled as one piece.
 */
public class ClickGuiScreen extends Screen {
	static final float W = 470;
	static final float H = 310;
	static final float SIDEBAR = 46;
	static final float HEADER = 24;
	static final float FOOTER = 15;
	static final float TAB_H = 34;
	static final float ROW_H = 19;
	private static final float OPEN_SECONDS = 0.55f;
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final String[] QUIPS = {
			"ready to ruin someone's day", "all systems mischievous", "twerk.exe loaded",
			"no warranty. no regrets.", "now with 40% more trolling", "press any module to continue",
			"have you tried turning them off and on again"
	};
	private static final String[] TIPS = {
			"tip: middle click a module to bind a key to it", "tip: up/down picks a module, space toggles, enter opens it",
			"tip: left/right or tab switches category", "tip: ctrl + scroll resizes the menu",
			"tip: double click the title bar to recenter the window", "tip: right click a slider to reset it",
			"tip: just start typing to search", "tip: .help in chat lists every command",
			"tip: the palette button cycles themes, right click goes back"
	};
	private static final Map<Category, String> LAST_SELECTED = new EnumMap<>(Category.class);

	private final Screen parent;
	private final Backdrop backdrop = new Backdrop();
	private final Map<Module, RowState> rowStates = new HashMap<>();
	private final Anim[] tabHover = new Anim[Category.values().length];
	private final Anim tabY = new Anim(18);
	private final Anim listScroll = new Anim(16);
	private final Anim searchFocus = new Anim(14);
	private final Anim tooltipAnim = new Anim(14);
	private final Anim[] buttonHover = {new Anim(16), new Anim(16), new Anim(16)};
	private final TextField search = new TextField(24);

	private long lastNanos;
	private float dt;
	private float progress;
	private boolean closing;
	private float winX;
	private float winY;
	private float scale = 1f;
	private boolean dragging;
	private float dragDX;
	private float dragDY;
	private boolean firstFrame = true;
	/** The bind key press that opened the menu also reaches it; ignore it until a frame has rendered. */
	private boolean bindArmed;

	private Category category;
	private float categorySwitchTime = -10;
	private int switchDir = 1;
	private SettingsPanel panel;
	private float listScrollTarget;
	private float themeFlashTime = -10;

	/** Keyboard cursor in the module list; only drawn while the keyboard was used last. */
	private int cursor = -1;
	private boolean keyboardNav;
	private final Anim cursorY = new Anim(22);
	private double lastMouseX = Double.NaN;
	private double lastMouseY = Double.NaN;
	/** Module waiting for a key after a middle click. */
	private Module binding;
	private float bindingSince;
	private float lastHeaderClick = -10;
	private int flyoutTab = -1;
	private int shownFlyout = -1;
	private final Anim flyout = new Anim(18);
	private int tipIndex = ThreadLocalRandom.current().nextInt(TIPS.length);

	private Object hoverKey;
	private String hoverText;
	private float hoverSince;
	private float shownTooltipSince;
	private String shownTooltip;
	private boolean wantHand;

	private static final class RowState {
		final Anim hover = new Anim(16);
		final Anim enabled = new Anim(14);
		final Anim selected = new Anim(16);
		float rippleTime = -10;
		float shineTime = -10;
		float rippleX;
	}

	public ClickGuiScreen(Screen parent) {
		super(Component.literal("Troll Client"));
		this.parent = parent;
		for (int i = 0; i < tabHover.length; i++) {
			tabHover[i] = new Anim(16);
		}
		category = GuiState.category;
		ClickGuiModule gui = ModuleManager.get(ClickGuiModule.class);
		if (gui.rememberModule.get()) {
			Module remembered = ModuleManager.byName(GuiState.selectedModule);
			if (remembered != null && remembered.getCategory() == category) {
				panel = new SettingsPanel(remembered);
			}
		}
		for (Module m : ModuleManager.all()) {
			RowState st = state(m);
			st.enabled.snap(m.isEnabled() ? 1 : 0);
		}
		categorySwitchTime = Motion.time();
		search.onChange(s -> {
			listScrollTarget = 0;
			// show which result Enter will open
			cursor = s.isBlank() ? -1 : 0;
			keyboardNav = !s.isBlank();
		});
		String user = minecraft.getUser().getName();
		TerminalLog.push("hi " + user.toLowerCase(Locale.ROOT) + ", " + QUIPS[ThreadLocalRandom.current().nextInt(QUIPS.length)]);
		Sounds.open();
	}

	private RowState state(Module m) {
		return rowStates.computeIfAbsent(m, k -> new RowState());
	}

	// ------------------------------------------------------------------ lifecycle

	@Override
	public boolean isPauseScreen() {
		return ModuleManager.get(ClickGuiModule.class).pause.get();
	}

	@Override
	public void onClose() {
		if (!closing) {
			closing = true;
			search.setFocused(false);
			if (panel != null) {
				panel.unfocusAll();
			}
			Sounds.click();
		}
	}

	@Override
	public void removed() {
		GuiState.category = category;
		ConfigManager.markDirty();
	}

	private void finishClose() {
		minecraft.gui.setScreen(parent);
	}

	// ------------------------------------------------------------------ layout

	private void layout() {
		float user = ModuleManager.get(ClickGuiModule.class).scale.getFloat();
		float fit = Math.min((width - 12) / W, (height - 12) / H);
		scale = Math.max(0.3f, Math.min(user, fit));
		float sw = W * scale;
		float sh = H * scale;
		float targetX = GuiState.centerX * width - sw / 2f;
		float targetY = GuiState.centerY * height - sh / 2f;
		targetX = Math.max(4, Math.min(width - sw - 4, targetX));
		targetY = Math.max(4, Math.min(height - sh - 4, targetY));
		if (firstFrame || dragging) {
			winX = targetX;
			winY = targetY;
		} else {
			// windows coast into place after a resize instead of teleporting
			float k = 1f - (float) Math.exp(-20 * dt);
			winX += (targetX - winX) * k;
			winY += (targetY - winY) * k;
		}
	}

	private float lx(double screenX) {
		return (float) ((screenX - winX) / scale);
	}

	private float ly(double screenY) {
		return (float) ((screenY - winY) / scale);
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		Theme.update();
		ClickGuiModule gui = ModuleManager.get(ClickGuiModule.class);
		float p = Motion.outCubic(progress);
		if (minecraft.level == null) {
			Draw.screen(g, width, height);
		} else {
			String mode = gui.background.get();
			if (mode.contains("Blur") && p > 0.05f) {
				g.blurBeforeThisStratum();
			}
			if (mode.contains("Dim")) {
				int base = Theme.light ? 0xFFFFFF : 0x000000;
				Draw.rect(g, 0, 0, width, height, ColorUtil.withAlpha(base, Math.round(255 * gui.dim.getFloat() / 100f * p)));
			}
		}
		backdrop.render(g, gui.backdrop.get(), width, height, dt, p);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		long now = System.nanoTime();
		dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
		lastNanos = now;
		Theme.update();
		ClickGuiModule gui = ModuleManager.get(ClickGuiModule.class);

		float step = dt / OPEN_SECONDS * gui.speed();
		progress = Motion.clamp01(progress + (closing ? -step : step));
		if (closing && progress <= 0) {
			finishClose();
			return;
		}
		layout();
		if (firstFrame) {
			tabY.snap(tabTop(category.ordinal()));
			firstFrame = false;
		} else {
			bindArmed = true;
		}

		if (mouseX != lastMouseX || mouseY != lastMouseY) {
			// moving the mouse hands control back from the keyboard cursor
			if (!Double.isNaN(lastMouseX)) {
				keyboardNav = false;
			}
			lastMouseX = mouseX;
			lastMouseY = mouseY;
		}
		if (TerminalLog.idleMillis() > 9000 && !closing) {
			TerminalLog.push(TIPS[tipIndex++ % TIPS.length]);
		}

		hoverKey = null;
		hoverText = null;
		wantHand = false;
		boolean interactive = !closing && progress > 0.6f;
		float mx = interactive ? lx(mouseX) : -9999;
		float my = interactive ? ly(mouseY) : -9999;

		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(winX, winY);
		pose.scale(scale, scale);
		float prevAlpha = Draw.alpha;
		switch (gui.openAnimation.get()) {
			case "Zoom" -> {
				float e = closing ? Motion.outCubic(progress) : Motion.outBack(progress);
				float s = 0.55f + 0.45f * e;
				pose.translate(W / 2f, H / 2f);
				pose.scale(s, s);
				pose.translate(-W / 2f, -H / 2f);
				Draw.alpha = Motion.clamp01(progress * 1.7f);
				drawWindow(g, mx, my);
			}
			case "Drop" -> {
				float e = closing ? Motion.inOutCubic(progress) : Motion.outBounce(progress);
				pose.translate(0, -(1 - e) * (winY / scale + H + 10));
				drawWindow(g, mx, my);
			}
			case "Glitch" -> drawGlitch(g, mx, my);
			case "Assemble" -> drawWindow(g, mx, my);
			case "Fade" -> {
				Draw.alpha = Motion.outCubic(progress);
				pose.translate(0, (1 - Motion.outCubic(progress)) * 8);
				drawWindow(g, mx, my);
			}
			default -> drawCrt(g, mx, my);
		}
		Draw.alpha = prevAlpha;
		if (interactive) {
			// new stratum, or the text underneath would draw over the tooltip box
			g.nextStratum();
			drawTooltip(g, mx, my);
		}
		pose.popMatrix();

		ScreenFx.overlays(g, width, height, Motion.outCubic(progress));
		if (wantHand) {
			g.requestCursor(CursorTypes.POINTING_HAND);
		} else if (dragging) {
			g.requestCursor(CursorTypes.RESIZE_ALL);
		}
	}

	/** Old TV turning on: a line, then the picture unrolls from the middle. */
	private void drawCrt(GuiGraphicsExtractor g, float mx, float my) {
		float p = progress;
		if (p < 0.3f) {
			float a = Motion.outCubic(p / 0.3f);
			float lw = Math.max(2, W * a);
			float cy = H / 2f;
			Draw.glow(g, (W - lw) / 2f, cy - 1, lw, 2, Theme.text, 6);
			Draw.rect(g, (W - lw) / 2f, cy - 1, lw, 2, Theme.text);
			Draw.rect(g, W / 2f - 3, cy - 2, 6, 4, Theme.text);
			return;
		}
		float b = Motion.outCubic(Motion.range(p, 0.3f, 0.8f));
		float vh = Math.max(2, (H + 8) * b);
		float top = (H - vh) / 2f;
		g.enableScissor(-8, Math.round(top), Math.round(W + 8), Math.round(top + vh));
		drawWindow(g, mx, my);
		float flash = 1f - Motion.range(p, 0.3f, 0.75f);
		if (flash > 0.01f) {
			g.nextStratum();
		}
		Draw.rect(g, 0, Math.max(0, top), W, Math.min(H, vh), ColorUtil.fade(Theme.text, flash * 0.9f));
		if (Theme.settings().flash.get() && p > 0.32f && p < 0.4f) {
			Draw.invert(g, 0, Math.max(0, top), W, Math.min(H, vh));
		}
		g.disableScissor();
	}

	/** Horizontal slices jitter around and settle as the window opens. */
	private void drawGlitch(GuiGraphicsExtractor g, float mx, float my) {
		float p = progress;
		if (p >= 0.999f) {
			drawWindow(g, mx, my);
			return;
		}
		float intensity = (1 - p) * (1 - p);
		int seed = (int) (Motion.time() * 20);
		int slices = 10;
		float sliceH = (H + 10) / slices;
		Matrix3x2fStack pose = g.pose();
		float prev = Draw.alpha;
		Draw.alpha = prev * Motion.clamp01(p * 1.4f);
		for (int i = 0; i < slices; i++) {
			float off = (Motion.hash(i * 31 + seed * 7) - 0.5f) * 2 * 70 * intensity;
			g.enableScissor(-20, Math.round(i * sliceH - 2), Math.round(W + 20), Math.round((i + 1) * sliceH - 2));
			pose.pushMatrix();
			pose.translate(off, 0);
			drawWindow(g, mx, my);
			pose.popMatrix();
			g.disableScissor();
		}
		Draw.alpha = prev;
		if (intensity > 0.05f) {
			for (int k = 0; k < 3; k++) {
				float y = Motion.hash(seed * 13 + k) * H;
				Draw.invert(g, 0, y, W, 1 + Motion.hash(seed + k * 5) * 5);
			}
		}
	}

	private void drawWindow(GuiGraphicsExtractor g, float mx, float my) {
		// "Assemble": the frame wipes open from the middle, then each part slides into place
		boolean asm = ModuleManager.get(ClickGuiModule.class).openAnimation.is("Assemble") && progress < 1;
		float frame = asm ? Motion.outCubic(Motion.range(progress, 0f, 0.4f)) : 1f;
		if (frame <= 0.001f) {
			return;
		}
		if (asm) {
			float half = (W / 2f + 10) * frame;
			g.enableScissor(Math.round(W / 2f - half), -10, Math.round(W / 2f + half), Math.round(H + 10));
		}
		ThemeModule t = Theme.settings();
		if (t.ditherShadow.get()) {
			Draw.ditherShadow(g, -1, -1, W + 2, H + 2, 5, ColorUtil.withAlpha(Theme.light ? 0x000000 : 0x000000, 170));
		}
		if (t.glow.get()) {
			Draw.glow(g, -1, -1, W + 2, H + 2, Theme.accent(), 8);
		}
		Draw.panel(g, -1, -1, W + 2, H + 2, Theme.a(Theme.light ? 0xFF505050 : 0xFF000000));
		Draw.panel(g, 0, 0, W, H, Theme.a(Theme.borderHi));
		Draw.rect(g, 1, 1, W - 2, H - 2, Theme.a(Theme.bg));
		Draw.gradientH(g, 1, 1, W - 2, 2, p -> Theme.accent(p / 1000f));
		Draw.rect(g, 1, 3, W - 2, 1, Theme.a(ColorUtil.withAlpha(0x000000, 90)));

		section(g, asm, 0.18f, 0.55f, 0, -HEADER - 8, () -> drawHeader(g, mx, my));
		section(g, asm, 0.24f, 0.62f, -SIDEBAR - 8, 0, () -> drawSidebar(g, mx, my));
		section(g, asm, 0.34f, 0.92f, 0, 16, () -> {
			drawContent(g, mx, my);
			drawFlyout(g);
		});
		section(g, asm, 0.3f, 0.68f, 0, FOOTER + 8, () -> drawFooter(g));
		if (asm) {
			// a bright scan bar rides the leading edges while the frame opens
			float edge = (W / 2f) * frame;
			int bar = ColorUtil.fade(Theme.text, (1 - frame) * 0.9f);
			Draw.rect(g, W / 2f - edge, 0, 2, H, bar);
			Draw.rect(g, W / 2f + edge - 2, 0, 2, H, bar);
			g.disableScissor();
		}

		float flash = Motion.time() - themeFlashTime;
		if (flash < 0.09f) {
			g.nextStratum();
			Draw.invert(g, 1, 1, W - 2, H - 2);
		}
	}

	/** One part of the window, slid in from (dx, dy) and faded while progress runs from a to b. */
	private void section(GuiGraphicsExtractor g, boolean animate, float a, float b, float dx, float dy, Runnable draw) {
		if (!animate) {
			draw.run();
			return;
		}
		float t = Motion.outCubic(Motion.range(progress, a, b));
		if (t <= 0.001f) {
			return;
		}
		float prev = Draw.alpha;
		Draw.alpha = prev * t;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(dx * (1 - t), dy * (1 - t));
		draw.run();
		pose.popMatrix();
		Draw.alpha = prev;
	}

	// ------------------------------------------------------------------ header

	private float searchWidth() {
		return 96 + 54 * Motion.outCubic(searchFocus.get());
	}

	private boolean inRect(float mx, float my, float x, float y, float w, float h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	private float[] closeBox() {
		return new float[]{W - 21, 7, 15, 15};
	}

	private float[] paletteBox() {
		return new float[]{W - 38, 7, 15, 15};
	}

	private float[] searchBox() {
		float sw = searchWidth();
		return new float[]{W - 44 - sw, 7, sw, 15};
	}

	private void drawHeader(GuiGraphicsExtractor g, float mx, float my) {
		Draw.rect(g, 1, 4, W - 2, HEADER - 4, Theme.a(Theme.panel));
		Draw.rect(g, 1, HEADER, W - 2, 1, Theme.a(Theme.border));
		if (Theme.gloss) {
			// glass title bar, accent stripe included
			Draw.gloss(g, 1, 1, W - 2, HEADER - 1, Theme.opacity);
		}

		// logo: inverted [TROLL] block then CLIENT, with the occasional glitch
		String a = glitchText("TROLL", 0);
		String b = glitchText("CLIENT", 1);
		float lx = 10;
		float ly = 10;
		float aw = Theme.width("TROLL") + 6;
		boolean glitching = isGlitchFrame(0);
		Draw.panel(g, lx - 3 + (glitching ? 1 : 0), ly - 3, aw, 13, Theme.accent(0.05f));
		Draw.text(g, a, lx, ly, Theme.onAccent(), false);
		Draw.text(g, b, lx + aw + 3, ly, Theme.text);
		Draw.text(g, "v1.0", lx + aw + 7 + Theme.width("CLIENT"), ly, Theme.textDim);
		if (glitching) {
			Draw.invert(g, lx - 3, ly + 2 + Motion.hash((int) (Motion.time() * 30)) * 6, aw + Theme.width("CLIENT") + 6, 2);
		}

		// search
		float sf = searchFocus.target(search.isFocused() || !search.getText().isEmpty()).update(dt);
		float[] s = searchBox();
		boolean overSearch = inRect(mx, my, s[0], s[1], s[2], s[3]);
		Draw.panel(g, s[0], s[1], s[2], s[3], Theme.a(Theme.panel2));
		Draw.panelOutline(g, s[0], s[1], s[2], s[3], ColorUtil.lerp(overSearch ? Theme.borderHi : Theme.border, Theme.accent(), sf));
		Draw.icon(g, Icon.SEARCH, s[0] + 4, s[1] + 3.5f, 8, ColorUtil.lerp(Theme.textDim, Theme.accent(), sf));
		g.enableScissor(Math.round(s[0] + 14), Math.round(s[1]), Math.round(s[0] + s[2] - 2), Math.round(s[1] + s[3]));
		search.render(g, s[0] + 16, s[1] + 4, s[2] - 20, sf > 0.5f ? "" : "type to search", 1f);
		g.disableScissor();
		if (overSearch) {
			hover("search", "Search every module by name or description. Ctrl+F or just type.");
		}

		headerButton(g, 0, paletteBox(), Icon.PALETTE, mx, my, "Theme: " + Theme.settings().preset.get() + "  (click to cycle)");
		headerButton(g, 1, closeBox(), Icon.CLOSE, mx, my, "Close (Esc)");
	}

	private void headerButton(GuiGraphicsExtractor g, int index, float[] box, Icon icon, float mx, float my, String tip) {
		boolean over = inRect(mx, my, box[0], box[1], box[2], box[3]);
		float hv = buttonHover[index].target(over).update(dt);
		if (hv > 0.01f) {
			Draw.panel(g, box[0], box[1], box[2], box[3], ColorUtil.fade(Theme.panel2, hv));
			Draw.panelOutline(g, box[0], box[1], box[2], box[3], ColorUtil.fade(Theme.borderHi, hv));
		}
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(box[0] + box[2] / 2f, box[1] + box[3] / 2f);
		pose.rotate(hv * (index == 1 ? (float) Math.PI / 2 : (float) Math.PI / 6));
		Draw.icon(g, icon, -5, -5, 10, ColorUtil.lerp(Theme.textDim, Theme.text, hv));
		pose.popMatrix();
		if (over) {
			hover(icon, tip);
			wantHand = true;
		}
	}

	private boolean isGlitchFrame(int salt) {
		float t = Motion.time();
		int window = (int) (t / 3.7f);
		float phase = t - window * 3.7f;
		return Motion.hash(window * 17 + salt) > 0.55f && phase < 0.14f;
	}

	private String glitchText(String text, int salt) {
		if (!isGlitchFrame(salt)) {
			return text;
		}
		char[] chars = text.toCharArray();
		int frame = (int) (Motion.time() * 30);
		int idx = (int) (Motion.hash(frame + salt * 3) * chars.length);
		String pool = "#%&@$/\\";
		chars[Math.min(chars.length - 1, idx)] = pool.charAt((int) (Motion.hash(frame * 5) * pool.length()) % pool.length());
		return new String(chars);
	}

	// ------------------------------------------------------------------ sidebar

	private float tabTop(int index) {
		return HEADER + 7 + index * TAB_H;
	}

	private float[] panicBox() {
		return new float[]{(SIDEBAR - 16) / 2f, H - FOOTER - 24, 16, 16};
	}

	private void drawSidebar(GuiGraphicsExtractor g, float mx, float my) {
		float top = HEADER + 1;
		float bottom = H - FOOTER;
		Draw.rect(g, 1, top, SIDEBAR - 1, bottom - top, Theme.a(Theme.panel));
		Draw.rect(g, SIDEBAR, top, 1, bottom - top, Theme.a(Theme.border));

		Category[] cats = Category.values();
		flyoutTab = -1;
		float iy = tabY.target(tabTop(category.ordinal())).update(dt);
		// selected tab is "cut out" of the sidebar so it joins the content area
		Draw.rect(g, 1, iy, SIDEBAR, TAB_H, Theme.a(Theme.bg));
		Draw.rect(g, 1, iy, SIDEBAR - 1, 1, Theme.a(Theme.border));
		Draw.rect(g, 1, iy + TAB_H - 1, SIDEBAR - 1, 1, Theme.a(Theme.border));
		Draw.rect(g, 1, iy + 4, 2, TAB_H - 8, Theme.accent(0.1f));
		if (Theme.gloss) {
			Draw.gloss(g, 3, iy + 1, SIDEBAR - 3, TAB_H - 2, 0.6f * Theme.opacity);
		}

		for (int i = 0; i < cats.length; i++) {
			Category c = cats[i];
			float ty = tabTop(i);
			boolean over = inRect(mx, my, 1, ty, SIDEBAR - 1, TAB_H);
			float hv = tabHover[i].target(over).update(dt);
			boolean selected = c == category;
			float bob = -2f * Motion.outBack(hv);
			int color = selected ? Theme.accent(i / (float) cats.length) : ColorUtil.lerp(Theme.textDim, Theme.text, hv);
			Draw.icon(g, c.getIcon(), (SIDEBAR - 16) / 2f + 0.5f, ty + 6 + bob, 16, color);

			// one LED pip per module, lit when it's on
			List<Module> mods = ModuleManager.byCategory(c);
			int total = 0;
			int on = 0;
			for (Module m : mods) {
				if (m.isToggleable()) {
					total++;
					if (m.isEnabled()) {
						on++;
					}
				}
			}
			// big categories wrap onto a second row instead of becoming a dotted line
			int perRow = total > 6 ? (total + 1) / 2 : Math.max(1, total);
			for (int k = 0; k < total; k++) {
				int row = k / perRow;
				int col = k % perRow;
				int inRow = Math.min(perRow, total - row * perRow);
				float px = SIDEBAR / 2f - (inRow * 4 - 1) / 2f + 0.5f;
				boolean lit = k < on;
				Draw.rect(g, px + col * 4, ty + (total > 6 ? 25 : 26) + row * 3, 2, 2,
						lit ? Theme.accent(k / 4f) : ColorUtil.fade(Theme.borderHi, 0.9f));
			}
			if (over) {
				flyoutTab = i;
				wantHand = true;
			}
		}

		float[] pb = panicBox();
		boolean overPanic = inRect(mx, my, pb[0] - 4, pb[1] - 4, pb[2] + 8, pb[3] + 8);
		float hv = buttonHover[2].target(overPanic).update(dt);
		float pulse = hv * (0.5f + 0.5f * (float) Math.sin(Motion.time() * 10));
		Draw.icon(g, Icon.POWER, pb[0] + 2, pb[1] + 2, 12, ColorUtil.lerp(Theme.textDim, Theme.text, Math.max(hv * 0.6f, pulse)));
		if (overPanic) {
			hover("panic", "Panic: switch off every module at once.");
			wantHand = true;
		}
	}

	// ------------------------------------------------------------------ content

	private List<Module> visibleModules() {
		String q = search.getText().trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) {
			return ModuleManager.byCategory(category);
		}
		// name matches first, then descriptions, then modules with a matching setting ("delay")
		List<Module> nameHits = new ArrayList<>();
		List<Module> descHits = new ArrayList<>();
		List<Module> settingHits = new ArrayList<>();
		for (Module m : ModuleManager.all()) {
			if (m.getName().toLowerCase(Locale.ROOT).contains(q)) {
				nameHits.add(m);
			} else if (m.getDescription().toLowerCase(Locale.ROOT).contains(q)) {
				descHits.add(m);
			} else if (m.getSettings().stream().anyMatch(s -> s.getName().toLowerCase(Locale.ROOT).contains(q))) {
				settingHits.add(m);
			}
		}
		nameHits.addAll(descHits);
		nameHits.addAll(settingHits);
		return nameHits;
	}

	private float[] listBox() {
		float cx = SIDEBAR + 1;
		float cw = W - SIDEBAR - 2;
		float boxTop = HEADER + 31;
		float boxH = H - FOOTER - boxTop - 9;
		float listW = (cw - 30) * 0.44f;
		return new float[]{cx + 10, boxTop, listW, boxH};
	}

	private float[] settingsBox() {
		float[] l = listBox();
		float x = l[0] + l[2] + 10;
		return new float[]{x, l[1], W - 11 - x, l[3]};
	}

	private void drawContent(GuiGraphicsExtractor g, float mx, float my) {
		float cx = SIDEBAR + 1;
		float cy = HEADER + 1;
		float cw = W - SIDEBAR - 2;
		float sinceSwitch = (Motion.time() - categorySwitchTime) * Motion.speed();
		float sw = Motion.outCubic(Motion.range(sinceSwitch, 0, 0.3f));

		Matrix3x2fStack pose = g.pose();
		float prev = Draw.alpha;
		Draw.alpha = prev * (0.25f + 0.75f * sw);
		pose.pushMatrix();
		pose.translate(0, (1 - sw) * 10 * switchDir);

		boolean searching = !search.getText().isBlank();
		String title = searching ? "search" : category.getDisplayName().toLowerCase(Locale.ROOT);
		Draw.textScaled(g, title, cx + 10, cy + 9, 1.5f, Theme.text);
		float tw = Theme.width(title) * 1.5f;
		List<Module> mods = visibleModules();
		String tag = searching ? mods.size() + " result" + (mods.size() == 1 ? "" : "s") + " for \"" + search.getText().trim() + "\""
				: category.getTagline();
		Draw.text(g, Draw.ellipsize(tag, (int) (cw - tw - 90)), cx + 16 + tw, cy + 13, Theme.textDim);
		int toggleable = 0;
		int on = 0;
		for (Module m : mods) {
			if (m.isToggleable()) {
				toggleable++;
				if (m.isEnabled()) {
					on++;
				}
			}
		}
		if (toggleable > 0) {
			Draw.textRight(g, on + "/" + toggleable + " on", cx + cw - 10, cy + 13, Theme.textDim);
		}
		pose.popMatrix();
		Draw.alpha = prev;

		float[] l = listBox();
		drawModuleList(g, mods, l[0], l[1], l[2], l[3], mx, my, sinceSwitch);
		float[] s = settingsBox();
		if (panel != null) {
			panel.render(g, s[0], s[1], s[2], s[3], mx, my, dt);
			Row row = panel.getHovered();
			if (panel.isResetHovered()) {
				hover("reset", panel.resetTooltip());
				wantHand = true;
			} else if (row != null) {
				hover(row, panel.tooltipFor(row));
				if (row.clickable()) {
					wantHand = true;
				}
			}
		} else {
			drawEmptySettings(g, s[0], s[1], s[2], s[3]);
		}
	}

	static void groupBox(GuiGraphicsExtractor g, float x, float y, float w, float h, String title) {
		Draw.panel(g, x, y, w, h, Theme.a(Theme.panel));
		Draw.panelOutline(g, x, y, w, h, Theme.a(Theme.border));
		float tw = Theme.width(title);
		// the title sits in a gap cut out of the top border, skeet style
		Draw.rect(g, x + 6, y, tw + 6, 1, Theme.a(Theme.bg));
		Draw.text(g, title, x + 9, y - 4, Theme.text);
	}

	private void drawModuleList(GuiGraphicsExtractor g, List<Module> mods, float x, float y, float w, float h,
								float mx, float my, float sinceSwitch) {
		groupBox(g, x, y, w, h, "modules");
		// module count sits in the top border, opposite the title
		String count = Integer.toString(mods.size());
		float cw = Theme.width(count);
		Draw.rect(g, x + w - cw - 12, y, cw + 6, 1, Theme.a(Theme.bg));
		Draw.text(g, count, x + w - cw - 9, y - 4, Theme.textDim);

		float top = y + 8;
		float bottom = y + h - 4;
		float content = mods.size() * ROW_H;
		float viewH = bottom - top;
		float maxScroll = Math.max(0, content - viewH);
		if (cursor >= mods.size()) {
			cursor = mods.size() - 1;
		}
		if (keyboardNav && cursor >= 0) {
			// keep the cursor row in view
			float rowTop = cursor * ROW_H;
			if (rowTop < listScrollTarget) {
				listScrollTarget = rowTop;
			} else if (rowTop + ROW_H > listScrollTarget + viewH) {
				listScrollTarget = rowTop + ROW_H - viewH;
			}
		}
		listScrollTarget = Math.max(0, Math.min(maxScroll, listScrollTarget));
		float sc = listScroll.target(listScrollTarget).update(dt);
		boolean mouseInList = my >= top && my < bottom;
		float time = Motion.time();
		String query = search.getText().trim().toLowerCase(Locale.ROOT);

		Matrix3x2fStack pose = g.pose();
		g.enableScissor(Math.round(x + 1), Math.round(top - 1), Math.round(x + w - 1), Math.round(bottom));
		for (int i = 0; i < mods.size(); i++) {
			Module m = mods.get(i);
			RowState st = state(m);
			float ry = top + i * ROW_H - sc;
			float rx = x + 4;
			float rw = w - 8;
			boolean over = mouseInList && inRect(mx, my, rx, ry, rw, ROW_H - 1);
			float hv = st.hover.target(over).update(dt);
			float en = st.enabled.target(m.isEnabled()).update(dt);
			float se = st.selected.target(panel != null && panel.getModule() == m).update(dt);
			if (ry + ROW_H < top || ry > bottom) {
				continue;
			}
			float appear = Motion.outCubic(Motion.range(sinceSwitch, 0.03f * i, 0.24f + 0.03f * i));
			float prev = Draw.alpha;
			Draw.alpha = prev * appear;
			pose.pushMatrix();
			pose.translate((1 - appear) * -14, 0);

			float rh = ROW_H - 2;
			Draw.rect(g, rx, ry, rw, rh, ColorUtil.fade(Theme.panel2, se));
			Draw.rect(g, rx, ry, rw * Motion.outCubic(hv), rh, ColorUtil.fade(Theme.panel2, 0.85f * hv));
			float bh = rh * Motion.clamp01(Motion.outBack(se));
			Draw.rect(g, rx, ry + (rh - bh) / 2f, 2, bh, Theme.accent((ry - top) / Math.max(1, bottom - top)));

			if (m.isToggleable()) {
				float bx = rx + 7;
				float by = ry + 4;
				Draw.rect(g, bx, by, 10, 10, Theme.panel2);
				Draw.outline(g, bx, by, 10, 10, ColorUtil.lerp(Theme.border, Theme.borderHi, Math.max(hv, en)));
				float fs = Math.min(8, 8 * Motion.outBack(en));
				if (fs > 0.3f) {
					Draw.gradientV(g, bx + 5 - fs / 2f, by + 5 - fs / 2f, fs, fs, Theme.accent(0.3f),
							ColorUtil.lerp(Theme.accent(0.3f), Theme.panel, 0.45f));
				}
			} else {
				Draw.icon(g, Icon.GEAR, rx + 7, ry + 4, 10, ColorUtil.lerp(Theme.textDim, Theme.text, Math.max(hv, se)));
			}

			// right-hand side first, so the name knows how much room it has
			float right = rx + rw - 4;
			Draw.icon(g, Icon.CHEVRON, right - 8 + se * 2, ry + 5, 8, ColorUtil.fade(Theme.text, 0.2f + 0.8f * Math.max(hv, se)));
			right -= 13;
			if (binding == m) {
				int dots = (int) ((time - bindingSince) * 4) % 4;
				String prompt = "press a key" + ".".repeat(dots);
				float pw = Theme.width("press a key...") + 6;
				Draw.panel(g, right - pw, ry + 3, pw, 11, Theme.accent());
				Draw.text(g, prompt, right - pw + 3, ry + 5, Theme.onAccent(), false);
				right -= pw + 5;
			} else if (m.getKey() > 0) {
				String key = BindRow.keyName(m.getKey());
				float kw = Theme.width(key) + 6;
				Draw.panelOutline(g, right - kw, ry + 3, kw, 11, ColorUtil.fade(Theme.border, 0.9f));
				Draw.text(g, key, right - kw + 3, ry + 5, ColorUtil.fade(Theme.textDim, 0.9f));
				right -= kw + 5;
			}
			if (!query.isEmpty()) {
				String cat = m.getCategory().getDisplayName().toLowerCase(Locale.ROOT);
				Draw.textRight(g, cat, right, ry + 5, ColorUtil.fade(Theme.textDim, 0.6f));
				right -= Theme.width(cat) + 5;
			}
			if (en > 0.05f) {
				// tiny equalizer while the module is on
				for (int b = 0; b < 3; b++) {
					float level = 0.5f + 0.5f * (float) Math.sin(time * (7 + b * 2.3f) + b * 1.7f + i);
					float barH = (2 + 6 * level) * en;
					Draw.rect(g, right - 9 + b * 3, ry + 13 - barH, 2, barH, ColorUtil.fade(Theme.accent(b / 3f), en));
				}
				right -= 13;
			}

			float nameX = rx + 23 + hv * 2;
			int room = (int) (right - nameX - 2);
			String name = Draw.ellipsize(m.getName(), room);
			int nameColor = ColorUtil.lerp(Theme.textDim, Theme.text, Math.max(en, Math.max(hv, se)));
			int match = query.isEmpty() ? -1 : m.getName().toLowerCase(Locale.ROOT).indexOf(query);
			boolean highlight = match >= 0 && match + query.length() <= name.length();
			if (highlight) {
				// three pieces (before, match, after) so the matched letters are drawn exactly once
				String before = name.substring(0, match);
				String hit = name.substring(match, match + query.length());
				float hx = nameX + Theme.width(before);
				float hw = Theme.width(hit);
				// the glyph advance already includes a 1px gap on the right; pad the left by the same so the box
				// doesn't cover the first column of the next letter
				Draw.rect(g, hx - 1, ry + 3, hw + 1, 11, Theme.accentBase);
				Draw.text(g, before, nameX, ry + 5, nameColor);
				Draw.text(g, hit, hx, ry + 5, Theme.onAccent(), false);
				Draw.text(g, name.substring(match + query.length()), hx + hw, ry + 5, nameColor);
			} else {
				Draw.text(g, name, nameX, ry + 5, nameColor);
			}
			// live status from the module ("Spin", a target's name...) trails the name while it's on
			String info = en > 0.05f ? m.displayInfo() : null;
			if (info != null) {
				float ix = nameX + Theme.width(name) + 5;
				int infoRoom = (int) (right - ix - 2);
				if (infoRoom > 16) {
					Draw.text(g, Draw.ellipsize(info.toLowerCase(Locale.ROOT), infoRoom), ix, ry + 5,
							ColorUtil.fade(Theme.textDim, 0.75f * en));
				}
			}

			float rt = (time - st.rippleTime) * Motion.speed();
			if (rt < 0.5f) {
				float k = Motion.outCubic(rt / 0.5f);
				float r = k * rw * 0.5f;
				Draw.outline(g, st.rippleX - r, ry + rh / 2f - 1 - r * 0.25f, r * 2, 2 + r * 0.5f, ColorUtil.fade(Theme.accent(), 1 - k));
			}
			float shine = (time - st.shineTime) * Motion.speed();
			if (shine < 0.55f) {
				// a slanted glint sweeps across the row when it switches on
				float k = Motion.inOutCubic(shine / 0.55f);
				float bandX = rx - 30 + (rw + 50) * k;
				int glint = ColorUtil.fade(Theme.text, 0.28f * (1 - k * 0.6f));
				for (int yy = 0; yy < (int) rh; yy++) {
					float sx = Math.max(rx, bandX - yy * 0.7f);
					float ex = Math.min(rx + rw, bandX - yy * 0.7f + 12);
					if (ex > sx) {
						Draw.rect(g, sx, ry + yy, ex - sx, 1, glint);
					}
				}
			}
			pose.popMatrix();
			Draw.alpha = prev;

			if (over) {
				hover(m, m.getDescription() + (m.isToggleable()
						? "\nleft: toggle   right: settings   middle: bind" : "\nclick: settings"));
				wantHand = true;
			}
		}

		// keyboard cursor: marching ants that glide between rows
		if (cursor >= 0 && cursor < mods.size()) {
			float target = top + cursor * ROW_H - sc;
			if (!keyboardNav) {
				cursorY.snap(target);
			}
			float cy = cursorY.target(target).update(dt);
			if (keyboardNav) {
				// stop short of the scrollbar so the two don't merge into one line
				Draw.dashedOutline(g, x + 3, cy - 1, maxScroll > 0 ? w - 9 : w - 6, ROW_H, Theme.accent(), time * 12);
			}
		}
		g.disableScissor();

		if (mods.isEmpty()) {
			Draw.textCentered(g, "nothing here.", x + w / 2f, y + h / 2f - 10, Theme.textDim);
			Draw.textCentered(g, "try \"twerk\"", x + w / 2f, y + h / 2f + 2, ColorUtil.fade(Theme.textDim, 0.6f));
		}
		if (maxScroll > 0) {
			float thumbH = Math.max(12, viewH * viewH / content);
			float thumbY = top + (viewH - thumbH) * (sc / maxScroll);
			Draw.rect(g, x + w - 4, thumbY, 2, thumbH, ColorUtil.fade(Theme.text, 0.35f));
			// fade rows into the box edges so cut-off rows don't look broken (over their text, too)
			g.nextStratum();
			if (sc > 1) {
				Draw.gradientV(g, x + 1, top - 1, w - 6, 8, Theme.a(Theme.panel), ColorUtil.withAlpha(Theme.panel, 0));
			}
			if (sc < maxScroll - 1) {
				Draw.gradientV(g, x + 1, bottom - 8, w - 6, 8, ColorUtil.withAlpha(Theme.panel, 0), Theme.a(Theme.panel));
			}
		}
	}

	/** Category name tag that slides out of the sidebar while a tab is hovered. */
	private void drawFlyout(GuiGraphicsExtractor g) {
		if (flyoutTab >= 0) {
			shownFlyout = flyoutTab;
		}
		float f = flyout.target(flyoutTab >= 0).update(dt);
		if (f < 0.01f || shownFlyout < 0) {
			return;
		}
		g.nextStratum();
		Category c = Category.values()[shownFlyout];
		int total = 0;
		int on = 0;
		for (Module m : ModuleManager.byCategory(c)) {
			if (m.isToggleable()) {
				total++;
				on += m.isEnabled() ? 1 : 0;
			}
		}
		String name = c.getDisplayName().toLowerCase(Locale.ROOT);
		String stat = total > 0 ? on + "/" + total : "";
		float tw = Theme.width(name) + (stat.isEmpty() ? 0 : Theme.width(stat) + 8) + 12;
		float th = 15;
		float fx = SIDEBAR + 4;
		float fy = tabTop(shownFlyout) + (TAB_H - th) / 2f;
		float e = Motion.outCubic(f);
		float prev = Draw.alpha;
		Draw.alpha = prev * Motion.clamp01(f * 2);
		g.enableScissor(Math.round(fx - 4), Math.round(fy - 2), Math.round(fx + tw * e + 2), Math.round(fy + th + 4));
		Draw.ditherShadow(g, fx, fy, tw, th, 2, ColorUtil.withAlpha(0x000000, 150));
		Draw.panel(g, fx, fy, tw, th, Theme.panel2);
		Draw.panelOutline(g, fx, fy, tw, th, Theme.borderHi);
		// little pixel arrow pointing back at the tab
		Draw.rect(g, fx - 1, fy + 5, 1, 5, Theme.borderHi);
		Draw.rect(g, fx - 2, fy + 6, 1, 3, Theme.borderHi);
		Draw.rect(g, fx - 3, fy + 7, 1, 1, Theme.borderHi);
		Draw.rect(g, fx + 1, fy + 1, 1, th - 2, Theme.accent());
		Draw.text(g, name, fx + 6, fy + 4, Theme.text);
		if (!stat.isEmpty()) {
			Draw.text(g, stat, fx + 6 + Theme.width(name) + 8, fy + 4, Theme.textDim);
		}
		g.disableScissor();
		Draw.alpha = prev;
	}

	private void drawEmptySettings(GuiGraphicsExtractor g, float x, float y, float w, float h) {
		groupBox(g, x, y, w, h, "settings");
		float cx = x + w / 2f;
		float cy = y + h / 2f;
		// a little pixel mouse with its right button blinking
		float t = Motion.time();
		boolean blink = (int) (t * 2) % 2 == 0;
		float mw = 14, mh = 20;
		float mxp = cx - mw / 2f;
		float myp = cy - 30;
		Draw.outline(g, mxp, myp, mw, mh, Theme.textDim);
		Draw.rect(g, mxp + mw / 2f, myp, 1, 8, Theme.textDim);
		Draw.rect(g, mxp, myp + 8, mw, 1, Theme.textDim);
		if (blink) {
			Draw.rect(g, mxp + mw / 2f + 1, myp + 1, mw / 2f - 2, 7, Theme.accent());
		}
		Draw.textCentered(g, "right click a module", cx, cy, Theme.text);
		Draw.textCentered(g, "to tweak its settings", cx, cy + 11, Theme.textDim);
	}

	// ------------------------------------------------------------------ footer

	private void drawFooter(GuiGraphicsExtractor g) {
		float fy = H - FOOTER;
		Draw.rect(g, 1, fy, W - 2, 1, Theme.a(Theme.border));
		Draw.rect(g, 1, fy + 1, W - 2, FOOTER - 2, Theme.a(Theme.panel));
		String user = minecraft.getUser().getName().toLowerCase(Locale.ROOT);
		String prompt = user + "@troll:~$ ";
		float px = 7;
		Draw.text(g, prompt, px, fy + 4, Theme.accent(0.15f));
		px += Theme.width(prompt);
		String line = TerminalLog.current();
		String typed = line.substring(0, TerminalLog.typed(60 * Motion.speed()));
		String right = LocalTime.now().format(CLOCK) + "  " + minecraft.getFps() + "fps";
		float maxW = W - 24 - px - Theme.width(right);
		typed = Draw.ellipsize(typed, (int) maxW);
		Draw.text(g, typed, px, fy + 4, Theme.text);
		if ((int) (Motion.time() * 2.2f) % 2 == 0) {
			Draw.rect(g, px + Theme.width(typed) + 1, fy + 3, 5, 9, ColorUtil.fade(Theme.text, 0.75f));
		}
		Draw.textRight(g, right, W - 7, fy + 4, Theme.textDim);
	}

	// ------------------------------------------------------------------ tooltip

	private void hover(Object key, String text) {
		hoverKey = key;
		hoverText = text;
	}

	private Object lastHoverKey;

	private void drawTooltip(GuiGraphicsExtractor g, float mx, float my) {
		ClickGuiModule gui = ModuleManager.get(ClickGuiModule.class);
		float time = Motion.time();
		if (hoverKey != lastHoverKey) {
			lastHoverKey = hoverKey;
			hoverSince = time;
		}
		boolean show = gui.tooltips.get() && hoverKey != null && hoverText != null && time - hoverSince > 0.45f && !dragging;
		if (show && !hoverText.equals(shownTooltip)) {
			shownTooltip = hoverText;
			shownTooltipSince = time;
		}
		float a = tooltipAnim.target(show).update(dt);
		if (a <= 0.01f || shownTooltip == null) {
			return;
		}
		List<String> lines = new ArrayList<>();
		for (String part : shownTooltip.split("\n")) {
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
		float minX = -winX / scale + 2;
		float maxX = (width - winX) / scale - 2;
		float maxY = (height - winY) / scale - 2;
		if (bx + bw > maxX) {
			bx = mx - bw - 6;
		}
		bx = Math.max(minX, bx);
		if (by + bh > maxY) {
			by = my - bh - 6;
		}
		float prev = Draw.alpha;
		Draw.alpha = a;
		g.pose().pushMatrix();
		g.pose().translate(0, (1 - a) * 4);
		Draw.ditherShadow(g, bx, by, bw, bh, 3, ColorUtil.withAlpha(0x000000, 160));
		Draw.panel(g, bx, by, bw, bh, Theme.panel2);
		Draw.panelOutline(g, bx, by, bw, bh, Theme.borderHi);
		Draw.rect(g, bx + 1, by + 1, 1, bh - 2, Theme.accent());
		// typewriter reveal, fast enough to not get in the way
		int budget = (int) ((time - shownTooltipSince) * 220 * Motion.speed());
		float ty = by + 4;
		for (int i = 0; i < lines.size(); i++) {
			String l = lines.get(i);
			if (budget <= 0) {
				break;
			}
			String shown = l.substring(0, Math.min(l.length(), budget));
			budget -= l.length();
			Draw.text(g, shown, bx + 6, ty, i == 0 ? Theme.text : Theme.textDim);
			ty += 10;
		}
		g.pose().popMatrix();
		Draw.alpha = prev;
	}

	// ------------------------------------------------------------------ actions

	private void selectCategory(Category c) {
		if (c == category) {
			return;
		}
		switchDir = c.ordinal() > category.ordinal() ? 1 : -1;
		category = c;
		GuiState.category = c;
		categorySwitchTime = Motion.time();
		listScrollTarget = 0;
		cursor = keyboardNav ? 0 : -1;
		binding = null;
		search.setText("");
		search.setFocused(false);
		Module remembered = ModuleManager.byName(LAST_SELECTED.getOrDefault(c, ""));
		panel = remembered != null ? new SettingsPanel(remembered) : null;
		Sounds.click();
		TerminalLog.push("cd ~/" + c.getDisplayName().toLowerCase(Locale.ROOT));
	}

	private void selectModule(Module m) {
		if (panel != null && panel.getModule() == m) {
			panel.unfocusAll();
			panel = null;
			LAST_SELECTED.remove(m.getCategory());
			TerminalLog.push("exit");
		} else {
			panel = new SettingsPanel(m);
			LAST_SELECTED.put(m.getCategory(), m.getName());
			GuiState.selectedModule = m.getName();
			TerminalLog.push("vim ~/" + m.getCategory().getDisplayName().toLowerCase(Locale.ROOT) + "/"
					+ m.getName().toLowerCase(Locale.ROOT) + ".cfg");
		}
		Sounds.click();
	}

	private void panic() {
		int count = 0;
		for (Module m : ModuleManager.all()) {
			if (m.isToggleable() && m.isEnabled() && m.getCategory() != Category.CLIENT) {
				m.setEnabled(false);
				count++;
			}
		}
		Sounds.toggle(false);
		TerminalLog.push(count == 0 ? "panic: nothing to turn off" : "panic: killed " + count + " module" + (count == 1 ? "" : "s"));
	}

	private Module moduleAt(float mx, float my) {
		float[] l = listBox();
		float top = l[1] + 8;
		float bottom = l[1] + l[3] - 4;
		if (!inRect(mx, my, l[0] + 4, top, l[2] - 8, bottom - top)) {
			return null;
		}
		int index = (int) ((my - top + listScroll.get()) / ROW_H);
		List<Module> mods = visibleModules();
		return index >= 0 && index < mods.size() ? mods.get(index) : null;
	}

	/** Window-local point to screen space. The showcase recorder uses these helpers to aim its cursor. */
	public double[] toScreen(float localX, float localY) {
		return new double[]{winX + localX * scale, winY + localY * scale};
	}

	public double[] paletteCenter() {
		float[] b = paletteBox();
		return toScreen(b[0] + b[2] / 2f, b[1] + b[3] / 2f);
	}

	public double[] searchCenter() {
		float[] b = searchBox();
		return toScreen(b[0] + b[2] / 2f, b[1] + b[3] / 2f);
	}

	/** A point on the settings row with this label, {@code fraction} of the way across it; null if it isn't shown. */
	public double[] settingsRow(String label, float fraction) {
		if (panel == null) {
			return null;
		}
		float[] local = panel.rowPoint(label, fraction);
		return local == null ? null : toScreen(local[0], local[1]);
	}

	/** Screen-space centre of a sidebar tab. Used by the dev smoke test. */
	public double[] tabCenter(int index) {
		return new double[]{winX + SIDEBAR / 2f * scale, winY + (tabTop(index) + TAB_H / 2f) * scale};
	}

	/** Screen-space centre of a module row. Used by the dev smoke test to click things. */
	public double[] moduleRowCenter(int index) {
		float[] l = listBox();
		float rowY = l[1] + 8 + index * ROW_H + ROW_H / 2f - listScroll.get();
		return new double[]{winX + (l[0] + l[2] / 2f) * scale, winY + rowY * scale};
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (closing || progress < 0.5f) {
			return true;
		}
		float mx = lx(event.x());
		float my = ly(event.y());
		int button = event.button();
		Module wasBinding = binding;
		binding = null;

		float[] s = settingsBox();
		// the panel's reset button pokes out of its top border
		boolean inPanel = panel != null && inRect(mx, my, s[0], s[1] - 6, s[2], s[3] + 6);
		if (panel != null && !inPanel) {
			panel.unfocusAll();
		}

		float[] sb = searchBox();
		if (inRect(mx, my, sb[0], sb[1], sb[2], sb[3])) {
			if (button == 1) {
				search.setText("");
			}
			search.setFocused(true);
			Sounds.click();
			return true;
		}
		search.setFocused(false);

		float[] cb = closeBox();
		if (inRect(mx, my, cb[0], cb[1], cb[2], cb[3])) {
			onClose();
			return true;
		}
		float[] pb = paletteBox();
		if (inRect(mx, my, pb[0], pb[1], pb[2], pb[3])) {
			ThemeModule t = Theme.settings();
			t.preset.cycle(button == 1 ? -1 : 1);
			themeFlashTime = Motion.time();
			Sounds.toggle(true);
			TerminalLog.push("theme --preset " + t.preset.get().toLowerCase(Locale.ROOT));
			return true;
		}
		Category[] cats = Category.values();
		for (int i = 0; i < cats.length; i++) {
			if (inRect(mx, my, 1, tabTop(i), SIDEBAR - 1, TAB_H)) {
				selectCategory(cats[i]);
				return true;
			}
		}
		float[] pk = panicBox();
		if (inRect(mx, my, pk[0] - 4, pk[1] - 4, pk[2] + 8, pk[3] + 8)) {
			panic();
			return true;
		}

		Module m = moduleAt(mx, my);
		if (m != null) {
			cursor = visibleModules().indexOf(m);
			if (button == 2 && m.isToggleable()) {
				if (wasBinding != m) {
					binding = m;
					bindingSince = Motion.time();
					TerminalLog.push("bind " + m.getName().toLowerCase(Locale.ROOT) + " ... (esc to clear)");
				}
				Sounds.click();
			} else if (button == 0 && m.isToggleable()) {
				m.toggle();
				RowState st = state(m);
				st.rippleTime = Motion.time();
				st.rippleX = mx;
				if (m.isEnabled()) {
					st.shineTime = Motion.time();
				}
				Sounds.toggle(m.isEnabled());
				TerminalLog.push(m.getName().toLowerCase(Locale.ROOT) + (m.isEnabled() ? " --enable" : " --disable"));
			} else if (button == 1 || !m.isToggleable()) {
				selectModule(m);
			}
			return true;
		}

		if (inPanel && panel.mouseClicked(mx, my, button)) {
			return true;
		}

		if (button == 0 && inRect(mx, my, 0, 0, W, HEADER)) {
			float now = Motion.time();
			if (now - lastHeaderClick < 0.3f) {
				GuiState.centerX = 0.5f;
				GuiState.centerY = 0.5f;
				lastHeaderClick = -10;
				Sounds.click();
				ConfigManager.markDirty();
				return true;
			}
			lastHeaderClick = now;
			dragging = true;
			dragDX = (float) event.x() - winX;
			dragDY = (float) event.y() - winY;
			return true;
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging) {
			dragging = false;
			ConfigManager.markDirty();
		}
		if (panel != null) {
			panel.mouseReleased(lx(event.x()), ly(event.y()), event.button());
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			float nx = (float) event.x() - dragDX;
			float ny = (float) event.y() - dragDY;
			GuiState.centerX = Motion.clamp01((nx + W * scale / 2f) / width);
			GuiState.centerY = Motion.clamp01((ny + H * scale / 2f) / height);
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		float mx = lx(mouseX);
		float my = ly(mouseY);
		if (minecraft.hasControlDown()) {
			ClickGuiModule gui = ModuleManager.get(ClickGuiModule.class);
			gui.scale.increment(scrollY > 0 ? 1 : -1);
			TerminalLog.push("scale " + gui.scale.display());
			Sounds.tick();
			return true;
		}
		float[] l = listBox();
		if (inRect(mx, my, l[0], l[1], l[2], l[3])) {
			listScrollTarget -= (float) scrollY * ROW_H;
			return true;
		}
		if (panel != null) {
			panel.mouseScrolled(mx, my, scrollY);
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (closing) {
			return true;
		}
		int key = event.key();
		if (binding != null) {
			Module m = binding;
			binding = null;
			if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) {
				m.setKey(GLFW.GLFW_KEY_UNKNOWN);
				TerminalLog.push("unbind " + m.getName().toLowerCase(Locale.ROOT));
			} else {
				m.setKey(key);
				TerminalLog.push("bind " + m.getName().toLowerCase(Locale.ROOT) + " " + BindRow.keyName(key));
			}
			Sounds.toggle(true);
			return true;
		}
		if (panel != null && panel.isCapturing()) {
			if (panel.keyPressed(event)) {
				return true;
			}
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				panel.unfocusAll();
			}
			return true;
		}
		if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
			moveCursor(key == GLFW.GLFW_KEY_DOWN ? 1 : -1);
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
			if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
				List<Module> hits = visibleModules();
				if (!hits.isEmpty()) {
					selectModule(hits.get(cursor >= 0 && cursor < hits.size() ? cursor : 0));
				}
				return true;
			}
			search.keyPressed(event);
			return true;
		}
		boolean bindKey = key == ModuleManager.get(ClickGuiModule.class).getKey();
		if (key == GLFW.GLFW_KEY_ESCAPE || (bindKey && bindArmed)) {
			onClose();
			return true;
		}
		if (event.hasControlDown() && key == GLFW.GLFW_KEY_F) {
			search.setFocused(true);
			return true;
		}
		Category[] cats = Category.values();
		if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_TAB) {
			int dir = key == GLFW.GLFW_KEY_LEFT || (key == GLFW.GLFW_KEY_TAB && event.hasShiftDown()) ? -1 : 1;
			keyboardNav = true;
			selectCategory(cats[Math.floorMod(category.ordinal() + dir, cats.length)]);
			return true;
		}
		List<Module> mods = visibleModules();
		Module focused = cursor >= 0 && cursor < mods.size() ? mods.get(cursor) : null;
		if (key == GLFW.GLFW_KEY_SPACE && focused != null && focused.isToggleable()) {
			focused.toggle();
			RowState st = state(focused);
			st.rippleTime = Motion.time();
			float[] l = listBox();
			st.rippleX = l[0] + l[2] / 2f;
			if (focused.isEnabled()) {
				st.shineTime = Motion.time();
			}
			Sounds.toggle(focused.isEnabled());
			return true;
		}
		if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && focused != null) {
			selectModule(focused);
			return true;
		}
		return true;
	}

	private void moveCursor(int dir) {
		List<Module> mods = visibleModules();
		if (mods.isEmpty()) {
			cursor = -1;
			return;
		}
		if (!keyboardNav && cursor < 0) {
			cursor = dir > 0 ? 0 : mods.size() - 1;
		} else {
			cursor = Math.floorMod(cursor + dir, mods.size());
		}
		keyboardNav = true;
		Sounds.tick();
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (closing) {
			return true;
		}
		String chars = event.codepointAsString();
		if (panel != null && panel.isCapturing()) {
			return panel.charTyped(chars);
		}
		if (!search.isFocused()) {
			if (!Character.isLetterOrDigit(event.codepoint())) {
				return true;
			}
			// type-to-search: any letter jumps straight into the search box
			search.setFocused(true);
		}
		return search.charTyped(chars);
	}
}
