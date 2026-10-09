package com.trollclient.gui.macro;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.trollclient.gui.Anim;
import com.trollclient.gui.Backdrop;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Motion;
import com.trollclient.gui.ScreenFx;
import com.trollclient.gui.Sounds;
import com.trollclient.gui.Theme;
import com.trollclient.gui.Tooltip;
import com.trollclient.gui.clickgui.SettingsPanel;
import com.trollclient.gui.clickgui.TerminalLog;
import com.trollclient.gui.clickgui.components.BindRow;
import com.trollclient.gui.clickgui.components.ButtonRow;
import com.trollclient.gui.clickgui.components.Row;
import com.trollclient.gui.clickgui.components.ToggleRow;
import com.trollclient.macro.Macro;
import com.trollclient.macro.MacroManager;
import com.trollclient.macro.MacroRun;
import com.trollclient.macro.MacroStep;
import com.trollclient.macro.StepType;
import com.trollclient.macro.Steps;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.ClickGuiModule;
import com.trollclient.module.client.ThemeModule;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The macro editor, in the ClickGUI's clothes: macros down the left, the
 * chosen macro's steps in the middle (drag to reorder, right click to switch
 * one off), and on the right the macro's settings, the selected step's
 * settings (the same rows as module settings), or the step palette.
 */
public class MacroEditorScreen extends Screen {
	private enum Pane {
		MACRO("macro"), STEP("step"), ADD("+ add");

		final String label;

		Pane(String label) {
			this.label = label;
		}
	}

	private static final float HEADER = 26;
	private static final float FOOTER = 14;
	private static final float BAR = 20;
	private static final float ROW = 14;
	private static final float CARD = 25;
	private static final float TABS = 16;
	private static final int UNDO_LIMIT = 40;

	/** A clickable box laid out this frame (action bar, tabs, palette...). */
	private record Hit(float x, float y, float w, float h, Runnable left, Runnable right) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final Screen parent;
	private final Backdrop backdrop = new Backdrop();
	private final Tooltip tooltip = new Tooltip();
	private final Anim open = new Anim(0, 7);
	private final float openedAt = Motion.time();
	private final Map<Object, Anim> hovers = new HashMap<>();
	private final Map<Object, Float> pressed = new HashMap<>();
	private final List<Hit> hits = new ArrayList<>();
	private final Deque<JsonObject> undo = new ArrayDeque<>();
	private final Anim macroScroll = new Anim(16);
	private final Anim stepScroll = new Anim(16);
	private final Anim paletteScroll = new Anim(16);
	private final Anim stepSel = new Anim(20);

	private Macro macro;
	private MacroStep step;
	private Pane pane = Pane.MACRO;
	private SettingsPanel panel;
	private Object panelFor;
	private String category = Steps.CATEGORIES.get(0);
	private float macroScrollTarget, stepScrollTarget, paletteScrollTarget;
	private int pressIndex = -1;
	private float pressY;
	private boolean dragging;
	private float dragY;
	private boolean closing;
	private long lastNanos;
	private float dt;
	private boolean wantHand;
	private boolean stepSelSnapped;

	// window and column geometry, refreshed every frame
	private float wx, wy, ww, wh;
	private float bodyTop, bodyBottom;
	private float leftX, leftW, midX, midW, rightX, rightW;

	public MacroEditorScreen(Screen parent) {
		super(Component.literal("Macros"));
		this.parent = parent;
		List<Macro> all = MacroManager.macros();
		selectMacro(all.isEmpty() ? null : all.get(0));
	}

	/** Opens the editor over whatever is showing; closing it comes back there. */
	public static void open() {
		open(null);
	}

	public static void open(Macro select) {
		Minecraft mc = Minecraft.getInstance();
		Screen current = mc.gui.screen();
		MacroEditorScreen editor = new MacroEditorScreen(current instanceof MacroEditorScreen e ? e.parent : current);
		if (select != null) {
			editor.selectMacro(select);
		}
		mc.gui.setScreen(editor);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (!closing) {
			closing = true;
			Sounds.click();
		}
	}

	@Override
	public void removed() {
		MacroManager.setEditing(null);
		MacroManager.save();
	}

	// ------------------------------------------------------------------ selection and editing

	private void selectMacro(Macro m) {
		if (m != macro) {
			undo.clear();
			stepScrollTarget = 0;
			stepSelSnapped = false;
		}
		macro = m;
		step = null;
		pane = Pane.MACRO;
		MacroManager.setEditing(m);
	}

	private void selectStep(MacroStep s) {
		step = s;
		pane = s == null ? Pane.MACRO : Pane.STEP;
	}

	private int stepIndex() {
		return macro == null || step == null ? -1 : macro.steps.indexOf(step);
	}

	/** Remembers the macro's steps before a change, for ctrl+z. */
	private void snapshot() {
		if (macro == null) {
			return;
		}
		undo.push(macro.toJson());
		while (undo.size() > UNDO_LIMIT) {
			undo.removeLast();
		}
		MacroManager.markDirty();
	}

	private void undo() {
		if (macro == null || undo.isEmpty()) {
			return;
		}
		int at = stepIndex();
		MacroManager.stop(macro);
		macro.readSteps(undo.pop());
		selectStep(at >= 0 && at < macro.steps.size() ? macro.steps.get(at) : null);
		MacroManager.markDirty();
		Sounds.click();
		TerminalLog.push("git checkout -- steps");
	}

