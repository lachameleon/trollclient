package com.trollclient.module.chat;

import com.trollclient.command.Commands;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.Targets;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Gives running commentary on whatever the players around you are up to, in a nature documentary voice (or worse). */
public class Narrator extends Module {
	/** Standing perfectly still this long counts as an event of its own. */
	private static final int IDLE_TICKS = 200;
	/** A line that's waited this much longer than the cooldown is old news. */
	private static final long STALE_MS = 3000;

	private enum Event { ARRIVE, LEAVE, CROUCH, SPRINT, HURT, SWIM, SLEEP, FLY, RIDE, FIRE, ITEM, EAT, IDLE }

	private final ModeSetting style = add(new ModeSetting("Style", "Whose voice the commentary is in", "Documentary",
			"Documentary", "Sports", "Plain"));
	private final NumberSetting range = add(new NumberSetting("Range", "Narrate players this close", 16, 4, 64, 1).unit("m"));
	private final BoolSetting arrivals = add(new BoolSetting("Arrivals", "Narrate players coming into range and leaving it", true));
	private final ModeSetting audience = add(new ModeSetting("Audience", "Say it in chat, or just show it to you", "Public",
			"Public", "Client"));
	private final NumberSetting cooldown = add(new NumberSetting("Cooldown", "Minimum gap between lines (servers kick spammers)", 8, 2, 60, 1)
			.unit("s"));
	private final NumberSetting chance = add(new NumberSetting("Chance", "Chance to narrate any given moment", 60, 5, 100, 5).unit("%"));

	private record Snapshot(boolean crouching, boolean sprinting, boolean swimming, boolean sleeping, boolean flying,
			boolean riding, boolean burning, boolean eating, boolean hurt, Item item, Vec3 pos, int still) {
	}

	private final Map<UUID, Snapshot> seen = new HashMap<>();
	private final Map<UUID, Long> lastAbout = new HashMap<>();
	private String pending;
	private long pendingAt;
	private long lastSent;
	private boolean baseline;
	private int lines;

	public Narrator() {
		super("Narrator", "Narrates what nearby players are doing, like a nature documentary or a sports commentator.", Category.CHAT);
	}

	@Override
	protected void onEnable() {
		onWorldLeave();
	}

	@Override
	public void onWorldLeave() {
		seen.clear();
		pending = null;
		baseline = true;
	}

	@Override
	public void onTick() {
		Set<UUID> present = new HashSet<>();
		for (Player p : Targets.playersWithin(range.get(), false)) {
			present.add(p.getUUID());
			Snapshot before = seen.get(p.getUUID());
			Snapshot now = snapshot(p, before);
			seen.put(p.getUUID(), now);
			if (baseline) {
				continue;
			}
			Event event = before == null ? (arrivals.get() ? Event.ARRIVE : null) : change(before, now);
			if (event != null) {
				narrate(event, p);
			}
		}
		for (UUID id : new HashSet<>(seen.keySet())) {
			if (!present.contains(id)) {
				seen.remove(id);
				Player gone = mc.level.getPlayerByUUID(id);
				// only "leaving" if they walked off; vanishing from the world is a log-off, not a scene
				if (!baseline && arrivals.get() && gone != null) {
					narrate(Event.LEAVE, gone);
				}
			}
		}
		baseline = false;

		long now = System.currentTimeMillis();
		if (pending != null && now - pendingAt > cooldown.get() * 1000 + STALE_MS) {
			pending = null;
		}
		if (pending != null && now - lastSent >= cooldown.get() * 1000) {
			lastSent = now;
			lines++;
			if (audience.is("Client")) {
				Commands.info(pending);
			} else {
				ChatUtil.send(pending);
			}
			pending = null;
		}
	}

	private Snapshot snapshot(Player p, Snapshot before) {
		Vec3 pos = p.position();
		boolean moved = before == null || before.pos().distanceToSqr(pos) > 0.0004;
		boolean eating = p.isUsingItem() && p.getUseItem().getUseAnimation() == ItemUseAnimation.EAT;
		return new Snapshot(p.isCrouching(), p.isSprinting(), p.isSwimming(), p.isSleeping(), p.isFallFlying(),
				p.isPassenger(), p.isOnFire(), eating, p.hurtTime > 0, p.getMainHandItem().getItem(), pos,
				moved ? 0 : before.still() + 1);
	}

	/** The most interesting thing that just started, if anything did. */
	private static Event change(Snapshot a, Snapshot b) {
		if (b.hurt() && !a.hurt()) {
			return Event.HURT;
		}
		if (b.burning() && !a.burning()) {
			return Event.FIRE;
		}
		if (b.sleeping() && !a.sleeping()) {
			return Event.SLEEP;
		}
		if (b.flying() && !a.flying()) {
			return Event.FLY;
		}
		if (b.riding() && !a.riding()) {
			return Event.RIDE;
		}
		if (b.swimming() && !a.swimming()) {
			return Event.SWIM;
		}
		if (b.eating() && !a.eating()) {
			return Event.EAT;
		}
		if (b.item() != a.item() && b.item() != Items.AIR) {
			return Event.ITEM;
		}
		if (b.sprinting() && !a.sprinting()) {
			return Event.SPRINT;
		}
		if (b.crouching() && !a.crouching()) {
			return Event.CROUCH;
		}
		if (b.still() == IDLE_TICKS) {
			return Event.IDLE;
		}
		return null;
	}

