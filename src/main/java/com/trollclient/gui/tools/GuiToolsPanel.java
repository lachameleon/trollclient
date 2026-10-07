package com.trollclient.gui.tools;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.trollclient.config.ConfigManager;
import com.trollclient.gui.Anim;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Motion;
import com.trollclient.gui.Sounds;
import com.trollclient.gui.Theme;
import com.trollclient.gui.Tooltip;
import com.trollclient.gui.clickgui.GuiState;
import com.trollclient.gui.clickgui.TerminalLog;
import com.trollclient.gui.clickgui.TextField;
import com.trollclient.gui.clickgui.components.BindRow;
import com.trollclient.gui.macro.MacroEditorScreen;
import com.trollclient.macro.Macro;
import com.trollclient.macro.MacroManager;
import com.trollclient.macro.MacroRun;
import com.trollclient.mixin.AbstractContainerScreenAccessor;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.GuiToolsModule;
import com.trollclient.module.client.ThemeModule;
import com.trollclient.packet.GuiTools;
import com.trollclient.packet.PacketGate;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.ColorUtil;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.inventory.Slot;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The GUI tools window over every container screen: the packet gate and its
 * queue, screen tricks (close without a packet, de-sync, save and load), the
 * macros, a chat line and the container's ids. It's hooked in with Fabric's
 * per-screen events, and every click on it stays on it, so the container
 * underneath never sees a stray click (which could drop the carried item).
 */
public final class GuiToolsPanel {
	private static final GuiToolsPanel INSTANCE = new GuiToolsPanel();
	private static final float W = 140;
	private static final float HEADER = 16;
	private static final float PAD = 6;
	private static final float BTN_H = 14;
	private static final float GAP = 3;
	private static final float LABEL_H = 12;
	private static final float QUEUE_ROW = 10;
	private static final float MACRO_ROW = 12;
	private static final int QUEUE_ROWS = 4;
	private static final int MACRO_ROWS = 4;
	private static final int SECTION_PACKETS = 1;
	private static final int SECTION_SCREEN = 2;
	private static final int SECTION_MACROS = 4;

	/** Something clickable laid out this frame. */
	private record Hit(float x, float y, float w, float h, Runnable left, Runnable right) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final List<Hit> hits = new ArrayList<>();
	/** Where each button was drawn last frame, by id (for scripted clicks in the dev smoke test). */
	private final Map<String, float[]> buttons = new HashMap<>();
	private final Map<String, Anim> hovers = new HashMap<>();
	private final Map<String, Float> pressed = new HashMap<>();
	private final Tooltip tooltip = new Tooltip();
	private final TextField chat = new TextField(256).onSubmit(this::sendChat);
	private final Anim collapse = new Anim(GuiState.toolsCollapsed ? 1 : 0, 14);

	private Screen lastScreen;
	private float openedAt;
	private long lastNanos;
	private float dt;
	private boolean shown;
	private float px, py, ph;
	private float[] chatBox;
	private float[] queueBox;
	private float[] macroBox;
	private int queueScroll;
	private int macroScroll;
	private boolean dragging;
	private float dragDX, dragDY;
	private boolean wantHand;

	private GuiToolsPanel() {
	}

