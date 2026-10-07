package com.trollclient.gui.clickgui.components;

import com.mojang.blaze3d.platform.InputConstants;
import com.trollclient.gui.Anim;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Icon;
import com.trollclient.gui.Motion;
import com.trollclient.gui.Sounds;
import com.trollclient.gui.Theme;
import com.trollclient.module.Module;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Click, then press a key. Escape / Backspace / Delete unbinds. */
public class BindRow extends Row {
	private final IntSupplier key;
	private final IntConsumer setKey;
	private final Anim listen = new Anim(14);
	private boolean listening;
	private float listenStart;

	public BindRow(String label, String description, IntSupplier key, IntConsumer setKey) {
		super(label, description, () -> true);
		this.key = key;
		this.setKey = setKey;
	}

	public BindRow(Module module) {
		this("Keybind", "Key that toggles this module", module::getKey, module::setKey);
	}

	public static String keyName(int key) {
		if (key <= 0) {
			return "none";
		}
		return switch (key) {
			case GLFW.GLFW_KEY_RIGHT_SHIFT -> "rshift";
			case GLFW.GLFW_KEY_LEFT_SHIFT -> "lshift";
			case GLFW.GLFW_KEY_RIGHT_CONTROL -> "rctrl";
			case GLFW.GLFW_KEY_LEFT_CONTROL -> "lctrl";
			case GLFW.GLFW_KEY_RIGHT_ALT -> "ralt";
			case GLFW.GLFW_KEY_LEFT_ALT -> "lalt";
			default -> InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString().toLowerCase(Locale.ROOT);
		};
	}

	@Override
	public float height() {
		return 16;
	}

	@Override
	public void render(GuiGraphicsExtractor g, float mx, float my, float dt) {
		float hv = hover.target(isHovered(mx, my)).update(dt);
		float l = listen.target(listening).update(dt);
		Draw.text(g, label, x + 2 + hv * 2, y + 4, ColorUtil.lerp(Theme.textDim, Theme.text, Math.max(hv, l)));

		String text;
		if (listening) {
			int dots = (int) ((Motion.time() - listenStart) * 4) % 4;
			text = "press a key" + ".".repeat(dots);
		} else {
			text = keyName(key.getAsInt());
		}
		// size the box for the longest prompt so it doesn't twitch as the dots animate
		float tw = Theme.width(listening ? "press a key..." : text);
		float bw = tw + 22;
		float bx = x + w - bw - 2;
		Draw.panel(g, bx, y + 2, bw, 12, ColorUtil.lerp(Theme.panel2, Theme.accent(), l * 0.9f));
		Draw.panelOutline(g, bx, y + 2, bw, 12, ColorUtil.lerp(Theme.border, Theme.borderHi, hv));
		int fg = ColorUtil.lerp(key.getAsInt() > 0 ? Theme.text : Theme.textDim, Theme.onAccent(), l);
		Draw.icon(g, Icon.KEYBOARD, bx + 3, y + 4, 8, fg);
		Draw.text(g, text, bx + 15, y + 4, fg);
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (!isHovered(mx, my)) {
			if (listening) {
				listening = false;
			}
			return false;
		}
		if (button == 1) {
			setKey.accept(GLFW.GLFW_KEY_UNKNOWN);
			listening = false;
		} else {
			listening = !listening;
			listenStart = Motion.time();
		}
		Sounds.click();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!listening) {
			return false;
		}
		int pressed = event.key();
		if (pressed == GLFW.GLFW_KEY_ESCAPE || pressed == GLFW.GLFW_KEY_BACKSPACE || pressed == GLFW.GLFW_KEY_DELETE) {
			setKey.accept(GLFW.GLFW_KEY_UNKNOWN);
		} else {
			setKey.accept(pressed);
		}
		listening = false;
		Sounds.toggle(true);
		return true;
	}

	@Override
	public boolean isCapturing() {
		return listening;
	}

	@Override
	public void unfocus() {
		listening = false;
	}

	@Override
	public String getDescription() {
		return "Click and press a key. Right click or Esc to unbind.";
	}
}
