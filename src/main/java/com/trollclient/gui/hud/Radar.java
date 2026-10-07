package com.trollclient.gui.hud;

import com.trollclient.gui.Draw;
import com.trollclient.gui.Motion;
import com.trollclient.gui.Theme;
import com.trollclient.util.ColorUtil;
import com.trollclient.util.Friends;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Old-school sweep radar, heading-up. Players light up as the sweep passes
 * over them and fade until the next pass; friends are hollow, and a tick
 * above or below the blip says they're higher or lower than you.
 */
public final class Radar {
	private static final float SWEEP_SPEED = 2.4f;
	private static final Map<UUID, Float> PINGED = new HashMap<>();
	private static float sweep;

	private Radar() {
	}

	public static void render(GuiGraphicsExtractor g, float x, float y, float size, float range, float dt) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		float r = size / 2f;
		float cx = x + r;
		float cy = y + r;
		float prevSweep = sweep;
		sweep = (sweep + dt * SWEEP_SPEED * Motion.speed()) % (float) (Math.PI * 2);
		float now = Motion.time();

		// filled disc, one span per row
		int bg = Theme.a(ColorUtil.withAlpha(Theme.panel, 190));
		for (int dy = (int) -r; dy < r; dy++) {
			float half = (float) Math.sqrt(Math.max(0, r * r - (dy + 0.5f) * (dy + 0.5f)));
			Draw.rect(g, cx - half, cy + dy, half * 2, 1, bg);
		}
		dottedCircle(g, cx, cy, r * 0.5f, ColorUtil.fade(Theme.border, 0.9f), 5);
		Draw.rect(g, cx - r + 2, cy, size - 4, 1, ColorUtil.fade(Theme.border, 0.5f));
		Draw.rect(g, cx, cy - r + 2, 1, size - 4, ColorUtil.fade(Theme.border, 0.5f));

		// the sweep and its fading wake
		for (int k = 0; k < 9; k++) {
			float a = sweep - k * 0.07f;
			float alpha = (1f - k / 9f) * (k == 0 ? 0.9f : 0.3f);
			line(g, cx, cy, a, r - 1, ColorUtil.fade(Theme.accent(), alpha));
		}

		float yaw = mc.player.getYRot() * Mth.DEG_TO_RAD;
		double fx = -Mth.sin(yaw);
		double fz = Mth.cos(yaw);
		// facing south (+z) your right hand points west (-x)
		double rx = -Mth.cos(yaw);
		double rz = -Mth.sin(yaw);
		for (Player p : mc.level.players()) {
			if (p == mc.player || p.isSpectator()) {
				continue;
			}
			double dx = p.getX() - mc.player.getX();
			double dz = p.getZ() - mc.player.getZ();
			float px = (float) ((dx * rx + dz * rz) / range * r);
			float py = (float) (-(dx * fx + dz * fz) / range * r);
			float len = (float) Math.hypot(px, py);
			boolean outside = len > r - 3;
			if (outside) {
				px *= (r - 3) / len;
				py *= (r - 3) / len;
			}
			// angle clockwise from "up", the same way the sweep turns
			float angle = (float) Math.atan2(px, -py);
			if (angle < 0) {
				angle += (float) (Math.PI * 2);
			}
			if (crossed(prevSweep, sweep, angle)) {
				PINGED.put(p.getUUID(), now);
			}
			float since = now - PINGED.getOrDefault(p.getUUID(), -10f);
			float glow = Math.max(0.3f, 1f - since / (float) (Math.PI * 2 / SWEEP_SPEED));
			int color = ColorUtil.fade(Theme.text, glow);
			float bx = cx + px - 1.5f;
			float by = cy + py - 1.5f;
			if (Friends.isFriend(p) || outside) {
				Draw.outline(g, bx, by, 3, 3, color);
			} else {
				Draw.rect(g, bx, by, 3, 3, color);
			}
			double dyBlocks = p.getY() - mc.player.getY();
			if (Math.abs(dyBlocks) > 3) {
				Draw.rect(g, bx + 1, dyBlocks > 0 ? by - 2 : by + 4, 1, 1, color);
			}
		}

		// you: a small arrow pointing up
		int me = Theme.accent();
		Draw.rect(g, cx, cy - 2, 1, 1, me);
		Draw.rect(g, cx - 1, cy - 1, 3, 1, me);
		Draw.rect(g, cx - 1, cy, 3, 1, me);
		Draw.rect(g, cx - 2, cy + 1, 1, 1, me);
		Draw.rect(g, cx + 2, cy + 1, 1, 1, me);

		dottedCircle(g, cx, cy, r - 0.5f, Theme.borderHi, 2);
		// north marker rides the rim (north is -z)
		float nx = cx + (float) -rz * (r - 5);
		float ny = cy + (float) fz * (r - 5);
		Draw.text(g, "n", nx - 2, ny - 4, Theme.textDim, false);
	}

	private static boolean crossed(float from, float to, float angle) {
		if (to >= from) {
			return angle > from && angle <= to;
		}
		return angle > from || angle <= to;
	}

	private static void line(GuiGraphicsExtractor g, float cx, float cy, float angle, float length, int color) {
		float sx = (float) Math.sin(angle);
		float sy = (float) -Math.cos(angle);
		for (float d = 2; d < length; d += 2f) {
			Draw.rect(g, cx + sx * d, cy + sy * d, 1, 1, color);
		}
	}

	private static void dottedCircle(GuiGraphicsExtractor g, float cx, float cy, float radius, int color, int gap) {
		int steps = Math.max(12, (int) (radius * Math.PI * 2 / gap));
		for (int i = 0; i < steps; i++) {
			double a = i * Math.PI * 2 / steps;
			Draw.rect(g, cx + (float) Math.cos(a) * radius, cy + (float) Math.sin(a) * radius, 1, 1, color);
		}
	}
}