	private void addStep(StepType type) {
		if (macro == null) {
			macro = MacroManager.create();
			selectMacro(macro);
		}
		snapshot();
		MacroStep s = type.create();
		int at = stepIndex();
		macro.steps.add(at < 0 ? macro.steps.size() : at + 1, s);
		selectStep(s);
		scrollStepIntoView();
		Sounds.toggle(true);
		TerminalLog.push("macro --add " + type.id());
	}

	private void deleteStep() {
		int at = stepIndex();
		if (at < 0) {
			return;
		}
		snapshot();
		MacroManager.stop(macro);
		macro.steps.remove(at);
		selectStep(macro.steps.isEmpty() ? null : macro.steps.get(Math.min(at, macro.steps.size() - 1)));
		Sounds.toggle(false);
		TerminalLog.push("macro --rm step " + (at + 1));
	}

	private void duplicateStep() {
		int at = stepIndex();
		if (at < 0) {
			return;
		}
		snapshot();
		MacroStep copy = step.copy();
		macro.steps.add(at + 1, copy);
		selectStep(copy);
		scrollStepIntoView();
		Sounds.click();
	}

	private void moveStep(int from, int to) {
		if (macro == null || from < 0 || from >= macro.steps.size()) {
			return;
		}
		to = Math.max(0, Math.min(macro.steps.size() - 1, to));
		if (from == to) {
			return;
		}
		snapshot();
		MacroManager.stop(macro);
		MacroStep s = macro.steps.remove(from);
		macro.steps.add(to, s);
		selectStep(s);
		scrollStepIntoView();
		Sounds.tick();
	}

	private void toggleStep(MacroStep s) {
		snapshot();
		s.setEnabled(!s.isEnabled());
		Sounds.toggle(s.isEnabled());
	}

	private void run() {
		if (macro != null) {
			MacroManager.start(macro);
			Sounds.open();
		}
	}

	private void newMacro() {
		selectMacro(MacroManager.create());
		Sounds.toggle(true);
		TerminalLog.push("touch ~/macros/" + macro.getName().replace(' ', '_') + ".json");
	}

	private void deleteMacro() {
		if (macro == null) {
			return;
		}
		int at = MacroManager.macros().indexOf(macro);
		TerminalLog.push("rm ~/macros/" + macro.getName().replace(' ', '_') + ".json");
		MacroManager.remove(macro);
		List<Macro> all = MacroManager.macros();
		selectMacro(all.isEmpty() ? null : all.get(Math.min(at, all.size() - 1)));
	}

	private void scrollStepIntoView() {
		int at = stepIndex();
		if (at < 0) {
			return;
		}
		float view = listBottom() - listTop();
		float rowTop = at * ROW;
		if (rowTop < stepScrollTarget) {
			stepScrollTarget = rowTop;
		} else if (rowTop + ROW > stepScrollTarget + view) {
			stepScrollTarget = rowTop + ROW - view;
		}
	}

	// ------------------------------------------------------------------ layout

	private void layout() {
		ww = Math.min(width - 12, 600);
		wh = Math.min(height - 12, 340);
		wx = (width - ww) / 2f;
		wy = (height - wh) / 2f;
		bodyTop = wy + HEADER + 10;
		bodyBottom = wy + wh - FOOTER - BAR - 6;
		leftX = wx + 8;
		leftW = Math.min(112, ww * 0.21f);
		rightW = Math.min(200, ww * 0.36f);
		rightX = wx + ww - 8 - rightW;
		midX = leftX + leftW + 8;
		midW = rightX - 8 - midX;
	}

	private float listTop() {
		return bodyTop + 8;
	}

	private float listBottom() {
		return bodyBottom - 4;
	}

	private float rightTop() {
		return bodyTop + TABS + 6;
	}

	// ------------------------------------------------------------------ render

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		Theme.update();
		ClickGuiModule gui = ModuleManager.get(ClickGuiModule.class);
		float p = Motion.outCubic(open.get());
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
		float o = open.target(closing ? 0 : 1).update(dt);
		if (closing && o < 0.02f) {
			minecraft.gui.setScreen(parent);
			return;
		}
		layout();
		hits.clear();
		tooltip.reset();
		wantHand = false;
		if (macro != null && !MacroManager.macros().contains(macro)) {
			selectMacro(MacroManager.macros().isEmpty() ? null : MacroManager.macros().get(0));
		}
		syncPanel();