	private void narrate(Event event, Player p) {
		long now = System.currentTimeMillis();
		// one line per player every so often, and only some of the time, or it's just spam
		if (now - lastAbout.getOrDefault(p.getUUID(), 0L) < cooldown.get() * 1500
				|| ThreadLocalRandom.current().nextInt(100) >= chance.getInt()) {
			return;
		}
		lastAbout.put(p.getUUID(), now);
		String line = ChatUtil.pick(template(event)).replace("{player}", Targets.name(p))
				.replace("{item}", p.getMainHandItem().getHoverName().getString().toLowerCase(Locale.ROOT))
				.replace("{vehicle}", vehicleName(p));
		// the newest moment is the most relevant one
		pending = line;
		pendingAt = now;
	}

	private static String vehicleName(Player p) {
		Entity vehicle = p.getVehicle();
		return vehicle == null ? "something" : vehicle.getName().getString().toLowerCase(Locale.ROOT);
	}

	private String template(Event event) {
		return switch (style.get()) {
			case "Sports" -> switch (event) {
				case ARRIVE -> "and {player} enters the arena! | here comes {player}! the crowd is on its feet";
				case LEAVE -> "{player} heads for the tunnel | and {player} is leaving the pitch";
				case CROUCH -> "{player} is crouching! what a tactical play | {player} gets low, very low";
				case SPRINT -> "{player} is off like a rocket! | look at {player} go!";
				case HURT -> "OH! {player} takes a big hit there | that's gonna leave a mark on {player}";
				case SWIM -> "{player} into the water, lovely stroke | {player} is swimming for gold";
				case SLEEP -> "{player} is taking a nap. bold strategy | {player} has gone to sleep mid-match";
				case FLY -> "{player} is AIRBORNE! | {player} takes to the skies!";
				case RIDE -> "{player} jumps on a {vehicle}, the crowd goes wild";
				case FIRE -> "{player} is literally on fire right now | {player} is ON FIRE (actually)";
				case ITEM -> "{player} pulls out the {item}! | {player} goes for the {item}, interesting choice";
				case EAT -> "{player} grabbing a quick snack at halftime | {player} refuelling";
				case IDLE -> "{player} just standing there. menacingly | {player} is holding position";
			};
			case "Plain" -> switch (event) {
				case ARRIVE -> "{player} is here";
				case LEAVE -> "{player} left";
				case CROUCH -> "{player} is crouching";
				case SPRINT -> "{player} started sprinting";
				case HURT -> "{player} got hurt";
				case SWIM -> "{player} is swimming";
				case SLEEP -> "{player} went to sleep";
				case FLY -> "{player} is flying";
				case RIDE -> "{player} is riding a {vehicle}";
				case FIRE -> "{player} is on fire";
				case ITEM -> "{player} is holding a {item}";
				case EAT -> "{player} is eating";
				case IDLE -> "{player} hasn't moved in a while";
			};
			default -> switch (event) {
				case ARRIVE -> "here we see {player}, approaching cautiously | and now, a wild {player} appears";
				case LEAVE -> "{player} retreats into the wilderness | and so {player} moves on, as all things must";
				case CROUCH -> "{player} crouches low, hoping not to be noticed | {player} makes themself small. a defensive posture";
				case SPRINT -> "and {player} breaks into a sprint. magnificent | {player} bolts. something has startled it";
				case HURT -> "{player} has been wounded. nature is cruel | {player} is hurt. we are not allowed to intervene";
				case SWIM -> "{player} takes to the water, as their ancestors once did | {player} swims. surprisingly graceful";
				case SLEEP -> "{player} curls up to sleep. we must be very quiet | {player} rests, at last";
				case FLY -> "{player} spreads their wings and takes flight | remarkable. {player} can fly";
				case RIDE -> "{player} has mounted a {vehicle}. a rare partnership";
				case FIRE -> "{player} is on fire. this is not normal behaviour | {player} is burning. fascinating";
				case ITEM -> "{player} produces a {item}. fascinating | {player} now wields a {item}, a tool of its kind";
				case EAT -> "{player} pauses to feed | {player} eats. it must keep its strength up";
				case IDLE -> "{player} has not moved in some time. perhaps it is resting | {player} stands perfectly still. waiting";
			};
		};
	}

	@Override
	public String getInfo() {
		return lines > 0 ? Integer.toString(lines) : style.get();
	}
}