	/** Fabric AFTER_INIT: hook the panel into every container screen (events are rebuilt on each init/resize). */
	public static void attach(Screen screen) {
		if (!(screen instanceof AbstractContainerScreen<?>)) {
			return;
		}
		GuiToolsPanel p = INSTANCE;
		ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> p.render(s, g, mx, my));
		ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !p.mouseClicked(e.x(), e.y(), e.button()));
		ScreenMouseEvents.allowMouseRelease(screen).register((s, e) -> !p.mouseReleased(e.x(), e.y()));
		ScreenMouseEvents.allowMouseDrag(screen).register((s, e, dx, dy) -> !p.mouseDragged(e.x(), e.y()));
		ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, h, v) -> !p.mouseScrolled(mx, my, v));
		ScreenKeyboardEvents.allowKeyPress(screen).register((s, e) -> !p.keyPressed(e));
		ScreenKeyboardEvents.allowCharType(screen).register((s, e) -> !p.charTyped(e.codepointAsString()));
		ScreenEvents.remove(screen).register(s -> p.chat.setFocused(false));
	}

	/** Centre of a button ("send", "delay", "flush"...) as last drawn, in GUI coordinates, or null. */
	public static double[] buttonCenter(String id) {
		float[] b = INSTANCE.buttons.get(id);
		return b == null ? null : new double[]{b[0] + b[2] / 2f, b[1] + b[3] / 2f};
	}

	/** True while the panel's chat line has the keyboard (macro keys stay quiet then). */
	public static boolean isTyping() {
		return INSTANCE.shown && INSTANCE.chat.isFocused();
	}

	private static boolean enabled() {
		GuiToolsModule tools = ModuleManager.get(GuiToolsModule.class);
		return tools != null && tools.isEnabled() && tools.panel.get();
	}

	private static boolean folded(int section) {
		return (GuiState.toolsFolded & section) != 0;
	}

	// ------------------------------------------------------------------ layout

	private float sectionHeight(int section, float body) {
		return LABEL_H + (folded(section) ? 0 : body);
	}

	private float contentHeight() {
		float h = 2;
		int queued = Math.min(QUEUE_ROWS, PacketGate.queued());
		float queue = queued > 0 ? queued * QUEUE_ROW + (PacketGate.queued() > QUEUE_ROWS ? QUEUE_ROW : 0) + 4 : 0;
		float dropped = !PacketGate.isSending() ? QUEUE_ROW : 0;
		h += sectionHeight(SECTION_PACKETS, (BTN_H + GAP) * 2 + queue + dropped);
		h += sectionHeight(SECTION_SCREEN, (BTN_H + GAP) * 3);
		int count = MacroManager.macros().size();
		float list = count == 0 ? MACRO_ROW : Math.min(MACRO_ROWS, count) * MACRO_ROW + 2 + (count > MACRO_ROWS ? QUEUE_ROW : 0);
		h += sectionHeight(SECTION_MACROS, BTN_H + GAP + list + 3);
		if (ModuleManager.get(GuiToolsModule.class).chat.get()) {
			h += BTN_H + GAP + 2;
		}
		h += 11; // ids
		return h;
	}

	// ------------------------------------------------------------------ render

	private void render(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
		hits.clear();
		shown = enabled();
		if (!shown) {
			return;
		}
		long now = System.nanoTime();
		dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
		lastNanos = now;
		if (screen != lastScreen) {
			lastScreen = screen;
			openedAt = Motion.time();
			chat.setFocused(false);
		}
		Theme.update();
		tooltip.reset();
		wantHand = false;

		float c = collapse.target(GuiState.toolsCollapsed).update(dt);
		float full = HEADER + contentHeight() + 13;
		ph = HEADER + (full - HEADER) * (1 - Motion.outCubic(c));
		px = Math.max(0, Math.min(screen.width - W, GuiState.toolsX));
		py = Math.max(0, Math.min(screen.height - Math.min(ph, screen.height), GuiState.toolsY));

		float appear = Motion.outCubic(Motion.range((Motion.time() - openedAt) * Motion.speed(), 0, 0.25f));
		g.nextStratum();
		float prev = Draw.alpha;
		Draw.alpha = appear;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(-(1 - appear) * 14, 0);

		drawFrame(g);
		drawHeader(g, mouseX, mouseY);
		if (ph > HEADER + 2) {
			g.enableScissor(Math.round(px + 1), Math.round(py + HEADER), Math.round(px + W - 1), Math.round(py + ph - 1));
			float y = py + HEADER + 2;
			y = drawPackets(g, y, mouseX, mouseY);
			y = drawScreen(g, y, mouseX, mouseY);
			y = drawMacros(g, y, mouseX, mouseY);
			y = drawChat(g, y, mouseX, mouseY);
			y = drawIds(g, screen, y);
			drawShell(g, py + ph - 13);
			g.disableScissor();
		}
		pose.popMatrix();
		Draw.alpha = prev;
		if (wantHand) {
			g.requestCursor(CursorTypes.POINTING_HAND);
		}
		tooltip.render(g, mouseX, mouseY, dt, screen.width - 2, screen.height - 2);
	}

	private void drawFrame(GuiGraphicsExtractor g) {
		ThemeModule t = Theme.settings();
		if (t.ditherShadow.get()) {
			Draw.ditherShadow(g, px - 1, py - 1, W + 2, ph + 2, 4, ColorUtil.withAlpha(0x000000, 170));
		}
		if (t.glow.get()) {
			Draw.glow(g, px - 1, py - 1, W + 2, ph + 2, Theme.accent(), 6);
		}
		Draw.panel(g, px - 1, py - 1, W + 2, ph + 2, Theme.a(Theme.light ? 0xFF505050 : 0xFF000000));
		Draw.panel(g, px, py, W, ph, Theme.a(Theme.borderHi));
		Draw.rect(g, px + 1, py + 1, W - 2, ph - 2, Theme.a(Theme.bg));
		Draw.gradientH(g, px + 1, py + 1, W - 2, 2, p -> Theme.accent(p / 1000f));
	}

	private void drawHeader(GuiGraphicsExtractor g, int mx, int my) {
		Draw.rect(g, px + 1, py + 3, W - 2, HEADER - 3, Theme.a(Theme.panel));
		Draw.rect(g, px + 1, py + HEADER, W - 2, 1, Theme.a(Theme.border));
		float tagW = Theme.width("TROLL") + 6;
		Draw.panel(g, px + 5, py + 4, tagW, 11, Theme.accent(0.05f));
		Draw.text(g, "TROLL", px + 8, py + 6, Theme.onAccent(), false);
		Draw.text(g, "tools", px + tagW + 9, py + 6, Theme.text);

		// what the gate is doing, right in the header, so it shows even folded up
		String state = !PacketGate.isSending() ? "drop" : PacketGate.isDelaying() ? PacketGate.queued() + " held" : null;
		if (state != null) {
			float blink = (int) (Motion.time() * 2) % 2 == 0 ? 1 : 0.55f;
			Draw.textRight(g, state, px + W - 20, py + 6, ColorUtil.fade(Theme.accent(), blink));
		}
		float bx = px + W - 15, by = py + 4;
		boolean over = in(mx, my, bx, by, 11, 11);
		float hv = hover("collapse", over);
		if (hv > 0.01f) {
			Draw.panel(g, bx, by, 11, 11, ColorUtil.fade(Theme.panel2, hv));
		}
		Draw.text(g, GuiState.toolsCollapsed ? "+" : "-", bx + 3, by + 2, ColorUtil.lerp(Theme.textDim, Theme.text, hv));
		hit(bx, by, 11, 11, () -> {
			GuiState.toolsCollapsed = !GuiState.toolsCollapsed;
			ConfigManager.markDirty();
		}, null, "collapse", "Fold the panel up to its header", mx, my);
	}

	/** "packets ----" divider; clicking it folds the section. Returns the y below it. */
	private float label(GuiGraphicsExtractor g, String text, int section, float y, int mx, int my) {
		boolean over = in(mx, my, px + 2, y, W - 4, LABEL_H);
		float hv = hover("label:" + text, over);
		String shown = (folded(section) ? "+ " : "") + text;
		Draw.text(g, shown, px + PAD, y + 2, ColorUtil.lerp(Theme.textDim, Theme.text, hv));
		float lx = px + PAD + Theme.width(shown) + 4;
		Draw.rect(g, lx, y + 6, px + W - PAD - lx, 1, Theme.a(Theme.border));
		hit(px + 2, y, W - 4, LABEL_H, () -> {
			GuiState.toolsFolded ^= section;
			ConfigManager.markDirty();
		}, null, null, null, mx, my);
		return y + LABEL_H;
	}

	private float drawPackets(GuiGraphicsExtractor g, float y, int mx, int my) {
		y = label(g, "packets", SECTION_PACKETS, y, mx, my);
		if (folded(SECTION_PACKETS)) {
			return y;
		}
		float half = (W - PAD * 2 - GAP) / 2f;
		float lx = px + PAD, rx = lx + half + GAP;
		boolean send = PacketGate.isSending();
		boolean delay = PacketGate.isDelaying();
		button(g, "send", "send: " + (send ? "on" : "off"), lx, y, half, send, !send, () -> PacketGate.setSending(!send), null,
				"Send GUI packets. Off: clicks, buttons, trades, chat and item use are thrown away instead.", mx, my);
		button(g, "delay", "delay: " + (delay ? "on" : "off"), rx, y, half, delay, false, () -> PacketGate.setDelaying(!delay), null,
				"Hold GUI packets in a queue instead of sending them. The close packet still goes out, so the server sees it first.", mx, my);
		y += BTN_H + GAP;
		int queued = PacketGate.queued();
		button(g, "flush", queued > 0 ? "flush " + queued : "flush", lx, y, half, false, false, PacketGate::flush, null,
				"Send every queued packet now, in order.", mx, my);
		button(g, "clear", "clear", rx, y, half, false, queued > 0, PacketGate::clear, null, "Throw the queue away.", mx, my);
		y += BTN_H + GAP;

		if (!send) {
			Draw.text(g, "dropped " + PacketGate.dropped(), px + PAD + 1, y + 1, Theme.textDim);
			y += QUEUE_ROW;
		}
		List<PacketGate.Queued> queue = PacketGate.queue();
		queueBox = null;
		if (!queue.isEmpty()) {
			queueScroll = Math.max(0, Math.min(queueScroll, queue.size() - QUEUE_ROWS));
			int rows = Math.min(QUEUE_ROWS, queue.size());
			queueBox = new float[]{px + PAD - 2, y, W - PAD * 2 + 4, rows * QUEUE_ROW + 2};
			Draw.panel(g, queueBox[0], y, queueBox[2], queueBox[3], Theme.a(Theme.panel));
			for (int i = 0; i < rows; i++) {
				int at = queueScroll + i;
				PacketGate.Queued q = queue.get(at);
				float ry = y + 1 + i * QUEUE_ROW;
				boolean over = in(mx, my, queueBox[0], ry, queueBox[2], QUEUE_ROW);
				if (over) {
					Draw.rect(g, queueBox[0] + 1, ry, queueBox[2] - 2, QUEUE_ROW, ColorUtil.fade(Theme.panel2, 0.9f));
				}
				String num = String.format(Locale.ROOT, "%02d", at + 1);
				Draw.text(g, num, px + PAD, ry + 1, ColorUtil.fade(Theme.textDim, 0.6f));
				float nx = px + PAD + Theme.width(num) + 4;
				Draw.text(g, q.name(), nx, ry + 1, Theme.text);
				float dx = nx + Theme.width(q.name()) + 4;
				float room = px + W - PAD - 8 - dx;
				Draw.text(g, Draw.ellipsize(q.detail(), (int) room), dx, ry + 1, Theme.textDim);
				if (over) {
					Draw.text(g, "x", px + W - PAD - 6, ry + 1, Theme.accent());
				}
				hit(queueBox[0], ry, queueBox[2], QUEUE_ROW, () -> PacketGate.remove(q), null, q,
						q.name() + " " + q.detail() + "\nqueued " + (System.currentTimeMillis() - q.at()) / 100 / 10f + "s ago. Click to drop it.", mx, my);
			}
			y += queueBox[3];
			if (queue.size() > QUEUE_ROWS) {
				Draw.text(g, (queue.size() - QUEUE_ROWS) + " more (scroll)", px + PAD + 1, y + 1, ColorUtil.fade(Theme.textDim, 0.7f));
				y += QUEUE_ROW;
			}
			y += 2;
		}
		return y;
	}

	private float drawScreen(GuiGraphicsExtractor g, float y, int mx, int my) {
		y = label(g, "screen", SECTION_SCREEN, y, mx, my);
		if (folded(SECTION_SCREEN)) {
			return y;
		}
		float half = (W - PAD * 2 - GAP) / 2f;
		float lx = px + PAD, rx = lx + half + GAP;
		boolean container = GuiTools.containerOpen();
		button(g, "close", "close", lx, y, half, false, false, GuiTools::closeWithoutPacket, null,
				"Close this GUI without telling the server. As far as it knows, it's still open.", mx, my);
		button(g, "desync", "de-sync", rx, y, half, false, container, GuiTools::desync, null,
				container ? "Tell the server this GUI closed, but keep it open here." : "Only works on a container the server opened.", mx, my);
		y += BTN_H + GAP;
		button(g, "save", "save", lx, y, half, false, false, GuiTools::save, null,
				"Remember this GUI, so load can bring it back after you close it.", mx, my);
		String saved = GuiTools.savedTitle();
		button(g, "load", "load", rx, y, half, false, false, GuiTools::restore, null,
				saved == null ? "Nothing saved yet." : "Reopen \"" + saved + "\" without asking the server.", mx, my);
		y += BTN_H + GAP;
		button(g, "title", "title", lx, y, half, false, false, GuiTools::copyTitle, null, "Copy this GUI's title.", mx, my);
		button(g, "disc", "disc+send", rx, y, half, false, true, GuiTools::disconnectAndSend, null,
				"Send the delay queue and disconnect right behind it.", mx, my);
		return y + BTN_H + GAP;
	}

	private float drawMacros(GuiGraphicsExtractor g, float y, int mx, int my) {
		y = label(g, "macros", SECTION_MACROS, y, mx, my);
		if (folded(SECTION_MACROS)) {
			return y;
		}
		float half = (W - PAD * 2 - GAP) / 2f;
		float lx = px + PAD, rx = lx + half + GAP;
		button(g, "editor", "editor", lx, y, half, false, false, () -> MacroEditorScreen.open(null), null,
				"Open the macro editor. Closing it brings you back to this GUI.", mx, my);
		int running = MacroManager.runs().size();
		button(g, "stop", running > 0 ? "stop " + running : "stop", rx, y, half, running > 0, false, MacroManager::stopAll, null,
				"Stop every running macro.", mx, my);
		y += BTN_H + GAP;

		List<Macro> macros = MacroManager.macros();
		macroBox = null;
		if (macros.isEmpty()) {
			Draw.text(g, "no macros yet", px + PAD + 1, y + 2, ColorUtil.fade(Theme.textDim, 0.7f));
			return y + MACRO_ROW + 3;
		}
		macroScroll = Math.max(0, Math.min(macroScroll, macros.size() - MACRO_ROWS));
		int rows = Math.min(MACRO_ROWS, macros.size());
		macroBox = new float[]{px + PAD - 2, y, W - PAD * 2 + 4, rows * MACRO_ROW + 2};
		for (int i = 0; i < rows; i++) {
			Macro m = macros.get(macroScroll + i);
			MacroRun run = MacroManager.runOf(m);
			float ry = y + 1 + i * MACRO_ROW;
			boolean over = in(mx, my, macroBox[0], ry, macroBox[2], MACRO_ROW);
			float hv = hover("macro:" + System.identityHashCode(m), over);
			if (run != null) {
				// progress fill behind the name while it runs
				float progress = m.steps.isEmpty() ? 1 : (run.index() + 0.5f) / m.steps.size();
				Draw.rect(g, macroBox[0] + 1, ry, (macroBox[2] - 2) * Motion.clamp01(progress), MACRO_ROW - 1, ColorUtil.fade(Theme.accent(), 0.25f));
				Draw.dashedOutline(g, macroBox[0] + 1, ry, macroBox[2] - 2, MACRO_ROW - 1, Theme.accent(), Motion.time() * 10);
			} else if (hv > 0.01f) {
				Draw.rect(g, macroBox[0] + 1, ry, macroBox[2] - 2, MACRO_ROW - 1, ColorUtil.fade(Theme.panel2, hv));
			}
			Draw.text(g, run != null ? ">" : "-", px + PAD, ry + 2, run != null ? Theme.accent() : Theme.textDim);
			String right = run != null ? (run.index() + 1) + "/" + m.steps.size() : m.getKey() > 0 ? BindRow.keyName(m.getKey()) : "";
			float rw = Theme.width(right);
			Draw.textRight(g, right, px + W - PAD, ry + 2, Theme.textDim);
			Draw.text(g, Draw.ellipsize(m.getName(), (int) (W - PAD * 2 - 12 - rw)), px + PAD + 8 + hv * 2, ry + 2,
					ColorUtil.lerp(Theme.textDim, Theme.text, Math.max(hv, run != null ? 1 : 0)));
			hit(macroBox[0], ry, macroBox[2], MACRO_ROW, () -> MacroManager.toggle(m), () -> MacroEditorScreen.open(m), m,
					m.getName() + "\n" + m.steps.size() + " steps. Click to " + (run != null ? "stop" : "run") + ", right click to edit.", mx, my);
		}
		y += macroBox[3];
		if (macros.size() > MACRO_ROWS) {
			Draw.textRight(g, (macroScroll + 1) + "-" + (macroScroll + rows) + " of " + macros.size() + " (scroll)", px + W - PAD, y + 1,
					ColorUtil.fade(Theme.textDim, 0.6f));
			y += QUEUE_ROW;
		}
		return y + 3;
	}

	private float drawChat(GuiGraphicsExtractor g, float y, int mx, int my) {
		chatBox = null;
		if (!ModuleManager.get(GuiToolsModule.class).chat.get()) {
			return y;
		}
		float x = px + PAD, w = W - PAD * 2;
		chatBox = new float[]{x, y, w, BTN_H};
		boolean over = in(mx, my, x, y, w, BTN_H);
		float hv = hover("chat", over || chat.isFocused());
		Draw.panel(g, x, y, w, BTN_H, Theme.a(Theme.panel2));
		Draw.panelOutline(g, x, y, w, BTN_H, ColorUtil.lerp(Theme.border, Theme.accent(), chat.isFocused() ? 1 : hv * 0.5f));
		g.enableScissor(Math.round(x + 2), Math.round(y), Math.round(x + w - 2), Math.round(y + BTN_H));
		chat.render(g, x + 4, y + 3, w - 8, "say something or /cmd", 1f);
		g.disableScissor();
		if (over) {
			tooltip.hover("chat", "Chat or run a command without closing the GUI. With delay on, it waits in the queue too.");
			wantHand = true;
		}
		return y + BTN_H + GAP + 2;
	}

	/** The container's state id (revision), its id, and the slot under the mouse: what the macro steps need. */
	private float drawIds(GuiGraphicsExtractor g, Screen screen, float y) {
		Minecraft mc = Minecraft.getInstance();
		String rev = "--", id = "--", slot = "--";
		if (mc.player != null && mc.player.containerMenu != null) {
			rev = Integer.toString(mc.player.containerMenu.getStateId());
			id = Integer.toString(mc.player.containerMenu.containerId);
			Slot hovered = ((AbstractContainerScreenAccessor) screen).troll$getHoveredSlot();
			if (hovered != null) {
				slot = Integer.toString(hovered.index);
			}
		}
		float x = px + PAD;
		x = metric(g, "rev", rev, x, y);
		x = metric(g, "id", id, x + 6, y);
		metric(g, "slot", slot, x + 6, y);
		return y + 11;
	}

	private float metric(GuiGraphicsExtractor g, String key, String value, float x, float y) {
		Draw.text(g, key, x, y, Theme.textDim);
		x += Theme.width(key) + 3;
		Draw.text(g, value, x, y, Theme.text);
		return x + Theme.width(value);
	}

	private void drawShell(GuiGraphicsExtractor g, float y) {
		Draw.rect(g, px + 1, y, W - 2, 1, Theme.a(Theme.border));
		Draw.rect(g, px + 1, y + 1, W - 2, 11, Theme.a(Theme.panel));
		float x = px + 5;
		Draw.text(g, "$", x, y + 3, Theme.accent(0.15f));
		x += Theme.width("$ ");
		String line = TerminalLog.current();
		String typed = Draw.ellipsize(line.substring(0, TerminalLog.typed(60 * Motion.speed())), (int) (px + W - 12 - x));
		Draw.text(g, typed, x, y + 3, Theme.text);
		if ((int) (Motion.time() * 2.2f) % 2 == 0) {
			Draw.rect(g, x + Theme.width(typed) + 1, y + 2, 4, 8, ColorUtil.fade(Theme.text, 0.75f));
		}
	}

	/**
	 * A panel button. {@code active} fills it with the accent (a switch that's
	 * on); {@code danger} gives it crawling ants instead of a solid border.
	 */
	private void button(GuiGraphicsExtractor g, String id, String label, float x, float y, float w, boolean active, boolean danger,
						Runnable left, Runnable right, String tip, int mx, int my) {
		buttons.put(id, new float[]{x, y, w, BTN_H});
		boolean over = in(mx, my, x, y, w, BTN_H);
		float hv = hover(id, over);
		float press = Motion.clamp01(1f - (Motion.time() - pressed.getOrDefault(id, -10f)) * 6f);
		int fill = active ? Theme.accent((x - px) / W) : ColorUtil.lerp(Theme.panel2, Theme.accent(), press * 0.7f);
		Draw.panel(g, x, y, w, BTN_H, Theme.a(fill));
		int edge = ColorUtil.lerp(Theme.border, Theme.borderHi, hv);
		if (danger && !active) {
			Draw.dashedOutline(g, x, y, w, BTN_H, ColorUtil.lerp(edge, Theme.text, hv * 0.5f), Motion.time() * 8);
		} else {
			Draw.panelOutline(g, x, y, w, BTN_H, active ? ColorUtil.lerp(Theme.accent(), Theme.text, hv * 0.4f) : edge);
		}
		int color = active || press > 0.5f ? Theme.onAccent() : ColorUtil.lerp(Theme.textDim, Theme.text, hv);
		Draw.textCentered(g, label, x + w / 2f, y + 3, color);
		hit(x, y, w, BTN_H, () -> {
			pressed.put(id, Motion.time());
			left.run();
		}, right, id, tip, mx, my);
	}

	/** Registers a clickable box; {@code key} identifies it for the tooltip (it must stay the same frame to frame). */
	private void hit(float x, float y, float w, float h, Runnable left, Runnable right, Object key, String tip, int mx, int my) {
		hits.add(new Hit(x, y, w, h, left, right));
		if (in(mx, my, x, y, w, h)) {
			wantHand = true;
			if (tip != null) {
				tooltip.hover(key, tip);
			}
		}
	}

	private float hover(String id, boolean over) {
		return hovers.computeIfAbsent(id, k -> new Anim(16)).target(over).update(dt);
	}

	private static boolean in(double mx, double my, float x, float y, float w, float h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	private boolean inside(double mx, double my) {
		return shown && in(mx, my, px - 1, py - 1, W + 2, ph + 2);
	}

	// ------------------------------------------------------------------ input

	private void sendChat() {
		String text = chat.getText().trim();
		chat.setText("");
		if (!text.isEmpty()) {
			ChatUtil.send(text);
			Minecraft.getInstance().gui.hud.getChat().addRecentChat(text);
		}
	}

	/** True if the click was ours (the container doesn't get it). */
	private boolean mouseClicked(double mx, double my, int button) {
		if (!inside(mx, my)) {
			chat.setFocused(false);
			return false;
		}
		// hits are laid out top-down without overlaps; the header's fold button comes before the drag area
		for (Hit h : hits) {
			if (h.contains(mx, my)) {
				Runnable action = button == 1 ? h.right() : button == 0 ? h.left() : null;
				if (action != null) {
					Sounds.click();
					action.run();
				}
				chat.setFocused(false);
				return true;
			}
		}
		if (chatBox != null && in(mx, my, chatBox[0], chatBox[1], chatBox[2], chatBox[3])) {
			chat.setFocused(true);
			Sounds.click();
			return true;
		}
		chat.setFocused(false);
		if (button == 0 && my < py + HEADER) {
			dragging = true;
			dragDX = (float) mx - px;
			dragDY = (float) my - py;
		}
		return true;
	}

	private boolean mouseReleased(double mx, double my) {
		if (dragging) {
			dragging = false;
			ConfigManager.markDirty();
			return true;
		}
		return inside(mx, my);
	}

	private boolean mouseDragged(double mx, double my) {
		if (dragging) {
			GuiState.toolsX = (float) mx - dragDX;
			GuiState.toolsY = (float) my - dragDY;
			return true;
		}
		return inside(mx, my);
	}

	private boolean mouseScrolled(double mx, double my, double amount) {
		if (!inside(mx, my)) {
			return false;
		}
		int step = amount > 0 ? -1 : 1;
		if (queueBox != null && in(mx, my, queueBox[0], queueBox[1], queueBox[2], queueBox[3] + QUEUE_ROW)) {
			queueScroll += step;
		} else if (macroBox != null && in(mx, my, macroBox[0], macroBox[1], macroBox[2], macroBox[3])) {
			macroScroll += step;
		}
		return true;
	}

	private boolean keyPressed(KeyEvent event) {
		if (!shown || !chat.isFocused()) {
			return false;
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			chat.setFocused(false);
			return true;
		}
		chat.keyPressed(event);
		// everything else is ours while typing: E mustn't close the inventory, numbers mustn't swap slots
		return true;
	}

	private boolean charTyped(String chars) {
		return shown && chat.isFocused() && chat.charTyped(chars);
	}
}
