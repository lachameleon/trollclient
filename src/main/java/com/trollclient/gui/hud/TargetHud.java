package com.trollclient.gui.hud;

import com.trollclient.gui.Anim;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Motion;
import com.trollclient.gui.Theme;
import com.trollclient.module.Module;
import com.trollclient.module.ModuleManager;
import com.trollclient.util.ColorUtil;
import com.trollclient.util.Targets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix3x2fStack;

import java.util.Locale;

/**
 * Fighting-game style card beside the crosshair for whoever a module is
 * targeting: face, name, a segmented health bar with a trailing "ghost" bar,
 * and a flash plus a shake when they take damage.
 */
public final class TargetHud {
	private static final float W = 136;
	private static final float H = 38;
	private static final Anim SHOW = new Anim(12);
	private static final Anim HEALTH = new Anim(1, 12);
	private static final Anim GHOST = new Anim(1, 2.5f);
	private static Player shown;
	private static String source = "";
	private static float lastHealth;
	private static float hitAt = -10;
	private static float ghostHoldUntil;

	private TargetHud() {
	}

	public static void render(GuiGraphicsExtractor g, int width, int height, float dt) {
		Player target = null;
		for (Module m : ModuleManager.all()) {
			if (m.isEnabled()) {
				Player p = m.getTarget();
				if (p != null && p.isAlive()) {
					target = p;
					source = m.getName().toLowerCase(Locale.ROOT);
					break;
				}
			}
		}
		float s = SHOW.target(target != null).update(dt);
		if (target != null && target != shown) {
			shown = target;
			float frac = fraction(target);
			HEALTH.snap(frac);
			GHOST.snap(frac);
			lastHealth = target.getHealth();
		}
		if (s < 0.01f || shown == null) {
			if (s < 0.01f) {
				shown = null;
			}
			return;
		}
		Player p = shown;
		float now = Motion.time();
		float health = p.getHealth();
		if (health < lastHealth - 0.01f) {
			hitAt = now;
			ghostHoldUntil = now + 0.45f;
		}
		lastHealth = health;
		float frac = fraction(p);
		float hp = HEALTH.target(frac).update(dt);
		// the ghost bar waits a moment, then drains down to the real value
		if (GHOST.get() < hp || now > ghostHoldUntil) {
			GHOST.target(frac);
		}
		float ghost = Math.max(hp, GHOST.update(dt));

		float hit = Motion.clamp01(1f - (now - hitAt) / 0.3f);
		float shake = hit * 2.5f * (float) Math.sin(now * 90);
		// centred under the crosshair: clear of notifications (right), chat (bottom left) and the hotbar
		float x = width / 2f - W / 2f + shake;
		float y = height / 2f + 22;
		float e = Motion.outBack(s);

		float prev = Draw.alpha;
		Draw.alpha = prev * Motion.clamp01(s * 1.6f);
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y + H / 2f);
		pose.scale(0.8f + 0.2f * e, 0.8f + 0.2f * e);
		pose.translate(0, -H / 2f);

		Draw.ditherShadow(g, 0, 0, W, H, 3, ColorUtil.withAlpha(0x000000, 140));
		Draw.panel(g, 0, 0, W, H, Theme.a(Theme.panel));
		Draw.panelOutline(g, 0, 0, W, H, Theme.border);
		Draw.gradientH(g, 1, 1, W - 2, 1, q -> Theme.accent(q / 1000f));

		// face, with a little frame
		Draw.rect(g, 4, 5, 28, 28, Theme.panel2);
		Draw.outline(g, 4, 5, 28, 28, Theme.borderHi);
		if (p instanceof AbstractClientPlayer client) {
			int tint = ColorUtil.withAlpha(0xFFFFFF, Math.round(255 * Draw.alpha));
			PlayerFaceExtractor.extractRenderState(g, client.getSkin(), 6, 7, 24, tint);
		}

		String name = Draw.ellipsize(Targets.name(p), (int) (W - 44 - Theme.width(source)));
		Draw.text(g, name, 37, 6, Theme.text);
		Draw.textRight(g, source, W - 5, 6, ColorUtil.fade(Theme.textDim, 0.8f));

		// segmented bar: the gaps make it read like an old arcade life meter
		float bx = 37;
		float by = 18;
		float bw = W - 42;
		float bh = 6;
		Draw.rect(g, bx, by, bw, bh, Theme.panel2);
		Draw.rect(g, bx, by, bw * ghost, bh, ColorUtil.fade(Theme.textDim, 0.7f));
		Draw.gradientH(g, bx, by, bw * hp, bh, q -> Theme.accent(q / 1000f * hp));
		for (int i = 1; i < 10; i++) {
			Draw.rect(g, bx + bw * i / 10f, by, 1, bh, Theme.a(Theme.panel));
		}
		Draw.outline(g, bx - 1, by - 1, bw + 2, bh + 2, Theme.border);
		float absorption = p.getAbsorptionAmount();
		if (absorption > 0) {
			Draw.rect(g, bx, by - 2, bw * Motion.clamp01(absorption / p.getMaxHealth()), 1, Theme.text);
		}

		Minecraft mc = Minecraft.getInstance();
		String stats = String.format(Locale.ROOT, "%.1f hp", health + absorption);
		String dist = mc.player == null ? "" : String.format(Locale.ROOT, "%.1fm", mc.player.distanceTo(p));
		Draw.text(g, stats, 37, 27, Theme.textDim);
		Draw.textRight(g, dist, W - 5, 27, Theme.textDim);

		if (hit > 0.01f) {
			Draw.rect(g, 0, 0, W, H, ColorUtil.fade(Theme.text, hit * 0.35f));
		}
		pose.popMatrix();
		Draw.alpha = prev;
	}

	private static float fraction(Player p) {
		return Motion.clamp01(p.getHealth() / Math.max(1f, p.getMaxHealth()));
	}
}
