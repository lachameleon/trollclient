package com.trollclient.gui.centre;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.trollclient.TrollClient;
import com.trollclient.chat.Chatroom;
import com.trollclient.dev.DevShots;
import com.trollclient.dev.Showcase;
import com.trollclient.gui.Anim;
import com.trollclient.gui.Backdrop;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Motion;
import com.trollclient.gui.ScreenFx;
import com.trollclient.gui.Sounds;
import com.trollclient.gui.Theme;
import com.trollclient.gui.clickgui.TextField;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.ChatroomModule;
import com.trollclient.module.client.ClickGuiModule;
import com.trollclient.update.UpdateNotes;
import com.trollclient.update.Updater;
import com.trollclient.util.ColorUtil;
import com.trollclient.util.Site;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The Troll Client centre, opened from the title screen's corner buttons: the chatroom with somewhere
 * to type (there is no chat to read it in without a world) and the update notes, in the same window
 * chrome as the custom world and server lists. The forum and the Discord are web pages, so those two
 * are buttons that hand a link to the browser.
 */
public class TrollCentreScreen extends Screen {
	public static final String DISCORD = "https://discord.gg/G57j8hF3yy";

	private static final float HEADER = 26;
	private static final float FOOTER = 28;
	private static final float INPUT_H = 20;
	private static final float LINE_H = 10;

	public enum Tab {
		CHATROOM("chatroom", "one room for everyone running the client, on any server or none"),
		NOTES("update notes", "what changed, newest first");

		private final String label;
		private final String tagline;

		Tab(String label, String tagline) {
			this.label = label;
			this.tagline = tagline;
		}
	}

	private enum Kind {
		NAME, OWN, UNVERIFIED, NOTE, HEADING, ENTRY, MORE, GAP
	}

	/** One drawn line: {@code lead} (a name, a date) then {@code text}; wrapped lines hang under the text. */
	private record Row(Kind kind, String lead, String text, int indent, boolean dim) {
	}

	private record Action(String label, String hotkey, BooleanSupplier enabled, Runnable run) {
	}

	private final Screen parent;
	private final Backdrop backdrop = new Backdrop();
	private final TextField input = new TextField(200);
	private final Anim open = new Anim(0, 7);
	private final Anim scroll = new Anim(16);
	private final Anim tabX = new Anim(18);
	private final Anim tabW = new Anim(18);
	private final Anim inputFocus = new Anim(14);
	private final Map<Tab, Anim> tabHover = new HashMap<>();
	private final Map<Integer, Anim> actionHover = new HashMap<>();

	private Tab tab;
	private float tabChangedAt = Motion.time();
	private boolean tabSnapped;
	private List<Row> rows = List.of();
	private int rowsKey;
	private float scrollTarget;
	/** Chatroom only: stay on the newest line until the player scrolls up. */
	private boolean follow;
	private boolean snapScroll;
	private String status = "";
	private float statusAt = -10;
	private long lastNanos;
	private float dt;
	private boolean closing;

	public TrollCentreScreen(Screen parent, Tab tab) {
		super(Component.literal("Troll Client centre"));
		this.parent = parent;
		input.onSubmit(() -> {
			String text = input.getText().trim();
			input.setText("");
			if (!text.isEmpty()) {
				Chatroom.say(text);
				follow = true;
			}
		});
		show(tab);
	}

	// ------------------------------------------------------------------ links

	/** The forum page on this build's site, or "" if it has no site. */
	public static String forumUrl() {
		return Site.url("/forum");
	}

	/** Hands a link to the system browser. False if there was nothing to open (or a scripted run, which never should). */
	public static boolean browse(String url) {
		if (url.isEmpty() || Showcase.enabled() || DevShots.enabled()) {
			return false;
		}
		try {
			Util.getPlatform().openUri(URI.create(url));
			return true;
		} catch (IllegalArgumentException e) {
			TrollClient.LOGGER.warn("Couldn't open {}", url, e);
			return false;
		}
	}