		// the window unrolls from its middle, like the singleplayer and multiplayer menus
		float e = Motion.outCubic(o);
		float visible = Math.max(2, wh * e);
		float prev = Draw.alpha;
		Draw.alpha = Motion.clamp01(o * 1.5f);
		g.enableScissor(Math.round(wx - 8), Math.round(wy + (wh - visible) / 2f), Math.round(wx + ww + 8), Math.round(wy + (wh + visible) / 2f));
		drawFrame(g);
		drawHeader(g, mouseX, mouseY);
		drawMacros(g, mouseX, mouseY);
		drawSteps(g, mouseX, mouseY);
		drawRight(g, mouseX, mouseY);
		drawBar(g, mouseX, mouseY);
		drawFooter(g);
		g.disableScissor();
		if (o < 0.6f && o > 0.05f) {
			Draw.rect(g, wx, wy + (wh - visible) / 2f, ww, 1, ColorUtil.fade(Theme.text, 1 - o));
			Draw.rect(g, wx, wy + (wh + visible) / 2f - 1, ww, 1, ColorUtil.fade(Theme.text, 1 - o));
		}
		Draw.alpha = prev;
		if (dragging) {
			drawDragged(g);
		}
		if (wantHand) {
			g.requestCursor(CursorTypes.POINTING_HAND);
		}
		if (!dragging) {
			tooltip.render(g, mouseX, mouseY, dt, width - 2, height - 2);
		}
		ScreenFx.overlays(g, width, height, e);
	}

	/** Rebuilds the settings panel when what it should show changes. */
	private void syncPanel() {
		if (pane == Pane.STEP && (step == null || macro == null || !macro.steps.contains(step))) {
			selectStep(null);
		}
		Object want = pane == Pane.STEP ? step : pane == Pane.MACRO ? macro : null;
		if (want == panelFor && (want != null) == (panel != null)) {
			return;
		}
		panelFor = want;
		panel = null;
		if (pane == Pane.MACRO && macro != null) {
			Macro m = macro;
			List<Row> rows = new ArrayList<>();
			rows.add(new BindRow("Keybind", "Key that starts the macro (and stops it if it's running)", m::getKey, m::setKey));
			rows.add(new ButtonRow("Run", "Run it now (ctrl+enter)", () -> MacroManager.start(m)));
			rows.add(new ButtonRow("Duplicate", "Copy this macro", () -> selectMacro(MacroManager.duplicate(m))));
			rows.add(new ButtonRow("Delete Macro", "Delete it for good", this::deleteMacro, true, () -> true));
			panel = new SettingsPanel("macro", "Runs from its key, the GUI tools panel, or .macro run " + m.getName().toLowerCase(Locale.ROOT) + ".",
					m.settings(), rows, "rm ~/macros/" + m.getName().replace(' ', '_') + ".json");
		} else if (pane == Pane.STEP && step != null) {
			MacroStep s = step;
			List<Row> rows = new ArrayList<>();
			rows.add(new ToggleRow("Enabled", "Skipped while off (right click it in the list, or space)", s::isEnabled,
					() -> toggleStep(s), () -> true));
			panel = new SettingsPanel(s.type().name(), s.type().description(), s.settings(), rows, "rm step --" + s.type().id());
		}
	}

	private void drawFrame(GuiGraphicsExtractor g) {
		ThemeModule t = Theme.settings();
		if (t.ditherShadow.get()) {
			Draw.ditherShadow(g, wx - 1, wy - 1, ww + 2, wh + 2, 5, ColorUtil.withAlpha(0x000000, 170));
		}
		if (t.glow.get()) {
			Draw.glow(g, wx - 1, wy - 1, ww + 2, wh + 2, Theme.accent(), 8);
		}
		Draw.panel(g, wx - 1, wy - 1, ww + 2, wh + 2, Theme.a(Theme.light ? 0xFF505050 : 0xFF000000));
		Draw.panel(g, wx, wy, ww, wh, Theme.a(Theme.borderHi));
		Draw.rect(g, wx + 1, wy + 1, ww - 2, wh - 2, Theme.a(Theme.bg));
		Draw.gradientH(g, wx + 1, wy + 1, ww - 2, 2, p -> Theme.accent(p / 1000f));
	}

	private void drawHeader(GuiGraphicsExtractor g, int mx, int my) {
		Draw.rect(g, wx + 1, wy + 3, ww - 2, HEADER - 3, Theme.a(Theme.panel));
		Draw.rect(g, wx + 1, wy + HEADER, ww - 2, 1, Theme.a(Theme.border));
		float tagW = Theme.width("TROLL") + 6;
		Draw.panel(g, wx + 8, wy + 8, tagW, 12, Theme.accent(0.05f));
		Draw.text(g, "TROLL", wx + 11, wy + 10, Theme.onAccent(), false);
		Draw.text(g, "macros", wx + tagW + 12, wy + 10, Theme.text);
		String tag = "string steps together, bind them to keys";
		float age = (Motion.time() - openedAt) * Motion.speed();
		String typed = tag.substring(0, (int) Math.min(tag.length(), Math.max(0, age - 0.2f) * 50));
		Draw.text(g, typed, wx + tagW + 18 + Theme.width("macros"), wy + 10, Theme.textDim);

		int running = MacroManager.runs().size();
		if (running > 0) {
			String r = running + " running";
			Draw.textRight(g, r, wx + ww - 28, wy + 10, (int) (Motion.time() * 2) % 2 == 0 ? Theme.accent() : Theme.textDim);
		}
		float cx = wx + ww - 21, cy = wy + 6;
		boolean over = in(mx, my, cx, cy, 14, 14);
		float hv = hover("close", over);
		if (hv > 0.01f) {
			Draw.panel(g, cx, cy, 14, 14, ColorUtil.fade(Theme.panel2, hv));
		}
		Draw.text(g, "x", cx + 4, cy + 3, ColorUtil.lerp(Theme.textDim, Theme.text, hv));
		hit(cx, cy, 14, 14, this::onClose, null, "close", "Close (Esc)", mx, my);
	}

	/** Box with its title cut into the top border, plus an optional count on the right of the border. */
	private void box(GuiGraphicsExtractor g, float x, float y, float w, float h, String title, String count) {
		Draw.panel(g, x, y, w, h, Theme.a(Theme.panel));
		Draw.panelOutline(g, x, y, w, h, Theme.a(Theme.border));
		float tw = Theme.width(title);
		Draw.rect(g, x + 6, y, tw + 6, 1, Theme.a(Theme.bg));
		Draw.text(g, title, x + 9, y - 4, Theme.text);
		if (count != null) {
			float cw = Theme.width(count);
			Draw.rect(g, x + w - cw - 12, y, cw + 6, 1, Theme.a(Theme.bg));
			Draw.text(g, count, x + w - cw - 9, y - 4, Theme.textDim);
		}
	}

	private void drawMacros(GuiGraphicsExtractor g, int mx, int my) {
		List<Macro> all = MacroManager.macros();
		box(g, leftX, bodyTop, leftW, bodyBottom - bodyTop, "macros", Integer.toString(all.size()));
		float top = listTop(), bottom = listBottom();
		float content = (all.size() + 1) * ROW;
		macroScrollTarget = Math.max(0, Math.min(Math.max(0, content - (bottom - top)), macroScrollTarget));
		float sc = macroScroll.target(macroScrollTarget).update(dt);
		g.enableScissor(Math.round(leftX + 1), Math.round(top - 2), Math.round(leftX + leftW - 1), Math.round(bottom));
		for (int i = 0; i <= all.size(); i++) {
			float ry = top + i * ROW - sc;
			if (ry + ROW < top - 2 || ry > bottom) {
				continue;
			}
			boolean over = in(mx, my, leftX + 2, ry, leftW - 4, ROW) && my >= top && my < bottom;
			if (i == all.size()) {
				float hv = hover("new", over);
				Draw.text(g, "+ new macro", leftX + 7 + hv * 2, ry + 3, ColorUtil.lerp(Theme.textDim, Theme.accent(), hv));
				hit(leftX + 2, ry, leftW - 4, ROW, this::newMacro, null, "new", "A new, empty macro", mx, my);
				continue;
			}
			Macro m = all.get(i);
			MacroRun run = MacroManager.runOf(m);
			float hv = hover(m, over);
			boolean sel = m == macro;
			if (sel) {
				Draw.rect(g, leftX + 2, ry, leftW - 4, ROW - 1, Theme.a(Theme.panel2));
				Draw.rect(g, leftX + 2, ry + 2, 2, ROW - 5, Theme.accent());
			} else if (hv > 0.01f) {
				Draw.rect(g, leftX + 2, ry, leftW - 4, ROW - 1, ColorUtil.fade(Theme.panel2, hv * 0.7f));
			}
			if (run != null) {
				Draw.dashedOutline(g, leftX + 2, ry, leftW - 4, ROW - 1, Theme.accent(), Motion.time() * 10);
			}
			String key = m.getKey() > 0 ? BindRow.keyName(m.getKey()) : "";
			float kw = Theme.width(key);
			Draw.textRight(g, key, leftX + leftW - 6, ry + 3, ColorUtil.fade(Theme.textDim, 0.8f));
			Draw.text(g, Draw.ellipsize(m.getName(), (int) (leftW - 18 - kw)), leftX + 8 + hv * 2, ry + 3,
					sel || run != null ? Theme.text : ColorUtil.lerp(Theme.textDim, Theme.text, hv));
			hit(leftX + 2, ry, leftW - 4, ROW, () -> selectMacro(m), () -> MacroManager.toggle(m), m,
					m.getName() + "\n" + m.steps.size() + " steps" + (run != null ? ", running" : "") + ". Right click to "
							+ (run != null ? "stop" : "run") + " it.", mx, my);
		}
		g.disableScissor();
	}

	private void drawSteps(GuiGraphicsExtractor g, int mx, int my) {
		String title = macro == null ? "steps" : "steps: " + Draw.ellipsize(macro.getName().toLowerCase(Locale.ROOT), (int) (midW * 0.55f));
		box(g, midX, bodyTop, midW, bodyBottom - bodyTop, title, macro == null ? null : Integer.toString(macro.steps.size()));
		float top = listTop(), bottom = listBottom();
		if (macro == null) {
			Draw.textCentered(g, "no macro selected", midX + midW / 2f, (top + bottom) / 2f - 4, Theme.textDim);
			return;
		}
		List<MacroStep> steps = macro.steps;
		if (steps.isEmpty()) {
			Draw.textCentered(g, "no steps yet", midX + midW / 2f, (top + bottom) / 2f - 10, Theme.textDim);
			Draw.textCentered(g, "pick one from + add on the right", midX + midW / 2f, (top + bottom) / 2f + 2, ColorUtil.fade(Theme.textDim, 0.7f));
			return;
		}
		float content = steps.size() * ROW;
		float maxScroll = Math.max(0, content - (bottom - top));
		stepScrollTarget = Math.max(0, Math.min(maxScroll, stepScrollTarget));
		float sc = stepScroll.target(stepScrollTarget).update(dt);
		MacroRun run = MacroManager.runOf(macro);
		int sel = stepIndex();

		g.enableScissor(Math.round(midX + 1), Math.round(top - 2), Math.round(midX + midW - 1), Math.round(bottom));
		if (sel >= 0) {
			float target = top + sel * ROW - sc;
			if (!stepSelSnapped) {
				// first frame with a selection: put the highlight there instead of sliding in from the top
				stepSel.snap(target);
				stepSelSnapped = true;
			}
			float sy = stepSel.target(target).update(dt);
			Draw.rect(g, midX + 2, sy, midW - 4, ROW - 1, Theme.a(Theme.panel2));
			Draw.rect(g, midX + 2, sy + 2, 2, ROW - 5, Theme.accent());
		}
		boolean inList = mx >= midX && mx < midX + midW && my >= top && my < bottom;
		for (int i = 0; i < steps.size(); i++) {
			float ry = top + i * ROW - sc;
			if (ry + ROW < top - 2 || ry > bottom) {
				continue;
			}
			MacroStep s = steps.get(i);
			boolean over = inList && !dragging && my >= ry && my < ry + ROW;
			float hv = hover(s, over);
			if (hv > 0.01f && i != sel) {
				Draw.rect(g, midX + 2, ry, (midW - 4) * Motion.outCubic(hv), ROW - 1, ColorUtil.fade(Theme.panel2, 0.6f * hv));
			}
			boolean now = run != null && run.index() == i;
			if (now) {
				Draw.dashedOutline(g, midX + 2, ry, midW - 4, ROW - 1, Theme.accent(), Motion.time() * 10);
			}
			float prev = Draw.alpha;
			if (!s.isEnabled() || (dragging && i == pressIndex)) {
				Draw.alpha = prev * 0.35f;
			}
			String num = String.format(Locale.ROOT, "%02d", i + 1);
			Draw.text(g, now ? ">" : num, midX + 8, ry + 3, now ? Theme.accent() : ColorUtil.fade(Theme.textDim, 0.6f));
			float nx = midX + 10 + Theme.width("00") + 4;
			String name = s.type().name().toLowerCase(Locale.ROOT);
			Draw.text(g, name, nx + hv * 2, ry + 3, i == sel ? Theme.text : ColorUtil.lerp(Theme.textDim, Theme.text, 0.6f + hv * 0.4f));
			float sx = nx + Theme.width(name) + 8;
			String summary = s.isEnabled() ? s.summary() : "off";
			Draw.text(g, Draw.ellipsize(summary, (int) (midX + midW - 8 - sx)), sx, ry + 3, Theme.textDim);
			if (!s.isEnabled()) {
				Draw.rect(g, nx - 1, ry + 7, Theme.width(name) + 2, 1, Theme.textDim);
			}
			Draw.alpha = prev;
			if (over) {
				wantHand = true;
				tooltip.hover(s, s.type().name() + ": " + s.type().description() + "\nDrag to move, right click to switch off, del to delete.");
			}
		}
		if (dragging) {
			int to = dropIndex();
			float ly = top + to * ROW - sc - 1;
			Draw.rect(g, midX + 4, ly, midW - 8, 2, Theme.accent());
		}
		g.disableScissor();
		if (maxScroll > 0) {
			float trackH = bottom - top;
			float thumbH = Math.max(12, trackH * trackH / content);
			Draw.rect(g, midX + midW - 4, top + (trackH - thumbH) * (sc / maxScroll), 2, thumbH, ColorUtil.fade(Theme.text, 0.35f));
		}
	}

	/** Where the dragged step would land. */
	private int dropIndex() {
		int n = macro.steps.size();
		int slot = (int) Math.floor((dragY - listTop() + stepScroll.get()) / ROW + 0.5f);
		return Math.max(0, Math.min(n, slot));
	}

	private void drawDragged(GuiGraphicsExtractor g) {
		if (macro == null || pressIndex < 0 || pressIndex >= macro.steps.size()) {
			return;
		}
		MacroStep s = macro.steps.get(pressIndex);
		g.nextStratum();
		float y = dragY - ROW / 2f;
		Draw.ditherShadow(g, midX + 2, y, midW - 4, ROW, 2, ColorUtil.withAlpha(0x000000, 150));
		Draw.panel(g, midX + 2, y, midW - 4, ROW, Theme.accent());
		Draw.text(g, s.type().name().toLowerCase(Locale.ROOT) + "  " + s.summary(), midX + 10, y + 3, Theme.onAccent());
	}

	private void drawRight(GuiGraphicsExtractor g, int mx, int my) {
		// tabs: macro | step | + add
		Pane[] panes = Pane.values();
		float tw = (rightW - 4) / panes.length;
		for (int i = 0; i < panes.length; i++) {
			Pane p = panes[i];
			float tx = rightX + 2 + i * tw;
			boolean active = p == pane;
			boolean usable = p != Pane.STEP || step != null;
			boolean over = in(mx, my, tx, bodyTop - 4, tw - 2, TABS);
			float hv = hover("tab:" + p, over && usable);
			Draw.panel(g, tx, bodyTop - 4, tw - 2, TABS, active ? Theme.accent((float) i / panes.length) : Theme.a(Theme.panel2));
			if (!active) {
				Draw.panelOutline(g, tx, bodyTop - 4, tw - 2, TABS, ColorUtil.lerp(Theme.border, Theme.borderHi, hv));
			}
			int color = active ? Theme.onAccent() : usable ? ColorUtil.lerp(Theme.textDim, Theme.text, hv) : ColorUtil.fade(Theme.textDim, 0.4f);
			Draw.textCentered(g, p.label, tx + (tw - 2) / 2f, bodyTop, color);
			if (usable) {
				hit(tx, bodyTop - 4, tw - 2, TABS, () -> pane = p, null, "tab:" + p, null, mx, my);
			}
		}
		float top = rightTop();
		float h = bodyBottom - top;
		if (pane == Pane.ADD) {
			drawPalette(g, rightX, top, rightW, h, mx, my);
		} else if (panel != null) {
			panel.render(g, rightX, top, rightW, h, mx, my, dt);
			Row row = panel.getHovered();
			if (row != null) {
				tooltip.hover(row, panel.tooltipFor(row));
				if (row.clickable()) {
					wantHand = true;
				}
			} else if (panel.isResetHovered()) {
				tooltip.hover("reset", panel.resetTooltip());
				wantHand = true;
			}
		} else {
			box(g, rightX, top, rightW, h, pane.label, null);
			Draw.textCentered(g, macro == null ? "make a macro first" : "select a step", rightX + rightW / 2f, top + h / 2f - 4, Theme.textDim);
		}
	}

	private void drawPalette(GuiGraphicsExtractor g, float x, float y, float w, float h, int mx, int my) {
		box(g, x, y, w, h, "add step", step != null ? "after " + (stepIndex() + 1) : null);
		// categories as a grid of chips: three across when the longest name fits, otherwise two
		float widest = 0;
		for (String c : Steps.CATEGORIES) {
			widest = Math.max(widest, Theme.width(c));
		}
		int cols = widest + 8 <= (w - 16) / 3f ? 3 : 2;
		int chipRows = (Steps.CATEGORIES.size() + cols - 1) / cols;
		float cw = (w - 12 - (cols - 1) * 2) / cols;
		float cy = y + 8;
		for (int i = 0; i < Steps.CATEGORIES.size(); i++) {
			String c = Steps.CATEGORIES.get(i);
			float cx = x + 6 + (i % cols) * (cw + 2);
			float ry = cy + (i / cols) * 14;
			boolean active = c.equals(category);
			boolean over = in(mx, my, cx, ry, cw, 12);
			float hv = hover("cat:" + c, over);
			Draw.panel(g, cx, ry, cw, 12, active ? Theme.accent((float) i / 6) : ColorUtil.lerp(Theme.panel2, Theme.panel2, hv));
			if (!active) {
				Draw.panelOutline(g, cx, ry, cw, 12, ColorUtil.lerp(Theme.border, Theme.borderHi, hv));
			}
			Draw.textCentered(g, c, cx + cw / 2f, ry + 2, active ? Theme.onAccent() : ColorUtil.lerp(Theme.textDim, Theme.text, hv));
			hit(cx, ry, cw, 12, () -> {
				category = c;
				paletteScrollTarget = 0;
			}, null, "cat:" + c, null, mx, my);
		}
		float top = cy + chipRows * 14 + 4;
		float bottom = y + h - 4;
		List<StepType> types = Steps.inCategory(category);
		float content = types.size() * CARD;
		float maxScroll = Math.max(0, content - (bottom - top));
		paletteScrollTarget = Math.max(0, Math.min(maxScroll, paletteScrollTarget));
		float sc = paletteScroll.target(paletteScrollTarget).update(dt);
		g.enableScissor(Math.round(x + 1), Math.round(top), Math.round(x + w - 1), Math.round(bottom));
		for (int i = 0; i < types.size(); i++) {
			StepType t = types.get(i);
			float ry = top + i * CARD - sc;
			if (ry + CARD < top || ry > bottom) {
				continue;
			}
			boolean over = in(mx, my, x + 4, ry, w - 8, CARD - 2) && my >= top && my < bottom;
			float hv = hover(t, over);
			float press = Motion.clamp01(1f - (Motion.time() - pressed.getOrDefault(t, -10f)) * 5f);
			Draw.panel(g, x + 4, ry, w - 8, CARD - 2, ColorUtil.lerp(ColorUtil.fade(Theme.panel2, 0.5f + hv * 0.5f), Theme.accent(), press));
			Draw.rect(g, x + 4, ry + 3, 2, CARD - 8, ColorUtil.lerp(Theme.border, Theme.accent(), hv));
			Draw.text(g, t.name().toLowerCase(Locale.ROOT), x + 11 + hv * 2, ry + 3, press > 0.5f ? Theme.onAccent() : Theme.text);
			Draw.text(g, Draw.ellipsize(t.description(), (int) (w - 22)), x + 11, ry + 13, press > 0.5f ? Theme.onAccent() : Theme.textDim);
			Draw.text(g, "+", x + w - 14, ry + 7, ColorUtil.fade(Theme.accent(), hv));
			hit(x + 4, ry, w - 8, CARD - 2, () -> {
				pressed.put(t, Motion.time());
				addStep(t);
			}, null, t, t.name() + ": " + t.description() + "\nClick to add it " + (step != null ? "after step " + (stepIndex() + 1) : "at the end") + ".", mx, my);
		}
		g.disableScissor();
	}

	private void drawBar(GuiGraphicsExtractor g, int mx, int my) {
		float y = wy + wh - FOOTER - BAR + 1;
		float x = wx + 8;
		MacroRun run = macro == null ? null : MacroManager.runOf(macro);
		boolean has = macro != null;
		boolean sel = step != null;
		x = barButton(g, "run", run != null ? "restart" : "run", "ctrl+enter", x, y, has, run == null && has, this::run,
				"Run the macro from the top.", mx, my);
		x = barButton(g, "stop", "stop", null, x, y, run != null, false, () -> MacroManager.stop(macro), "Stop it.", mx, my);
		x = barButton(g, "add", "+ step", null, x, y, true, pane == Pane.ADD, () -> pane = Pane.ADD, "Open the step palette.", mx, my);
		x = barButton(g, "dup", "dup", "ctrl+d", x, y, sel, false, this::duplicateStep, "Copy the selected step.", mx, my);
		x = barButton(g, "del", "del", "del", x, y, sel, false, this::deleteStep, "Delete the selected step.", mx, my);
		x = barButton(g, "undo", "undo", "ctrl+z", x, y, !undo.isEmpty(), false, this::undo, "Undo the last change to the steps.", mx, my);

		String status;
		int color = Theme.textDim;
		if (run != null) {
			int loops = macro.loops.getInt();
			status = "step " + (run.index() + 1) + "/" + macro.steps.size() + "  loop " + (run.loop() + 1) + "/" + (loops == 0 ? "inf" : loops);
			color = Theme.text;
		} else {
			status = macro == null ? "" : macro.steps.size() + " steps, " + (macro.loops.getInt() == 0 ? "loops forever" :
					macro.loops.getInt() == 1 ? "runs once" : "runs " + macro.loops.getInt() + " times");
		}
		Draw.textRight(g, Draw.ellipsize(status, (int) (wx + ww - 10 - x)), wx + ww - 9, y + 4, color);
	}

	private float barButton(GuiGraphicsExtractor g, String id, String label, String key, float x, float y, boolean enabled, boolean active,
							Runnable action, String tip, int mx, int my) {
		float w = Theme.width(label) + (key == null ? 0 : Theme.width(key) + 5) + 12;
		boolean over = enabled && in(mx, my, x, y, w, 16);
		float hv = hover("bar:" + id, over);
		float press = Motion.clamp01(1f - (Motion.time() - pressed.getOrDefault(id, -10f)) * 6f);
		int fill = active ? Theme.accent() : ColorUtil.lerp(Theme.a(Theme.panel2), Theme.accent(), press * 0.7f);
		Draw.panel(g, x, y, w, 16, fill);
		Draw.panelOutline(g, x, y, w, 16, active ? Theme.accent() : ColorUtil.lerp(Theme.border, Theme.borderHi, hv));
		int color = !enabled ? ColorUtil.fade(Theme.textDim, 0.4f) : active || press > 0.5f ? Theme.onAccent()
				: ColorUtil.lerp(Theme.textDim, Theme.text, hv);
		Draw.text(g, label, x + 6, y + 4, color);
		if (key != null) {
			Draw.text(g, key, x + 11 + Theme.width(label), y + 4, ColorUtil.fade(color, 0.5f));
		}
		if (enabled) {
			hit(x, y, w, 16, () -> {
				pressed.put(id, Motion.time());
				action.run();
			}, null, "bar:" + id, tip, mx, my);
		}
		return x + w + 4;
	}

	private void drawFooter(GuiGraphicsExtractor g) {
		float fy = wy + wh - FOOTER;
		Draw.rect(g, wx + 1, fy, ww - 2, 1, Theme.a(Theme.border));
		Draw.rect(g, wx + 1, fy + 1, ww - 2, FOOTER - 2, Theme.a(Theme.panel));
		String prompt = minecraft.getUser().getName().toLowerCase(Locale.ROOT) + "@troll:~/macros$ ";
		float x = wx + 7;
		Draw.text(g, prompt, x, fy + 3, Theme.accent(0.15f));
		x += Theme.width(prompt);
		String hints = "alt+up/down move  space on/off";
		String typed = TerminalLog.current().substring(0, TerminalLog.typed(60 * Motion.speed()));
		typed = Draw.ellipsize(typed, (int) (wx + ww - 20 - x - Theme.width(hints)));
		Draw.text(g, typed, x, fy + 3, Theme.text);
		if ((int) (Motion.time() * 2.2f) % 2 == 0) {
			Draw.rect(g, x + Theme.width(typed) + 1, fy + 2, 5, 9, ColorUtil.fade(Theme.text, 0.75f));
		}
		Draw.textRight(g, hints, wx + ww - 7, fy + 3, ColorUtil.fade(Theme.textDim, 0.7f));
	}

	private void hit(float x, float y, float w, float h, Runnable left, Runnable right, Object key, String tip, int mx, int my) {
		hits.add(new Hit(x, y, w, h, left, right));
		if (in(mx, my, x, y, w, h)) {
			wantHand = true;
			if (tip != null) {
				tooltip.hover(key, tip);
			}
		}
	}

	private float hover(Object key, boolean over) {
		return hovers.computeIfAbsent(key, k -> new Anim(16)).target(over).update(dt);
	}

	private static boolean in(double mx, double my, float x, float y, float w, float h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	private boolean overRight(double mx, double my) {
		return in(mx, my, rightX, rightTop(), rightW, bodyBottom - rightTop());
	}

	/** Step row under the mouse, or -1. */
	private int stepAt(double mx, double my) {
		if (macro == null || !in(mx, my, midX + 2, listTop(), midW - 4, listBottom() - listTop())) {
			return -1;
		}
		int i = (int) Math.floor((my - listTop() + stepScroll.get()) / ROW);
		return i >= 0 && i < macro.steps.size() ? i : -1;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (closing) {
			return true;
		}
		double mx = event.x(), my = event.y();
		int button = event.button();
		MacroManager.markDirty();
		// the settings panel first: it also unfocuses its text boxes on clicks elsewhere
		if (panel != null && pane != Pane.ADD && panel.mouseClicked((float) mx, (float) my, button)) {
			return true;
		}
		for (Hit h : hits) {
			if (h.contains(mx, my)) {
				Runnable action = button == 1 ? h.right() : button == 0 ? h.left() : null;
				if (action != null) {
					Sounds.click();
					action.run();
				}
				return true;
			}
		}
		int at = stepAt(mx, my);
		if (at >= 0) {
			MacroStep s = macro.steps.get(at);
			if (button == 1) {
				toggleStep(s);
			} else if (button == 0) {
				selectStep(s);
				pressIndex = at;
				pressY = (float) my;
				dragY = (float) my;
				Sounds.tick();
			}
			return true;
		}
		if (button == 0 && in(mx, my, midX, listTop(), midW, listBottom() - listTop())) {
			// empty space under the steps: back to the macro's own settings
			selectStep(null);
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (panel != null) {
			panel.mouseReleased((float) event.x(), (float) event.y(), event.button());
		}
		if (dragging) {
			int to = dropIndex();
			moveStep(pressIndex, to > pressIndex ? to - 1 : to);
		}
		dragging = false;
		pressIndex = -1;
		MacroManager.markDirty();
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (pressIndex >= 0) {
			dragY = (float) event.y();
			if (!dragging && Math.abs(dragY - pressY) > 3) {
				dragging = true;
				MacroManager.stop(macro);
			}
			if (dragging) {
				// nudge the list when dragging past its ends
				if (dragY < listTop() + 6) {
					stepScrollTarget -= 4;
				} else if (dragY > listBottom() - 6) {
					stepScrollTarget += 4;
				}
			}
			return true;
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		MacroManager.markDirty();
		if (overRight(mouseX, mouseY)) {
			if (pane == Pane.ADD) {
				paletteScrollTarget -= (float) scrollY * CARD;
			} else if (panel != null) {
				panel.mouseScrolled((float) mouseX, (float) mouseY, scrollY);
			}
		} else if (in(mouseX, mouseY, midX, bodyTop, midW, bodyBottom - bodyTop)) {
			stepScrollTarget -= (float) scrollY * ROW * 2;
		} else if (in(mouseX, mouseY, leftX, bodyTop, leftW, bodyBottom - bodyTop)) {
			macroScrollTarget -= (float) scrollY * ROW * 2;
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (closing) {
			return true;
		}
		MacroManager.markDirty();
		if (panel != null && panel.isCapturing()) {
			return panel.keyPressed(event);
		}
		int key = event.key();
		boolean ctrl = event.hasControlDown();
		int at = stepIndex();
		switch (key) {
			case GLFW.GLFW_KEY_ESCAPE -> onClose();
			case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
				if (macro == null || macro.steps.isEmpty()) {
					return true;
				}
				int dir = key == GLFW.GLFW_KEY_UP ? -1 : 1;
				if (event.hasAltDown() && at >= 0) {
					moveStep(at, at + dir);
				} else {
					int next = at < 0 ? (dir > 0 ? 0 : macro.steps.size() - 1) : Math.floorMod(at + dir, macro.steps.size());
					selectStep(macro.steps.get(next));
					scrollStepIntoView();
					Sounds.tick();
				}
			}
			case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> deleteStep();
			case GLFW.GLFW_KEY_SPACE -> {
				if (step != null) {
					toggleStep(step);
				}
			}
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				if (ctrl) {
					run();
				}
			}
			case GLFW.GLFW_KEY_D -> {
				if (ctrl) {
					duplicateStep();
				}
			}
			case GLFW.GLFW_KEY_Z -> {
				if (ctrl) {
					undo();
				}
			}
			case GLFW.GLFW_KEY_N -> {
				if (ctrl) {
					newMacro();
				}
			}
			default -> {
				return super.keyPressed(event);
			}
		}
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (panel != null && panel.isCapturing()) {
			MacroManager.markDirty();
			return panel.charTyped(event.codepointAsString());
		}
		return true;
	}

	/** Shows the step palette on a category (for screenshots). */
	public void showPalette(String category) {
		this.category = category;
		pane = Pane.ADD;
	}

	/** Centre of a step row in GUI coordinates (for scripted clicks). */
	public double[] stepRowCenter(int index) {
		layout();
		return new double[]{midX + midW / 2f, listTop() + index * ROW - stepScroll.get() + ROW / 2f};
	}
}
