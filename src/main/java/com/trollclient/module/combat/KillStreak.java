package com.trollclient.module.combat;

import com.trollclient.gui.Draw;
import com.trollclient.gui.Motion;
import com.trollclient.gui.Theme;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.ColorUtil;
import com.trollclient.util.Targets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Arena-shooter announcer for your kills: DOUBLE KILL, KILLING SPREE, UNSTOPPABLE, in big letters over the crosshair. */
public class KillStreak extends Module {
	private static final byte DEATH = 3;
	/** A death counts as ours if we hit them this recently. */
	private static final long CREDIT_MS = 5000;
	private static final float SHOW_SECONDS = 2.4f;

	private final NumberSetting window = add(new NumberSetting("Multi Kill Window", "Kills this close together chain into a double, triple...", 4, 1, 15, 0.5)
			.unit("s"));
	private final BoolSetting mobs = add(new BoolSetting("Mobs Count", "Count mob kills too, not just players", false));
	private final BoolSetting screen = add(new BoolSetting("On Screen", "Big call-outs over the crosshair", true));
	private final ModeSetting chat = add(new ModeSetting("Chat", "Announce it in chat", "Off", "Off", "Streaks", "Every Kill"));
	private final BoolSetting sound = add(new BoolSetting("Sound", "A little fanfare with each call-out", true));

	private final Map<UUID, Long> hitAt = new HashMap<>();
	private int streak;
	private int multi;
	private long lastKill;
	private int kills;
	private String title;
	private String subtitle;
	private float shownAt = -10;

	public KillStreak() {
		super("KillStreak", "Counts your kills and calls out multi-kills and streaks in big letters (and in chat, if you're brave).",
				Category.COMBAT);
	}

	@Override
	protected void onEnable() {
		hitAt.clear();
		streak = 0;
		multi = 0;
		kills = 0;
	}

	@Override
	public void onWorldLeave() {
		hitAt.clear();
		streak = 0;
		multi = 0;
	}

	@Override
	public void onDamage(Entity victim, DamageSource source) {
		if (mc.player != null && victim != mc.player && source.getEntity() == mc.player && countable(victim)) {
			long now = System.currentTimeMillis();
			// forget everyone we hit who got away
			hitAt.values().removeIf(t -> now - t > CREDIT_MS);
			hitAt.put(victim.getUUID(), now);
		}
	}

	@Override
	public void onEntityEvent(Entity entity, byte event) {
		if (event != DEATH || mc.player == null) {
			return;
		}
		if (entity == mc.player) {
			if (streak >= 3 && !chat.is("Off")) {
				ChatUtil.send("my " + streak + " kill streak has ended");
			}
			streak = 0;
			multi = 0;
			return;
		}
		Long hit = hitAt.remove(entity.getUUID());
		if (hit == null || System.currentTimeMillis() - hit > CREDIT_MS || !countable(entity)) {
			return;
		}
		kill(entity);
	}

	private boolean countable(Entity e) {
		return e instanceof Player || (mobs.get() && e instanceof LivingEntity);
	}

	private void kill(Entity victim) {
		long now = System.currentTimeMillis();
		multi = now - lastKill <= window.get() * 1000 ? multi + 1 : 1;
		lastKill = now;
		streak++;
		kills++;
		String multiName = switch (multi) {
			case 1 -> null;
			case 2 -> "DOUBLE KILL";
			case 3 -> "TRIPLE KILL";
			case 4 -> "QUAD KILL";
			case 5 -> "PENTA KILL";
			default -> "MONSTER KILL";
		};
		String streakName = switch (streak) {
			case 3 -> "KILLING SPREE";
			case 5 -> "RAMPAGE";
			case 7 -> "DOMINATING";
			case 10 -> "UNSTOPPABLE";
			case 15 -> "GODLIKE";
			case 20 -> "LEGENDARY";
			default -> null;
		};
		String name = victim instanceof Player p ? Targets.name(p) : victim.getName().getString();
		String headline = multiName != null ? multiName : streakName;
		if (headline == null) {
			headline = "KILL";
		}
		title = headline;
		subtitle = name.toLowerCase(Locale.ROOT) + "  /  streak " + streak;
		shownAt = Motion.time();
		if (sound.get() && (multiName != null || streakName != null)) {
			float pitch = 0.8f + Math.min(multi + streak / 5, 6) * 0.15f;
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, pitch, 0.6f));
		}
		boolean special = multiName != null || streakName != null;
		if (chat.is("Every Kill") || (chat.is("Streaks") && special)) {
			ChatUtil.send(special ? headline.toLowerCase(Locale.ROOT) + "! (" + streak + " streak)" : "got " + name + " (" + streak + " streak)");
		}
	}

	/** Called from the HUD renderer: the call-out, punched in and then faded out. */
	public void render(GuiGraphicsExtractor g, int width, int height) {
		if (!screen.get() || title == null) {
			return;
		}
		float age = Motion.time() - shownAt;
		if (age > SHOW_SECONDS || age < 0) {
			return;
		}
		float in = Motion.outBack(Motion.range(age, 0, 0.25f));
		float fade = 1f - Motion.range(age, SHOW_SECONDS - 0.5f, SHOW_SECONDS);
		float scale = 2.6f - 0.6f * in;
		float cx = width / 2f;
		float y = height / 2f - 52;
		int accent = ColorUtil.fade(Theme.accent(Motion.clamp01(age / SHOW_SECONDS)), fade);
		float prev = Draw.alpha;
		Draw.alpha = prev * Motion.clamp01(in * 1.5f) * fade;
		float tw = Theme.width(title) * scale;
		// dark backing bar so it reads over sky, snow or lava
		Draw.rect(g, cx - tw / 2f - 8, y - 4, tw + 16, 9 * scale + 8, ColorUtil.withAlpha(Theme.light ? 0xFFFFFF : 0x000000, 110));
		Draw.rect(g, cx - tw / 2f - 8, y - 4, tw + 16, 1, accent);
		Draw.textScaled(g, title, cx - tw / 2f, y, scale, accent);
		Draw.textCentered(g, subtitle, cx, y + 9 * scale + 7, Theme.text);
		Draw.alpha = prev;
	}

	@Override
	public String getInfo() {
		return streak > 0 ? Integer.toString(streak) : (kills > 0 ? "0" : null);
	}
}
