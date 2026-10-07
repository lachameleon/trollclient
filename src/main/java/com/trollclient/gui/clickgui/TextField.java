package com.trollclient.gui.clickgui;

import com.trollclient.gui.Draw;
import com.trollclient.gui.Theme;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Single-line text input with a block cursor and optional tab completion. */
public class TextField {
	private String text = "";
	private int cursor;
	private boolean focused;
	private final int maxLength;
	private long focusTime;
	private Consumer<String> onChange = s -> {};
	private Runnable onSubmit = () -> {};
	private Supplier<List<String>> suggestions = List::of;

	public TextField(int maxLength) {
		this.maxLength = maxLength;
	}

	public TextField onChange(Consumer<String> onChange) {
		this.onChange = onChange;
		return this;
	}

	public TextField onSubmit(Runnable onSubmit) {
		this.onSubmit = onSubmit;
		return this;
	}

	public TextField suggestions(Supplier<List<String>> suggestions) {
		this.suggestions = suggestions;
		return this;
	}

	public String getText() {
		return text;
	}

	public void setText(String text) {
		this.text = text.length() > maxLength ? text.substring(0, maxLength) : text;
		this.cursor = this.text.length();
	}

	public boolean isFocused() {
		return focused;
	}

	public void setFocused(boolean focused) {
		if (focused && !this.focused) {
			focusTime = System.currentTimeMillis();
			cursor = text.length();
		}
		this.focused = focused;
	}

	/** First suggestion that extends what's typed, used for ghost text and Tab. */
	public String completion() {
		if (text.isEmpty()) {
			return null;
		}
		for (String s : suggestions.get()) {
			if (s.length() > text.length() && s.toLowerCase().startsWith(text.toLowerCase())) {
				return s;
			}
		}
		return null;
	}

	public boolean keyPressed(KeyEvent event) {
		if (!focused) {
			return false;
		}
		int key = event.key();
		if (event.isPaste()) {
			insert(Minecraft.getInstance().keyboardHandler.getClipboard());
			return true;
		}
		if (event.isSelectAll()) {
			cursor = text.length();
			return true;
		}
		switch (key) {
			case GLFW.GLFW_KEY_BACKSPACE -> {
				if (cursor > 0) {
					if (event.hasControlDown()) {
						int start = previousWord();
						text = text.substring(0, start) + text.substring(cursor);
						cursor = start;
					} else {
						text = text.substring(0, cursor - 1) + text.substring(cursor);
						cursor--;
					}
					onChange.accept(text);
				}
			}
			case GLFW.GLFW_KEY_DELETE -> {
				if (cursor < text.length()) {
					text = text.substring(0, cursor) + text.substring(cursor + 1);
					onChange.accept(text);
				}
			}
			case GLFW.GLFW_KEY_LEFT -> cursor = Math.max(0, cursor - 1);
			case GLFW.GLFW_KEY_RIGHT -> cursor = Math.min(text.length(), cursor + 1);
			case GLFW.GLFW_KEY_HOME -> cursor = 0;
			case GLFW.GLFW_KEY_END -> cursor = text.length();
			case GLFW.GLFW_KEY_TAB -> {
				String c = completion();
				if (c != null) {
					setText(c);
					onChange.accept(text);
				}
			}
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				onSubmit.run();
				setFocused(false);
			}
			case GLFW.GLFW_KEY_ESCAPE -> setFocused(false);
			default -> {
				return false;
			}
		}
		focusTime = System.currentTimeMillis();
		return true;
	}

	public boolean charTyped(String chars) {
		if (!focused) {
			return false;
		}
		insert(chars);
		return true;
	}

	private void insert(String s) {
		StringBuilder clean = new StringBuilder();
		for (char c : s.toCharArray()) {
			if (c >= 32 && c != 127) {
				clean.append(c);
			}
		}
		int room = maxLength - text.length();
		if (room <= 0 || clean.isEmpty()) {
			return;
		}
		String add = clean.length() > room ? clean.substring(0, room) : clean.toString();
		text = text.substring(0, cursor) + add + text.substring(cursor);
		cursor += add.length();
		focusTime = System.currentTimeMillis();
		onChange.accept(text);
	}

	private int previousWord() {
		int i = cursor - 1;
		while (i > 0 && text.charAt(i - 1) == ' ') {
			i--;
		}
		while (i > 0 && text.charAt(i - 1) != ' ') {
			i--;
		}
		return Math.max(0, i);
	}

	/** Draws the field contents (no frame) clipped to the given width. */
	public void render(GuiGraphicsExtractor g, float x, float y, float w, String placeholder, float alpha) {
		int textColor = ColorUtil.fade(Theme.text, alpha);
		int dim = ColorUtil.fade(Theme.textDim, alpha * 0.8f);
		String visible = text;
		int cursorPos = cursor;
		// scroll the text left so the cursor stays in view
		while (Theme.width(visible.substring(0, Math.min(cursorPos, visible.length()))) > w - 6 && !visible.isEmpty()) {
			visible = visible.substring(1);
			cursorPos--;
		}
		if (text.isEmpty() && !focused) {
			Draw.text(g, placeholder, x, y, dim);
		} else {
			Draw.text(g, Draw.ellipsize(visible, (int) w), x, y, textColor);
			String completion = focused ? completion() : null;
			if (completion != null && cursor == text.length()) {
				Draw.text(g, completion.substring(text.length()), x + Theme.width(visible), y, dim);
			}
		}
		if (focused) {
			long since = System.currentTimeMillis() - focusTime;
			if ((since / 500) % 2 == 0) {
				float cx = x + Theme.width(visible.substring(0, Math.max(0, Math.min(cursorPos, visible.length()))));
				Draw.rect(g, cx, y - 1, cursor == text.length() ? 5 : 1, 9, ColorUtil.fade(Theme.accent(), alpha * 0.85f));
			}
		}
	}
}