	private void link(String url, String what) {
		status(browse(url) ? what + " opened in your browser" : "couldn't open " + what);
	}

	private void status(String text) {
		status = text;
		statusAt = Motion.time();
	}

	// ------------------------------------------------------------------ tabs and their lines

	private void show(Tab next) {
		tab = next;
		tabChangedAt = Motion.time();
		rowsKey = 0;
		scrollTarget = 0;
		follow = next == Tab.CHATROOM;
		snapScroll = true;
		input.setFocused(next == Tab.CHATROOM);
	}

	private ChatroomModule chatroom() {
		return ModuleManager.get(ChatroomModule.class);
	}

	private void buildRows(float width) {
		int key = Objects.hash(tab, (int) width, Theme.font(), Chatroom.logged(), chatroom().verifiedOnly.get());
		if (key == rowsKey) {
			return;
		}
		rowsKey = key;
		List<Row> out = new ArrayList<>();
		if (tab == Tab.CHATROOM) {
			String me = minecraft.getUser().getName();
			for (Chatroom.Line line : Chatroom.log()) {
				if (line.note()) {
					add(out, Kind.NOTE, "* ", line.text(), width, Theme.width("* "), true);
				} else {
					Kind kind = line.name().equalsIgnoreCase(me) ? Kind.OWN : line.verified() ? Kind.NAME : Kind.UNVERIFIED;
					String lead = (line.verified() ? "" : "~") + line.name() + ": ";
					add(out, kind, lead, line.text(), width, Theme.width(lead), false);
				}
			}
		} else {
			String running = Site.version(TrollClient.MOD_ID);
			// one column for the dates: digits aren't all the same width in every font
			int column = 0;
			for (UpdateNotes.Entry entry : UpdateNotes.entries()) {
				column = Math.max(column, Theme.width(entry.date().isEmpty() ? "- " : entry.date() + "  "));
			}
			for (UpdateNotes.Entry entry : UpdateNotes.entries()) {
				if (entry.heading()) {
					if (!out.isEmpty()) {
						out.add(new Row(Kind.GAP, "", "", 0, false));
					}
					out.add(new Row(Kind.HEADING, "v" + entry.version(), entry.version().equals(running) ? "the one you're running" : "", 0, true));
				} else {
					add(out, Kind.ENTRY, entry.date().isEmpty() ? "- " : entry.date(), entry.text(), width, column, false);
				}
			}
		}
		rows = out;
	}

	private static void add(List<Row> out, Kind kind, String lead, String text, float width, int indent, boolean dim) {
		List<String> lines = wrap(text, (int) Math.max(40, width - indent));
		for (int i = 0; i < lines.size(); i++) {
			out.add(new Row(i == 0 ? kind : Kind.MORE, lead, lines.get(i), indent, dim));
		}
	}

	/** Word wrap that also breaks a single word too long for the line (a wall of one letter, a link). */
	private static List<String> wrap(String text, int maxWidth) {
		List<String> out = new ArrayList<>();
		for (String line : Draw.wrap(text, maxWidth)) {
			while (Theme.width(line) > maxWidth && line.length() > 1) {
				int cut = line.length() - 1;
				while (cut > 1 && Theme.width(line.substring(0, cut)) > maxWidth) {
					cut--;
				}
				out.add(line.substring(0, cut));
				line = line.substring(cut);
			}
			out.add(line);
		}
		if (out.isEmpty()) {
			out.add("");
		}
		return out;
	}

