package com.trollclient.gui.title;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.mojang.realmsclient.RealmsMainScreen;
import com.trollclient.TrollClient;
import com.trollclient.gui.Anim;
import com.trollclient.gui.Backdrop;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Motion;
import com.trollclient.gui.ScreenFx;
import com.trollclient.gui.Sounds;
import com.trollclient.gui.Theme;
import com.trollclient.gui.clickgui.ClickGuiScreen;
import com.trollclient.gui.clickgui.TerminalLog;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.ThemeModule;
import com.trollclient.module.client.TitleScreenModule;
import com.trollclient.util.ColorUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * Demoscene-flavoured main menu: chunky 3D pixel logo, sine scroller, and a
 * background that follows the theme (warp starfield for Noir, binary rain for
 * Terminal, halftone for Newsprint...). Every Theme option shows up here too:
 * palette, font, accent, corners, glow, dither shadows and the invert flash.
 */
public class TrollTitleScreen extends Screen {
	private static boolean introPlayed;
	/** Backdrops are drawn a little oversized so parallax never shows their edges. */
	private static final int PARALLAX_PAD = 8;
	private static final float SCENE_FADE = 0.6f;

	private static final String LOGO = "TROLL CLIENT";
	private static final String TAGLINE = "a client for being mildly annoying";
	private static final String[] SPLASHES = {
			"now with 100% more twerking!", "crystals? cancelled.", "no way home!", "nom nom nom (never finishes)",
			"wind charge to the face!", "follow me!", "arrows? nope.", "certified annoying", "black & white & read all over",
			"right shift for fun", "your path is blocked", "it's not a bug, it's a troll", "26.2 compatible!",
			"skeet? never heard of it", "fabric, not cotton"
	};
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

	private record Item(String label, String hint, Runnable action, BooleanSupplier enabled) {
	}

	private record Ring(float x, float y, float born) {
	}

	private final List<Item> items = new ArrayList<>();
	private final List<PixelFont.Pixel> logoPixels = PixelFont.layout(LOGO);
	private final int logoWidth = PixelFont.width(LOGO);
	private final Random random = new Random();
	private final String splash = SPLASHES[new Random().nextInt(SPLASHES.length)];
	private final List<Ring> rings = new ArrayList<>();

	private float[] starX = new float[0];
	private float[] starY = new float[0];
	private float[] starZ = new float[0];
	private final Anim warp = new Anim(4);
	private final Anim selY = new Anim(18);
	private final Anim selShown = new Anim(14);
	private final Anim[] itemHover;
	/** When each item last became selected, for the "decrypt" scramble on its label. */
	private final float[] selectedAt;
	private int lastSelected = -1;
	private final Anim vanillaHover = new Anim(16);
	private final Anim themeHover = new Anim(16);
	private final Backdrop backdrop = new Backdrop();
	private final float openedAt = Motion.time();
	private final boolean playIntro;

	/** Background showing now, and the one fading out after a theme change (null once it's gone). */
	private String scene;
	private String fromScene;
	private float sceneChangedAt = -10;
	private float themeFlashAt = -10;

	private long lastNanos;
	private float dt;
	private int selected = -1;
	private int lastMouseX = -1;
	private int lastMouseY = -1;
	private boolean keyboardMode;
	private float scrollOffset;

