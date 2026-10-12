package com.trollclient.command;

import com.trollclient.gui.Draw;
import com.trollclient.gui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tips above the chat box while a client command is being typed: the commands that match so far,
 * then, once the command is there, what can go in the argument under the cursor (modules, settings,
 * modes, players, macros) and a line saying what the command takes. Tab fills in the highlighted
 * one; Up and Down move the highlight when there's more than one.
 *
 * <p>Vanilla only does this for {@code /} commands, and those come from the server. Ours never
 * leave the client, so everything here is worked out from {@link Commands#specs()}.
 */
public final class CommandTips {
	private static final int MAX_ROWS = 8;
	private static final int PAD = 4;

	/** {@code text} is what Tab types; {@code hint} says what it is. */
	private record Tip(String text, String hint) {
	}

	private static String seenText;
	private static int seenCursor = -1;
	private static List<Tip> tips = List.of();
	private static String usage = "";
	/** Where the word being completed starts in the chat box. */
	private static int wordStart;
	private static int selected;

	private CommandTips() {
	}

	/** Works out the tips for this text and cursor, unless they're the ones we already have. */
	private static void update(String text, int cursor) {
		cursor = Math.max(0, Math.min(cursor, text.length()));
		if (text.equals(seenText) && cursor == seenCursor) {
			return;
		}
		seenText = text;
		seenCursor = cursor;
		selected = 0;
		tips = List.of();
		usage = "";

		String prefix = Commands.prefix();
		if (!text.startsWith(prefix) || cursor < prefix.length()) {
			return;
		}
		String body = text.substring(prefix.length(), cursor);
		int space = body.indexOf(' ');
		if (space < 0) {
			commandTips(prefix, body.toLowerCase(Locale.ROOT));
			return;
		}
		Commands.Spec spec = Commands.spec(body.substring(0, space));
		if (spec == null) {
			usage = "no such command. " + prefix + "help lists them";
			return;
		}
		// the words typed in full, and the (maybe empty) one under the cursor
		String[] words = body.substring(space + 1).split(" ", -1);
		String partial = words[words.length - 1];
		List<String> before = new ArrayList<>();
		for (int i = 0; i < words.length - 1; i++) {
			if (!words[i].isEmpty()) {
				before.add(words[i]);
			}
		}
		wordStart = cursor - partial.length();
		List<Tip> found = new ArrayList<>();
		for (String option : matching(spec.completer().complete(before), partial)) {
			found.add(new Tip(option, ""));
		}
		// nothing left to say once the only option is typed out
		if (found.size() == 1 && found.get(0).text().equalsIgnoreCase(partial)) {
			found.clear();
		}
		tips = found;
		String hint = spec.name().equals("set") ? Commands.settingHint(before) : null;
		usage = hint != null ? hint : spec.usage(prefix) + "  -  " + spec.description();
	}

	private static void commandTips(String prefix, String partial) {
		wordStart = prefix.length();
		List<Tip> found = new ArrayList<>();
		for (Commands.Spec spec : Commands.specs()) {
			boolean byAlias = !partial.isEmpty() && spec.aliases().stream().anyMatch(a -> a.startsWith(partial));
			if (spec.name().startsWith(partial) || byAlias) {
				found.add(new Tip(spec.name(), spec.args().isEmpty() ? spec.description() : spec.args() + "  " + spec.description()));
			}
		}
		if (found.isEmpty()) {
			usage = "no such command. " + prefix + "help lists them";
		}
		tips = found;
	}

	/** Options that start with what's typed; failing that, ones that contain it. Case doesn't matter. */
	private static List<String> matching(List<String> options, String partial) {
		String needle = partial.toLowerCase(Locale.ROOT);
		List<String> starts = new ArrayList<>();
		List<String> contains = new ArrayList<>();
		for (String option : options) {
			String lower = option.toLowerCase(Locale.ROOT);
			if (lower.startsWith(needle)) {
				starts.add(option);
			} else if (needle.length() >= 2 && lower.contains(needle)) {
				contains.add(option);
			}
		}
		return starts.isEmpty() ? contains : starts;
	}

	/** @return true if the key was ours (Tab to complete, Up/Down to pick) and vanilla shouldn't see it. */
	public static boolean keyPressed(EditBox input, KeyEvent event) {
		update(input.getValue(), input.getCursorPosition());
		if (tips.isEmpty()) {
			return false;
		}
		int key = event.key();
		if (key == GLFW.GLFW_KEY_TAB) {
			Tip tip = tips.get(Math.min(selected, tips.size() - 1));
			String text = input.getValue();
			int cursor = Math.min(input.getCursorPosition(), text.length());
			// a space after it, unless there's already one waiting
			String insert = tip.text() + (text.startsWith(" ", cursor) ? "" : " ");
			input.setValue(text.substring(0, wordStart) + insert + text.substring(cursor));
			input.moveCursorTo(wordStart + insert.length(), false);
			return true;
		}
		if (tips.size() > 1 && (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN)) {
			selected = Math.floorMod(selected + (key == GLFW.GLFW_KEY_UP ? -1 : 1), tips.size());
			return true;
		}
		return false;
	}

	public static void render(GuiGraphicsExtractor g, EditBox input) {
		update(input.getValue(), input.getCursorPosition());
		if (tips.isEmpty() && usage.isEmpty()) {
			return;
		}
		Theme.update();
		float alpha = Draw.alpha;
		Draw.alpha = 1f;

		int rowHeight = Theme.lineHeight() + 3;
		int rows = Math.min(MAX_ROWS, tips.size());
		int first = Math.max(0, Math.min(selected - rows / 2, tips.size() - rows));
		String count = tips.size() > rows ? (selected + 1) + "/" + tips.size() : "";
		int width = usage.isEmpty() ? 0 : Theme.width(usage) + (count.isEmpty() ? 0 : Theme.width(count) + 10);
		for (int i = first; i < first + rows; i++) {
			Tip tip = tips.get(i);
			width = Math.max(width, Theme.width(tip.text()) + (tip.hint().isEmpty() ? 0 : Theme.width(tip.hint()) + 12));
		}
		width = Math.min(width + PAD * 2 + 3, g.guiWidth() - 4);
		boolean footer = !usage.isEmpty() || !count.isEmpty();
		int height = rows * rowHeight + (footer ? rowHeight + (rows > 0 ? 1 : 0) : 0) + 2;
		// lined up with the word being typed, kept on screen, sitting just above the chat box
		float x = Math.max(2, Math.min(input.getScreenX(wordStart) - PAD - 3, g.guiWidth() - width - 2));
		float y = input.getY() - 4 - height;

		Draw.panel(g, x, y, width, height, Theme.a(Theme.panel));
		Draw.panelOutline(g, x, y, width, height, Theme.borderHi);
		float rowY = y + 1;
		for (int i = first; i < first + rows; i++) {
			Tip tip = tips.get(i);
			boolean on = i == selected;
			if (on) {
				Draw.rect(g, x + 1, rowY, width - 2, rowHeight, Theme.a(Theme.panel2));
				Draw.rect(g, x + 1, rowY, 2, rowHeight, Theme.accent());
			}
			Draw.text(g, tip.text(), x + PAD + 3, rowY + 2, on ? Theme.text : Theme.textDim);
			if (!tip.hint().isEmpty()) {
				String hint = Draw.ellipsize(tip.hint(), (int) (width - PAD * 2 - 15 - Theme.width(tip.text())));
				Draw.textRight(g, hint, x + width - PAD, rowY + 2, Theme.textDim);
			}
			rowY += rowHeight;
		}
		if (footer) {
			if (rows > 0) {
				Draw.rect(g, x + 1, rowY, width - 2, 1, Theme.border);
				rowY += 1;
			}
			int room = (int) (width - PAD * 2 - 3 - (count.isEmpty() ? 0 : Theme.width(count) + 10));
			Draw.text(g, Draw.ellipsize(usage, room), x + PAD + 3, rowY + 2, rows > 0 ? Theme.textDim : Theme.text);
			if (!count.isEmpty()) {
				Draw.textRight(g, count, x + width - PAD, rowY + 2, Theme.textDim);
			}
		}
		Draw.alpha = alpha;
	}
}