	private List<Action> actions() {
		List<Action> list = new ArrayList<>();
		list.add(new Action("forum", "web", () -> !forumUrl().isEmpty(), () -> link(forumUrl(), "the forum")));
		list.add(new Action("discord", "web", () -> true, () -> link(DISCORD, "the discord")));
		if (tab == Tab.CHATROOM) {
			ChatroomModule module = chatroom();
			list.add(new Action("chatroom:", module.isEnabled() ? "on" : "off", () -> !Site.origin().isEmpty(), () -> {
				module.toggle();
				Sounds.toggle(module.isEnabled());
			}));
		}
		list.add(new Action("back", "esc", () -> true, this::onClose));
		return list;
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

	private float bodyX() {
		return winX() + 10;
	}

	private float bodyY() {
		return winY() + HEADER + 22;
	}

	private float bodyW() {
		return winW() - 20;
	}

	private float bodyH() {
		return winH() - HEADER - 22 - FOOTER - 6 - (tab == Tab.CHATROOM ? INPUT_H : 0);
	}

	private float[] inputBox() {
		return new float[]{bodyX(), bodyY() + bodyH() + 4, bodyW(), INPUT_H - 6};
	}

	private float[] closeBox() {
		return new float[]{winX() + winW() - 21, winY() + 6, 14, 14};
	}

	private float[] tabBox(Tab t) {
		float x = winX() + Theme.width("TROLL") + 6 + 12 + Theme.width("centre") + 16;
		for (Tab other : Tab.values()) {
			float w = Theme.width(other.label) + 10;
			if (other == t) {
				return new float[]{x, winY() + 6, w, 14};
			}
			x += w + 2;
		}
		throw new IllegalArgumentException(t.name());
	}

	private List<float[]> actionBoxes(List<Action> actions) {
		List<float[]> boxes = new ArrayList<>();
		float gap = 5;
		float total = 0;
		float[] widths = new float[actions.size()];
		for (int i = 0; i < actions.size(); i++) {
			Action a = actions.get(i);
			widths[i] = Theme.width(a.label()) + Theme.width(a.hotkey()) + 6 + 16;
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
		Draw.screen(g, width, height);
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
		drawBody(g);
		if (tab == Tab.CHATROOM) {
			drawInput(g, mouseX, mouseY);
		}
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

	private void drawHeader(GuiGraphicsExtractor g, int mx, int my) {
		float x = winX(), y = winY(), w = winW();
		Draw.rect(g, x + 1, y + 3, w - 2, HEADER - 3, Theme.a(Theme.panel));
		Draw.rect(g, x + 1, y + HEADER, w - 2, 1, Theme.a(Theme.border));
		float tagW = Theme.width("TROLL") + 6;
		Draw.panel(g, x + 8, y + 8, tagW, 12, Theme.accent(0.05f));
		Draw.text(g, "TROLL", x + 11, y + 10, Theme.onAccent(), false);
		Draw.text(g, "centre", x + tagW + 12, y + 10, Theme.text);

		// the accent bar slides under whichever tab is open
		float[] current = tabBox(tab);
		if (!tabSnapped) {
			tabX.snap(current[0]);
			tabW.snap(current[2]);
			tabSnapped = true;
		}
		float ux = tabX.target(current[0]).update(dt);
		float uw = tabW.target(current[2]).update(dt);
		Draw.rect(g, ux + 3, y + HEADER - 4, uw - 6, 1, Theme.accent());
		for (Tab t : Tab.values()) {
			float[] b = tabBox(t);
			boolean over = in(mx, my, b);
			float hv = tabHover.computeIfAbsent(t, k -> new Anim(16)).target(over || t == tab).update(dt);
			Draw.text(g, t.label, b[0] + 5, b[1] + 4, ColorUtil.lerp(Theme.textDim, Theme.text, hv));
			if (over && t != tab) {
				g.requestCursor(CursorTypes.POINTING_HAND);
			}
		}

		float[] c = closeBox();
		boolean overClose = in(mx, my, c);
		if (overClose) {
			Draw.panel(g, c[0], c[1], c[2], c[3], Theme.panel2);
			g.requestCursor(CursorTypes.POINTING_HAND);
		}
		Draw.text(g, "x", c[0] + 4, c[1] + 3, overClose ? Theme.text : Theme.textDim);

		// sub-header: the tab's tagline types itself out; on the right, how things stand
		String info = info();
		float infoW = Theme.width(info);
		float age = (Motion.time() - tabChangedAt) * Motion.speed();
		String shown = tab.tagline.substring(0, (int) Math.min(tab.tagline.length(), Math.max(0, age - 0.1f) * 70));
		Draw.text(g, Draw.ellipsize(shown, (int) (bodyW() - infoW - 14)), bodyX() + 2, y + HEADER + 8, Theme.textDim);
		Draw.textRight(g, info, bodyX() + bodyW() - 2, y + HEADER + 8, Theme.textDim);
	}

	/** Right of the tagline: the last thing a button did for a few seconds, otherwise the tab's own state. */
	private String info() {
		if (Motion.time() - statusAt < 3f) {
			return status;
		}
		if (tab == Tab.NOTES) {
			String updater = Updater.status();
			return "running " + Site.version(TrollClient.MOD_ID) + (updater.isEmpty() ? "" : " / " + updater);
		}
		return switch (Chatroom.state()) {
			case "connected" -> Chatroom.online() + " online";
			case "connecting" -> "connecting" + ".".repeat((int) (Motion.time() * 2.5f) % 4);
			default -> Site.origin().isEmpty() ? "not in this build" : "off";
		};
	}

	private String emptyText() {
		if (tab == Tab.NOTES) {
			return "no notes came with this build";
		}
		return switch (Chatroom.state()) {
			case "connected" -> "nobody has said anything yet";
			case "connecting" -> "connecting to the room";
			default -> Site.origin().isEmpty() ? "this build has no chatroom" : "the chatroom is off";
		};
	}

	private void drawBody(GuiGraphicsExtractor g) {
		float bx = bodyX(), by = bodyY(), bw = bodyW(), bh = bodyH();
		Draw.panel(g, bx, by, bw, bh, Theme.a(Theme.panel));
		Draw.panelOutline(g, bx, by, bw, bh, Theme.a(Theme.border));

		buildRows(bw - 20);
		float view = bh - 8;
		float content = rows.size() * LINE_H;
		float maxScroll = Math.max(0, content - view);
		scrollTarget = follow ? maxScroll : Math.max(0, Math.min(maxScroll, scrollTarget));
		if (snapScroll) {
			scroll.snap(scrollTarget);
			snapScroll = false;
		}
		float sc = scroll.target(scrollTarget).update(dt);
		// a short chatroom sits at the bottom, next to where you type
		float top = by + 4 + (tab == Tab.CHATROOM ? Math.max(0, view - content) : 0);

		g.enableScissor(Math.round(bx + 1), Math.round(by + 1), Math.round(bx + bw - 1), Math.round(by + bh - 1));
		int msg = ColorUtil.lerp(Theme.textDim, Theme.text, 0.75f);
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			float ry = top + i * LINE_H - sc;
			if (ry + LINE_H < by || ry > by + bh || row.kind() == Kind.GAP) {
				continue;
			}
			float rx = bx + 8;
			int body = row.dim() ? Theme.textDim : msg;
			switch (row.kind()) {
				case HEADING -> {
					float tw = Theme.width(row.lead()) + 6;
					Draw.panel(g, rx - 2, ry - 1, tw, LINE_H, Theme.accent(0.05f));
					Draw.text(g, row.lead(), rx + 1, ry, Theme.onAccent(), false);
					Draw.text(g, row.text(), rx + tw + 4, ry, Theme.textDim);
				}
				case MORE -> Draw.text(g, row.text(), rx + row.indent(), ry, body);
				default -> {
					int lead = switch (row.kind()) {
						case OWN -> Theme.accent();
						case NAME -> Theme.text;
						default -> ColorUtil.fade(Theme.textDim, 0.85f);
					};
					Draw.text(g, row.lead(), rx, ry, lead);
					Draw.text(g, row.text(), rx + row.indent(), ry, body);
				}
			}
		}
		g.disableScissor();

		if (rows.isEmpty()) {
			Draw.textCentered(g, emptyText(), bx + bw / 2f, by + bh / 2f - 4, Theme.textDim);
		}
		if (maxScroll > 0) {
			float th = Math.max(12, view * view / content);
			float ty = by + 2 + (bh - 4 - th) * (sc / maxScroll);
			Draw.rect(g, bx + bw - 4, ty, 2, th, ColorUtil.fade(Theme.text, 0.35f));
			if (sc > 1) {
				Draw.gradientV(g, bx + 1, by + 1, bw - 6, 8, Theme.a(Theme.panel), ColorUtil.withAlpha(Theme.panel, 0));
			}
			if (sc < maxScroll - 1) {
				Draw.gradientV(g, bx + 1, by + bh - 9, bw - 6, 8, ColorUtil.withAlpha(Theme.panel, 0), Theme.a(Theme.panel));
			}
		}
	}

	private void drawInput(GuiGraphicsExtractor g, int mx, int my) {
		float[] b = inputBox();
		float f = inputFocus.target(input.isFocused()).update(dt);
		Draw.panel(g, b[0], b[1], b[2], b[3], Theme.a(Theme.panel2));
		Draw.panelOutline(g, b[0], b[1], b[2], b[3], ColorUtil.lerp(in(mx, my, b) ? Theme.borderHi : Theme.border, Theme.accent(), f));
		String prompt = minecraft.getUser().getName().toLowerCase(Locale.ROOT) + "> ";
		float pw = Theme.width(prompt);
		Draw.text(g, prompt, b[0] + 5, b[1] + 3, Theme.accent(0.15f));
		g.enableScissor(Math.round(b[0] + 3), Math.round(b[1]), Math.round(b[0] + b[2] - 3), Math.round(b[1] + b[3]));
		input.render(g, b[0] + 5 + pw, b[1] + 3, b[2] - 10 - pw, "say something to everyone, enter sends", 1f);
		g.disableScissor();
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
			Draw.text(g, a.hotkey(), tx + Theme.width(a.label()) + 6, b[1] + 4,
					!enabled ? ColorUtil.fade(Theme.textDim, 0.4f) : ColorUtil.lerp(Theme.textDim, Theme.onAccent(), hv * 0.7f), false);
			g.disableScissor();
			if (over) {
				g.requestCursor(CursorTypes.POINTING_HAND);
			}
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
		if (closing || event.button() != 0) {
			return true;
		}
		double mx = event.x(), my = event.y();
		if (in(mx, my, closeBox())) {
			onClose();
			return true;
		}
		for (Tab t : Tab.values()) {
			if (in(mx, my, tabBox(t))) {
				if (t != tab) {
					show(t);
					Sounds.tick();
				}
				return true;
			}
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
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scrollBy((float) -scrollY * LINE_H * 3);
		return true;
	}

	private void scrollBy(float amount) {
		float maxScroll = Math.max(0, rows.size() * LINE_H - (bodyH() - 8));
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget + amount));
		follow = tab == Tab.CHATROOM && scrollTarget >= maxScroll - 0.5f;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (closing) {
			return true;
		}
		int key = event.key();
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			onClose();
			return true;
		}
		if (key == GLFW.GLFW_KEY_TAB) {
			show(Tab.values()[(tab.ordinal() + 1) % Tab.values().length]);
			Sounds.tick();
			return true;
		}
		if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
			scrollBy((bodyH() - 8 - LINE_H) * (key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1));
			return true;
		}
		if (tab == Tab.CHATROOM) {
			input.setFocused(true);
			input.keyPressed(event);
			// enter lets go of the field; here it is the only thing to type into
			input.setFocused(true);
			return true;
		}
		if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
			scrollBy(LINE_H * 3 * (key == GLFW.GLFW_KEY_UP ? -1 : 1));
		}
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (closing || tab != Tab.CHATROOM) {
			return true;
		}
		input.setFocused(true);
		return input.charTyped(event.codepointAsString());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