	public TrollTitleScreen() {
		super(Component.literal("Troll Client"));
		items.add(new Item("singleplayer", "play by yourself (boring)", () -> minecraft.gui.setScreen(new SelectWorldScreen(this)), () -> true));
		items.add(new Item("multiplayer", "where the trolling happens", () -> minecraft.gui.setScreen(minecraft.options.skipMultiplayerWarning
				? new JoinMultiplayerScreen(this) : new SafetyScreen(this)), () -> minecraft.allowsMultiplayer()));
		items.add(new Item("realms", "trolling, but subscription based", () -> minecraft.gui.setScreen(new RealmsMainScreen(this)),
				() -> minecraft.allowsRealms()));
		if (FabricLoader.getInstance().isModLoaded("modmenu")) {
			// we replace the menu that Mod Menu's button lives on, so offer it here instead
			items.add(new Item("mods", "everything else you've installed", this::openModMenu, () -> true));
		}
		items.add(new Item("options", "the vanilla kind", () -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options, false)), () -> true));
		items.add(new Item("troll client", "modules, themes, the good stuff", () -> minecraft.gui.setScreen(new ClickGuiScreen(this)), () -> true));
		items.add(new Item("quit", "rage quit", () -> minecraft.stop(), () -> true));
		itemHover = new Anim[items.size()];
		selectedAt = new float[items.size()];
		for (int i = 0; i < itemHover.length; i++) {
			itemHover[i] = new Anim(16);
		}
		playIntro = !introPlayed;
		introPlayed = true;
	}

	/** Centre of a menu item in GUI coordinates (used by the showcase recorder's cursor). */
	public double[] itemCenter(int index) {
		return new double[]{menuX() + menuW() / 2f, menuTop() + index * itemH() + itemH() / 2f - 1};
	}

	private void openModMenu() {
		try {
			Class<?> mods = Class.forName("com.terraformersmc.modmenu.gui.ModsScreen");
			minecraft.gui.setScreen((Screen) mods.getConstructor(Screen.class).newInstance(this));
		} catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
			TrollClient.LOGGER.warn("Couldn't open Mod Menu's screen", e);
			Sounds.toggle(false);
		}
	}

	private TitleScreenModule settings() {
		return ModuleManager.get(TitleScreenModule.class);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private float age() {
		return (Motion.time() - openedAt) * Motion.speed();
	}

	// ------------------------------------------------------------------ layout helpers

	private int pixelSize() {
		return Math.max(2, Math.min(7, (int) (width * 0.72f / logoWidth)));
	}

	private float logoX() {
		return (width - logoWidth * pixelSize()) / 2f;
	}

	private float logoY() {
		return Math.max(16, height * 0.16f);
	}

	private float menuTop() {
		return logoY() + 7 * pixelSize() + 34;
	}

	private float itemH() {
		return 15;
	}

	private float menuW() {
		return 150;
	}

	private float menuX() {
		return (width - menuW()) / 2f;
	}

	private int itemAt(double mx, double my) {
		float top = menuTop();
		if (mx < menuX() - 10 || mx > menuX() + menuW() + 10 || my < top) {
			return -1;
		}
		int i = (int) ((my - top) / itemH());
		return i >= 0 && i < items.size() ? i : -1;
	}

	private float[] vanillaBox() {
		String label = "[ vanilla menu ]";
		return new float[]{6, 6, Theme.width(label) + 4, 12};
	}

	private String themeLabel() {
		return "[ theme: " + Theme.settings().preset.get().toLowerCase(Locale.ROOT) + " ]";
	}

	/** Right of the vanilla button; null when the Theme Button setting is off. */
	private float[] themeBox() {
		if (!settings().themeButton.get()) {
			return null;
		}
		float[] vb = vanillaBox();
		return new float[]{vb[0] + vb[2] + 6, vb[1], Theme.width(themeLabel()) + 4, vb[3]};
	}

	private static boolean inBox(double mx, double my, float[] b) {
		return b != null && mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
	}

	/** Next (or previous) theme preset, with the same flash, sound and shell log as the ClickGUI's palette button. */
	private void cycleTheme(int direction) {
		ThemeModule t = Theme.settings();
		t.preset.cycle(direction);
		themeFlashAt = Motion.time();
		Sounds.toggle(true);
		TerminalLog.push("theme --preset " + t.preset.get().toLowerCase(Locale.ROOT));
	}

	/**
	 * Dithered drop shadows: black reads on the light presets, but on the dark
	 * ones (where there's no world behind to darken) a dim grey checkerboard does.
	 */
	private static int shadowColor() {
		return Theme.light ? ColorUtil.withAlpha(0x000000, 110) : ColorUtil.fade(Theme.textDim, 0.4f);
	}

	// ------------------------------------------------------------------ render

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		Theme.update();
		Draw.rect(g, 0, 0, width, height, 0xFF000000 | Theme.bg);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		long now = System.nanoTime();
		dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
		lastNanos = now;
		Theme.update();
		TitleScreenModule s = settings();

		if (mouseX != lastMouseX || mouseY != lastMouseY) {
			lastMouseX = mouseX;
			lastMouseY = mouseY;
			int over = itemAt(mouseX, mouseY);
			if (over >= 0 || !keyboardMode) {
				keyboardMode = false;
				selected = over;
			}
		}

		float px = s.parallax.get() ? (mouseX - width / 2f) * -0.025f : 0;
		float py = s.parallax.get() ? (mouseY - height / 2f) * -0.025f : 0;
		drawScene(g, s, px, py);
		if (!scene.equals("Stars") && !scene.equals("None")) {
			drawMenuClearing(g);
		}
		drawRings(g);

		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(px * 0.4f, py * 0.4f);
		drawLogo(g, s);
		pose.popMatrix();

		drawMenu(g, mouseX, mouseY);
		if (s.scroller.get()) {
			drawScroller(g, s.scrollerText.get());
		}
		drawChrome(g, mouseX, mouseY);
		if (playIntro) {
			drawBootLog(g);
		}

		ScreenFx.overlays(g, width, height, 1f);
		if (Theme.settings().flash.get() && Motion.time() - themeFlashAt < 0.09f) {
			// negative flash on a theme change, like the ClickGUI's
			g.nextStratum();
			Draw.invert(g, 0, 0, width, height);
		}
		if (playIntro) {
			drawIntro(g);
		}
	}

	/** The theme's background, crossfading from the previous one when the preset (or setting) changes. */
	private void drawScene(GuiGraphicsExtractor g, TitleScreenModule s, float px, float py) {
		String now = s.scene();
		if (!now.equals(scene)) {
			fromScene = scene;
			scene = now;
			sceneChangedAt = Motion.time();
		}
		float t = fromScene == null ? 1f : Motion.range(Motion.time() - sceneChangedAt, 0, SCENE_FADE);
		if (t >= 1f) {
			fromScene = null;
		} else {
			drawSceneLayer(g, s, fromScene, px, py, 1f - t);
		}
		drawSceneLayer(g, s, scene, px, py, t);
	}

	/**
	 * Busy backdrops (rain, pen traces) would run through the menu labels: a
	 * soft clearing of background colour behind the menu, built from stacked
	 * translucent boxes so its edges fade out in steps.
	 */
	private void drawMenuClearing(GuiGraphicsExtractor g) {
		float top = menuTop() - 6;
		float bottom = menuTop() + items.size() * itemH() + 18;
		int layers = 6;
		for (int i = 0; i < layers; i++) {
			float insetX = i * 9;
			float insetY = i * 2.5f;
			Draw.rect(g, menuX() - 60 + insetX, top + insetY, menuW() + 120 - insetX * 2, bottom - top - insetY * 2,
					ColorUtil.fade(Theme.bg, 0.13f));
		}
	}

	private void drawSceneLayer(GuiGraphicsExtractor g, TitleScreenModule s, String mode, float px, float py, float alpha) {
		if (mode.equals("Stars")) {
			drawStars(g, s, px, py, alpha);
			return;
		}
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(px * 0.6f - PARALLAX_PAD, py * 0.6f - PARALLAX_PAD);
		backdrop.render(g, mode, width + PARALLAX_PAD * 2, height + PARALLAX_PAD * 2, dt, alpha);
		pose.popMatrix();
	}

	private void ensureStars(int count) {
		if (starX.length == count) {
			return;
		}
		starX = new float[count];
		starY = new float[count];
		starZ = new float[count];
		for (int i = 0; i < count; i++) {
			respawn(i, random.nextFloat());
		}
	}

	private void respawn(int i, float z) {
		starX[i] = (random.nextFloat() * 2 - 1) * 1.2f;
		starY[i] = (random.nextFloat() * 2 - 1) * 1.2f;
		starZ[i] = Math.max(0.02f, z);
	}

	private void drawStars(GuiGraphicsExtractor g, TitleScreenModule s, float px, float py, float alpha) {
		ensureStars(s.stars.getInt());
		boolean hovering = selected >= 0 && s.warp.get();
		float speed = 0.06f + warp.target(hovering ? 1 : 0).update(dt) * 0.75f;
		float ringBoost = rings.isEmpty() ? 0 : 0.4f;
		speed += ringBoost;
		float cx = width / 2f + px;
		float cy = height / 2f + py;
		float fov = Math.max(width, height) * 0.5f;
		int bright = Theme.text;
		for (int i = 0; i < starZ.length; i++) {
			float oldZ = starZ[i];
			starZ[i] -= speed * dt * Motion.speed();
			if (starZ[i] <= 0.02f) {
				respawn(i, 1f);
				continue;
			}
			float z = starZ[i];
			float sx = cx + starX[i] / z * fov;
			float sy = cy + starY[i] / z * fov;
			if (sx < -4 || sy < -4 || sx > width + 4 || sy > height + 4) {
				respawn(i, 1f);
				continue;
			}
			float depth = 1f - z;
			float size = depth > 0.85f ? 2 : 1;
			int color = ColorUtil.fade(bright, (0.15f + depth * 0.85f) * alpha);
			// streak: a few fading dots back along the star's path
			float tz = Math.min(1f, oldZ + speed * 0.06f);
			float tx = cx + starX[i] / tz * fov;
			float ty = cy + starY[i] / tz * fov;
			int steps = (int) Math.min(6, Math.hypot(sx - tx, sy - ty));
			for (int k = 1; k <= steps; k++) {
				float f = k / (float) (steps + 1);
				Draw.rect(g, sx + (tx - sx) * f, sy + (ty - sy) * f, 1, 1, ColorUtil.fade(color, (1 - f) * 0.5f));
			}
			Draw.rect(g, sx, sy, size, size, color);
		}
	}

	private void drawRings(GuiGraphicsExtractor g) {
		float now = Motion.time();
		rings.removeIf(r -> now - r.born() > 1.2f);
		for (Ring r : rings) {
			float t = (now - r.born()) / 1.2f;
			float radius = Motion.outCubic(t) * Math.max(width, height) * 0.6f;
			int dots = (int) Math.max(24, radius * 0.9f);
			int color = ColorUtil.fade(Theme.text, (1 - t) * 0.7f);
			for (int i = 0; i < dots; i++) {
				double a = i * Math.PI * 2 / dots;
				Draw.rect(g, r.x() + (float) Math.cos(a) * radius, r.y() + (float) Math.sin(a) * radius, 1, 1, color);
			}
		}
	}

	private boolean logoGlitching() {
		float t = Motion.time();
		int window = (int) (t / 2.9f);
		return settings().glitch.get() && Motion.hash(window * 13) > 0.5f && t - window * 2.9f < 0.18f;
	}

	private void drawLogo(GuiGraphicsExtractor g, TitleScreenModule s) {
		int ps = pixelSize();
		float lx = logoX();
		float ly = logoY();
		float age = playIntro ? age() - 0.55f : age() + 2f;
		float time = Motion.time();
		boolean glitch = logoGlitching();
		int frame = (int) (time * 24);
		int bandTop = (int) (Motion.hash(frame) * 7);
		int bandBottom = bandTop + 1 + (int) (Motion.hash(frame * 3) * 3);
		float bandShift = (Motion.hash(frame * 7) - 0.5f) * 8 * ps;

		int extrudeFar = ColorUtil.lerp(Theme.bg, Theme.textDim, 0.35f);
		int extrudeNear = ColorUtil.lerp(Theme.bg, Theme.textDim, 0.8f);
		int face = Theme.text;
		int depth = Math.max(2, ps / 2 + 1);
		ThemeModule theme = Theme.settings();
		boolean shadow = theme.ditherShadow.get();
		boolean halo = theme.glow.get();
		int shadowOffset = depth + Math.max(2, ps / 2 + 1);
		int shadowColor = shadowColor();

		// pass 0 = theme shadow and glow, 1 = extrusion layers, 2 = face; each pass sits behind the next
		for (int pass = shadow || halo ? 0 : 1; pass < 3; pass++) {
			for (PixelFont.Pixel p : logoPixels) {
				float delay = 0.6f * p.x() / logoWidth + Motion.hash(p.x() * 7 + p.y() * 13) * 0.12f;
				float t = Motion.range(age, delay, delay + 0.55f);
				if (t <= 0) {
					continue;
				}
				float drop = (1 - Motion.outBounce(t)) * (ly + 60);
				float wave = (float) Math.sin(time * 2.2f + p.x() * 0.22f) * ps * 0.25f;
				float x = lx + p.x() * ps;
				float y = ly + p.y() * ps - drop + wave;
				if (glitch && p.y() >= bandTop && p.y() <= bandBottom) {
					x += bandShift;
				}
				if (pass == 0) {
					if (shadow) {
						// texture offset = screen position keeps neighbouring pixels' checkerboards in step
						int sx = Math.round(x) + shadowOffset;
						int sy = Math.round(y) + shadowOffset;
						Draw.tile(g, Draw.DITHER, sx, sy, ps, ps, sx, sy, shadowColor);
					}
					if (halo) {
						int c = Theme.accent(p.x() / (float) logoWidth);
						Draw.rect(g, x - 3, y - 3, ps + 6, ps + 6, ColorUtil.fade(c, 0.035f));
						Draw.rect(g, x - 1, y - 1, ps + 2, ps + 2, ColorUtil.fade(c, 0.09f));
					}
				} else if (pass == 1) {
					for (int d = depth; d >= 1; d--) {
						int c = ColorUtil.lerp(extrudeNear, extrudeFar, (d - 1) / (float) depth);
						Draw.rect(g, x + d, y + d, ps, ps, c);
					}
				} else {
					// a soft vertical sheen sweeps across the face every few seconds
					float sweep = (float) Math.pow(Math.max(0, Math.cos((p.x() / (float) logoWidth - time * 0.25f) * Math.PI * 2)), 18);
					// bottom rows a touch darker, like a lit-from-above bevel
					Draw.rect(g, x, y, ps, ps, p.y() > 4 ? ColorUtil.lerp(face, Theme.bg, 0.12f) : face);
					if (sweep > 0.05f) {
						Draw.rect(g, x, y, ps, ps, ColorUtil.fade(Theme.accent(p.x() / (float) logoWidth), sweep));
					}
				}
			}
		}
		if (glitch) {
			Draw.invert(g, lx - 6, ly + bandTop * ps, logoWidth * ps + 12, (bandBottom - bandTop + 1) * ps * 0.5f);
		}

		// tagline types itself out once the logo lands
		float tagAge = age - 1.1f;
		if (tagAge > 0) {
			int n = (int) Math.min(TAGLINE.length(), tagAge * 28);
			String shown = TAGLINE.substring(0, n);
			float tw = Theme.width(TAGLINE);
			float tx = (width - tw) / 2f;
			float ty = ly + 7 * ps + 12;
			Draw.text(g, shown, tx, ty, Theme.textDim);
			if ((int) (time * 2.2f) % 2 == 0) {
				Draw.rect(g, tx + Theme.width(shown) + 1, ty - 1, 5, 9, ColorUtil.fade(Theme.text, 0.8f));
			}
		}

		if (s.splash.get() && age > 1.4f) {
			drawSplash(g, lx, ly, ps, depth, age, time);
		}
	}

	/**
	 * Splash hangs off the logo's right end, rising to the right. It is anchored
	 * (and pulses) around its left end, so it never grows back into the logo, and
	 * shrinks or moves under the logo when the window is too narrow.
	 */
	private void drawSplash(GuiGraphicsExtractor g, float lx, float ly, int ps, int depth, float age, float time) {
		double angle = Math.toRadians(-18);
		float cos = (float) Math.cos(angle);
		float textW = Theme.width(splash);
		float left = lx + logoWidth * ps + depth + 6;
		float room = width - 6 - left;
		float base = Math.min(1f, 100f / Math.max(100f, textW + 32f));
		float fit = room / (textW * cos * 1.1f);
		float ax = left;
		float ay = ly + 4 * ps;
		if (fit < base) {
			if (fit >= 0.55f) {
				base = fit;
			} else {
				// no room on the right: tuck it under the logo's right end instead
				base = Math.min(base, (logoWidth * ps * 0.5f) / (textW * 1.1f));
				angle = Math.toRadians(-8);
				ax = lx + logoWidth * ps - textW * base * 1.1f;
				ay = ly + 7 * ps + depth + 10;
			}
		}
		float pulse = 1.0f + (float) Math.abs(Math.sin(time * 6.3f)) * 0.08f;
		float pop = Motion.outBack(Motion.range(age, 1.4f, 1.8f));
		float sc = pulse * pop * base;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(ax, ay);
		pose.rotate((float) angle);
		pose.scale(sc, sc);
		Draw.text(g, splash, 0, -4, Theme.accent(0.8f));
		pose.popMatrix();
	}

	private void drawMenu(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		float top = menuTop();
		float x = menuX();
		float w = menuW();
		float ih = itemH();
		float age = playIntro ? age() - 1.2f : age();

		float target = selected >= 0 ? top + selected * ih : selY.getTarget();
		if (selShown.get() < 0.01f && selected >= 0) {
			selY.snap(target);
		}
		float sy = selY.target(target).update(dt);
		float shown = selShown.target(selected >= 0).update(dt);
		if (shown > 0.01f) {
			float bw = (w + 20) * Motion.outCubic(shown);
			float bx = x - 10 + (w + 20 - bw) / 2f;
			ThemeModule theme = Theme.settings();
			if (theme.ditherShadow.get()) {
				Draw.ditherShadow(g, bx, sy, bw, ih - 2, 2, shadowColor());
			}
			if (theme.glow.get()) {
				Draw.glow(g, bx, sy, bw, ih - 2, Theme.accent(0.5f), 6);
			}
			Draw.panel(g, bx, sy, bw, ih - 2, Theme.accent(0.5f));
		}

		float widest = 0;
		for (Item item : items) {
			widest = Math.max(widest, Theme.width(item.label()));
		}
		// numbers line up in their own column just left of the widest label
		float numX = x + (w - widest) / 2f - 22;
		for (int i = 0; i < items.size(); i++) {
			Item item = items.get(i);
			float appear = Motion.outCubic(Motion.range(age, i * 0.06f, i * 0.06f + 0.35f));
			if (appear <= 0) {
				continue;
			}
			float hv = itemHover[i].target(i == selected).update(dt);
			float iy = top + i * ih;
			boolean enabled = item.enabled().getAsBoolean();
			float prev = Draw.alpha;
			Draw.alpha = prev * appear;
			float slide = (1 - appear) * 30 * (i % 2 == 0 ? -1 : 1);

			if (i == selected && lastSelected != selected) {
				selectedAt[i] = Motion.time();
			}
			String label = item.label();
			float lw = Theme.width(label);
			if (i == selected) {
				// hacker-movie decrypt: letters resolve left to right out of noise
				label = scramble(label, Motion.time() - selectedAt[i]);
			}
			float lx = x + (w - lw) / 2f + slide;
			float overlap = Motion.clamp01(1f - Math.abs(sy - iy) / (ih - 2)) * shown;
			int base = enabled ? ColorUtil.lerp(Theme.textDim, Theme.text, hv) : ColorUtil.fade(Theme.textDim, 0.45f);
			int color = ColorUtil.lerp(base, Theme.onAccent(), overlap);
			Draw.text(g, label, lx, iy + 3, color);
			if (!enabled) {
				Draw.rect(g, lx - 1, iy + 7, lw + 2, 1, ColorUtil.fade(Theme.textDim, 0.6f));
			}
			Draw.text(g, Integer.toString(i + 1), numX + slide, iy + 3, ColorUtil.lerp(ColorUtil.fade(Theme.textDim, 0.5f), Theme.onAccent(), overlap));
			if (hv > 0.02f) {
				float arrow = Motion.outBack(hv) * 6;
				Draw.text(g, ">", lx - 10 - (6 - arrow), iy + 3, ColorUtil.fade(color, hv));
				Draw.text(g, "<", lx + lw + 4 + (6 - arrow), iy + 3, ColorUtil.fade(color, hv));
			}
			Draw.alpha = prev;
		}

		lastSelected = selected;
		if (selected >= 0) {
			Item item = items.get(selected);
			String hint = item.enabled().getAsBoolean() ? item.hint() : "not available right now";
			Draw.textCentered(g, hint, width / 2f, top + items.size() * ih + 8, ColorUtil.fade(Theme.textDim, shown));
			if (item.enabled().getAsBoolean() && !keyboardMode) {
				g.requestCursor(CursorTypes.POINTING_HAND);
			}
		}
	}

	private static String scramble(String text, float age) {
		int revealed = (int) (age * Motion.speed() * 45);
		if (revealed >= text.length()) {
			return text;
		}
		String pool = "#%&@$*+=?/\\<>01";
		int frame = (int) (Motion.time() * 30);
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			sb.append(i < revealed || c == ' ' ? c : pool.charAt((int) (Motion.hash(frame * 31 + i * 7) * pool.length()) % pool.length()));
		}
		return sb.toString();
	}

	/** First launch only: a quick fake BIOS log in the corner while the logo lands. */
	private void drawBootLog(GuiGraphicsExtractor g) {
		float t = age() - 0.85f;
		if (t < 0 || t > 4.2f) {
			return;
		}
		String[] lines = {
				"troll bios v1.0  (c) nobody",
				"memory test ........ 640k ok",
				"loading " + ModuleManager.all().size() + " modules ... ok",
				"mounting /dev/chaos ....... ok",
				"calibrating twerk ......... ok",
				"ready."
		};
		float fade = 1f - Motion.range(t, 3.2f, 4.2f);
		float y = 22;
		for (int i = 0; i < lines.length; i++) {
			float at = i * 0.16f;
			if (t < at) {
				break;
			}
			String line = lines[i];
			int typed = (int) Math.min(line.length(), (t - at) * 90);
			Draw.text(g, line.substring(0, typed), 8, y, ColorUtil.fade(i == lines.length - 1 ? Theme.text : Theme.textDim, 0.8f * fade));
			y += 10;
		}
	}

	/** Classic demoscene sine scroller. */
	private void drawScroller(GuiGraphicsExtractor g, String text) {
		if (text.isBlank()) {
			return;
		}
		String loop = text.toLowerCase(Locale.ROOT) + "     ";
		float total = Theme.width(loop);
		scrollOffset = (scrollOffset + dt * 55 * Motion.speed()) % total;
		float baseY = height - 34;
		float time = Motion.time();
		float x = width - scrollOffset - total;
		while (x < width) {
			for (int i = 0; i < loop.length(); i++) {
				char c = loop.charAt(i);
				String ch = String.valueOf(c);
				float cw = Theme.width(ch);
				if (x > -cw && x < width && c != ' ') {
					float y = baseY + (float) Math.sin(x * 0.022f + time * 3f) * 6;
					float shade = 0.55f + 0.45f * (float) Math.sin(x * 0.01f - time * 2f);
					// fade out at the screen edges
					float edge = Motion.clamp01(Math.min(x, width - x) / 60f);
					Draw.text(g, ch, x, y, ColorUtil.fade(Theme.text, shade * edge));
				}
				x += cw;
			}
		}
	}

	private void drawChrome(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		float time = Motion.time();
		String version = SharedConstants.getCurrentVersion().name();
		Draw.text(g, "troll client 1.0 / minecraft " + version + " / fabric", 6, height - 12, ColorUtil.fade(Theme.textDim, 0.85f));
		Draw.textRight(g, "Copyright Mojang AB. Do not distribute!", width - 6, height - 12, ColorUtil.fade(Theme.textDim, 0.85f));

		String clock = LocalTime.now().format(CLOCK);
		if ((int) (time * 2) % 2 == 1) {
			clock = clock.replace(':', ' '); // blinking colon, digital-clock style
		}
		String right = minecraft.getUser().getName().toLowerCase(Locale.ROOT) + "  " + clock;
		Draw.textRight(g, right, width - 8, 9, Theme.textDim);

		chip(g, vanillaBox(), "[ vanilla menu ]", vanillaHover, mouseX, mouseY);
		float[] tb = themeBox();
		if (tb != null) {
			chip(g, tb, themeLabel(), themeHover, mouseX, mouseY);
			if (inBox(mouseX, mouseY, tb)) {
				Draw.text(g, "click: next / right click: back / t", tb[0] + 2, tb[1] + tb[3] + 3, ColorUtil.fade(Theme.textDim, themeHover.get()));
			}
		}
	}

	/** A "[ label ]" button in the corner that fills with the accent when hovered. */
	private void chip(GuiGraphicsExtractor g, float[] box, String label, Anim hover, int mouseX, int mouseY) {
		boolean over = inBox(mouseX, mouseY, box);
		float hv = hover.target(over).update(dt);
		if (hv > 0.01f) {
			Draw.rect(g, box[0], box[1], box[2] * Motion.outCubic(hv), box[3], ColorUtil.fade(Theme.accent(), hv));
		}
		Draw.text(g, label, box[0] + 2, box[1] + 2, ColorUtil.lerp(Theme.textDim, Theme.onAccent(), hv));
		if (over) {
			g.requestCursor(CursorTypes.POINTING_HAND);
		}
	}

	/**
	 * First launch only: the whole screen powers on like an old monitor. Dark
	 * presets get a black tube and a white beam; light ones the negative, like
	 * a pen line drawn across paper.
	 */
	private void drawIntro(GuiGraphicsExtractor g) {
		float t = age();
		if (t > 0.9f) {
			return;
		}
		g.nextStratum();
		int off = Theme.light ? 0xFFFFFFFF : 0xFF000000;
		int beam = Theme.light ? 0xFF000000 : 0xFFFFFFFF;
		if (t < 0.3f) {
			float a = Motion.outCubic(t / 0.3f);
			Draw.rect(g, 0, 0, width, height, off);
			float lw = width * a;
			Draw.glow(g, (width - lw) / 2f, height / 2f - 1, lw, 2, beam, 8);
			Draw.rect(g, (width - lw) / 2f, height / 2f - 1, lw, 2, beam);
			return;
		}
		float b = Motion.outCubic(Motion.range(t, 0.3f, 0.75f));
		float band = height * b;
		float top = (height - band) / 2f;
		Draw.rect(g, 0, 0, width, top, off);
		Draw.rect(g, 0, top + band, width, height - top - band, off);
		float flash = 1f - Motion.range(t, 0.3f, 0.9f);
		Draw.rect(g, 0, top, width, band, ColorUtil.fade(beam, flash * 0.85f));
	}

	// ------------------------------------------------------------------ input

	private void activate(int index) {
		if (index < 0 || index >= items.size()) {
			return;
		}
		Item item = items.get(index);
		if (!item.enabled().getAsBoolean()) {
			Sounds.toggle(false);
			return;
		}
		Sounds.click();
		item.action().run();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (inBox(event.x(), event.y(), themeBox()) && (event.button() == 0 || event.button() == 1)) {
			cycleTheme(event.button() == 1 ? -1 : 1);
			return true;
		}
		if (event.button() != 0) {
			return true;
		}
		if (inBox(event.x(), event.y(), vanillaBox())) {
			Sounds.click();
			TitleScreenGuard.requestVanilla();
			minecraft.gui.setScreen(new TitleScreen());
			return true;
		}
		int index = itemAt(event.x(), event.y());
		if (index >= 0) {
			activate(index);
			return true;
		}
		rings.add(new Ring((float) event.x(), (float) event.y(), Motion.time()));
		Sounds.tick();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_TAB) {
			keyboardMode = true;
			selected = selected < 0 ? 0 : (selected + 1) % items.size();
			Sounds.tick();
			return true;
		}
		if (key == GLFW.GLFW_KEY_UP) {
			keyboardMode = true;
			selected = selected < 0 ? items.size() - 1 : Math.floorMod(selected - 1, items.size());
			Sounds.tick();
			return true;
		}
		if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) && selected >= 0) {
			activate(selected);
			return true;
		}
		if (key == GLFW.GLFW_KEY_T && settings().themeButton.get()) {
			cycleTheme(event.hasShiftDown() ? -1 : 1);
			return true;
		}
		if (key >= GLFW.GLFW_KEY_1 && key < GLFW.GLFW_KEY_1 + items.size()) {
			selected = key - GLFW.GLFW_KEY_1;
			activate(selected);
			return true;
		}
		return super.keyPressed(event);
	}
}
