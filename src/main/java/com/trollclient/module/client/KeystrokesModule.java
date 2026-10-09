package com.trollclient.module.client;

import com.trollclient.gui.Anim;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Theme;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/** WASD, space and mouse buttons on the HUD, lighting up as you press them. In the theme's colours, of course. */
public class KeystrokesModule extends Module {
	private static final float KEY = 16;
	private static final float GAP = 2;

	public final NumberSetting x = add(new NumberSetting("X", "Horizontal position, from the left edge", 1, 0, 100, 0.5).unit("%"));
	public final NumberSetting y = add(new NumberSetting("Y", "Vertical position, from the top edge", 45, 0, 100, 0.5).unit("%"));
	public final NumberSetting scale = add(new NumberSetting("Scale", "Size of the keys", 1.0, 0.5, 2.0, 0.05).unit("x"));
	public final BoolSetting mouse = add(new BoolSetting("Mouse", "Show the mouse buttons", true));
	public final BoolSetting cps = add(new BoolSetting("CPS", "Clicks per second on the mouse buttons", true)).visibleWhen(mouse::get);
	public final BoolSetting space = add(new BoolSetting("Space", "Show the space bar", true));

	private final Anim[] glow = {new Anim(18), new Anim(18), new Anim(18), new Anim(18), new Anim(18), new Anim(18), new Anim(18)};
	private final Deque<Long> leftClicks = new ArrayDeque<>();
	private final Deque<Long> rightClicks = new ArrayDeque<>();
	private boolean leftWasDown;
	private boolean rightWasDown;

	public KeystrokesModule() {
		super("Keystrokes", "WASD, space and mouse buttons on the HUD, lighting up as you press them, with clicks per second.",
				Category.CLIENT);
	}

	/** Called from the HUD renderer. */
	public void render(GuiGraphicsExtractor g, int width, int height, float dt) {
		boolean leftDown = mc.options.keyAttack.isDown();
		boolean rightDown = mc.options.keyUse.isDown();
		long now = System.currentTimeMillis();
		if (leftDown && !leftWasDown) {
			leftClicks.add(now);
		}
		if (rightDown && !rightWasDown) {
			rightClicks.add(now);
		}
		leftWasDown = leftDown;
		rightWasDown = rightDown;
		while (!leftClicks.isEmpty() && now - leftClicks.peek() > 1000) {
			leftClicks.poll();
		}
		while (!rightClicks.isEmpty() && now - rightClicks.peek() > 1000) {
			rightClicks.poll();
		}

		float s = scale.getFloat();
		float total = KEY * 3 + GAP * 2;
		float h = KEY * 2 + GAP + (mouse.get() ? KEY + GAP : 0) + (space.get() ? 9 + GAP : 0);
		float px = Math.max(2, Math.min(width - total * s - 2, x.getFloat() / 100f * width));
		float py = Math.max(2, Math.min(height - h * s - 2, y.getFloat() / 100f * height));
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(px, py);
		pose.scale(s, s);
		key(g, 0, KEY + GAP, 0, KEY, KEY, label(mc.options.keyUp), mc.options.keyUp.isDown(), dt);
		key(g, 1, 0, KEY + GAP, KEY, KEY, label(mc.options.keyLeft), mc.options.keyLeft.isDown(), dt);
		key(g, 2, KEY + GAP, KEY + GAP, KEY, KEY, label(mc.options.keyDown), mc.options.keyDown.isDown(), dt);
		key(g, 3, (KEY + GAP) * 2, KEY + GAP, KEY, KEY, label(mc.options.keyRight), mc.options.keyRight.isDown(), dt);
		float row = (KEY + GAP) * 2;
		if (mouse.get()) {
			float half = (total - GAP) / 2f;
			key(g, 4, 0, row, half, KEY, clicks("LMB", leftClicks.size()), leftDown, dt);
			key(g, 5, half + GAP, row, half, KEY, clicks("RMB", rightClicks.size()), rightDown, dt);
			row += KEY + GAP;
		}
		if (space.get()) {
			boolean down = mc.options.keyJump.isDown();
			float lit = glow[6].target(down).update(dt);
			fill(g, 0, row, total, 9, lit);
			// a bar instead of a word: it's the space bar, everyone knows
			Draw.rect(g, total / 2f - 10, row + 4, 20, 1, ColorUtil.lerp(Theme.textDim, Theme.onAccent(), lit));
		}
		pose.popMatrix();
	}

	/** The button's name while idle, its clicks per second while you're clicking. */
	private String clicks(String name, int count) {
		return cps.get() && count > 0 ? Integer.toString(count) : name;
	}

	private void key(GuiGraphicsExtractor g, int index, float kx, float ky, float w, float h, String text, boolean down, float dt) {
		float lit = glow[index].target(down).update(dt);
		fill(g, kx, ky, w, h, lit);
		Draw.textCentered(g, Draw.ellipsize(text, Math.round(w - 2)), kx + w / 2f, ky + (h - 8) / 2f,
				ColorUtil.lerp(Theme.text, Theme.onAccent(), lit));
	}

	private static void fill(GuiGraphicsExtractor g, float kx, float ky, float w, float h, float lit) {
		Draw.panel(g, kx, ky, w, h, Theme.a(ColorUtil.lerp(ColorUtil.withAlpha(Theme.panel, 200), Theme.accent(kx / 52f), lit)));
		Draw.panelOutline(g, kx, ky, w, h, ColorUtil.lerp(Theme.border, Theme.accent(kx / 52f), lit));
	}

	/** The key's actual binding, so AZERTY players see Z Q S D. */
	private static String label(KeyMapping key) {
		String name = key.getTranslatedKeyMessage().getString();
		return name.length() <= 3 ? name.toUpperCase(Locale.ROOT) : name.substring(0, 1).toUpperCase(Locale.ROOT);
	}
}
