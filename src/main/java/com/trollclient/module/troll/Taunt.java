package com.trollclient.module.troll;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.pathing.Walkability;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.Friends;
import com.trollclient.util.MovementControl;
import com.trollclient.util.Rotations;
import com.trollclient.util.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Somebody nearby died or popped a totem? Say something, then teabag the spot. */
public class Taunt extends Module {
	private static final byte TOTEM_POP = 35;
	private static final byte DEATH = 3;

	private final NumberSetting radius = add(new NumberSetting("Radius", "React to players within this distance", 16, 4, 64, 1)
			.unit("m"));
	private final BoolSetting onDeath = add(new BoolSetting("On Death", "Taunt when a player dies", true));
	private final BoolSetting onPop = add(new BoolSetting("On Totem Pop", "Taunt when a player pops a totem", false));
	private final BoolSetting chat = add(new BoolSetting("Chat", "Say something in public chat", true));
	private final TextSetting deathLines = add(new TextSetting("Death Lines", "Options split with |. {player} is who died",
			"gg {player} | ez | {player} has left the chat (forcefully) | sit, {player} | rest in pieces {player} | outplayed", 256))
			.visibleWhen(() -> chat.get() && onDeath.get());
	private final TextSetting popLines = add(new TextSetting("Pop Lines", "Options split with |. {player} {count} {s}",
			"pop goes the {player} | {player} totem count: -1 | nice totem {player}", 256))
			.visibleWhen(() -> chat.get() && onPop.get());
	private final NumberSetting cooldown = add(new NumberSetting("Chat Cooldown", "Minimum time between taunts", 6, 1, 60, 1)
			.unit("s")).visibleWhen(chat::get);
	private final BoolSetting teabag = add(new BoolSetting("Teabag", "Crouch spam after the taunt", true));
	private final NumberSetting duration = add(new NumberSetting("Duration", "How long to teabag", 3, 0.5, 10, 0.5)
			.unit("s")).visibleWhen(teabag::get);
	private final BoolSetting walkOver = add(new BoolSetting("Walk Over", "Walk to the spot first (deaths only)", true))
			.visibleWhen(teabag::get);
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Be nice when friends die", true));

	private long lastChat;
	private int teabagTicks;
	private int tick;
	private Vec3 spot;
	private int taunts;

	public Taunt() {
		super("Taunt", "Says something rude when nearby players die or pop, then teabags the spot.", Category.TROLL);
	}

	@Override
	protected void onEnable() {
		teabagTicks = 0;
		spot = null;
	}

	@Override
	public void onWorldLeave() {
		teabagTicks = 0;
		spot = null;
	}

	@Override
	public void onEntityEvent(Entity entity, byte event) {
		if (!(entity instanceof Player player) || player == mc.player || mc.player == null) {
			return;
		}
		boolean death = event == DEATH && onDeath.get();
		boolean pop = event == TOTEM_POP && onPop.get();
		if ((!death && !pop) || player.distanceTo(mc.player) > radius.get()) {
			return;
		}
		if (ignoreFriends.get() && Friends.isFriend(player)) {
			return;
		}
		taunts++;
		String name = Targets.name(player);
		long now = System.currentTimeMillis();
		if (chat.get() && now - lastChat >= cooldown.get() * 1000) {
			lastChat = now;
			ChatUtil.send(ChatUtil.format(ChatUtil.pick(death ? deathLines.get() : popLines.get()), name, 1));
		}
		if (teabag.get()) {
			teabagTicks = (int) (duration.get() * 20);
			spot = death && walkOver.get() ? player.position() : null;
		}
	}

	@Override
	public void onTick() {
		if (teabagTicks <= 0) {
			return;
		}
		tick++;
		if (spot != null) {
			Vec3 d = spot.subtract(mc.player.position());
			double dist = Math.sqrt(d.x * d.x + d.z * d.z);
			if (dist > 0.8 && dist < radius.get() + 4 && safeStep(d)) {
				// walk over first; the clock only starts once we're standing on the spot
				MovementControl.move(MovementControl.PRIORITY_FOLLOW, d.x, d.z, dist > 4);
				float[] look = Rotations.lookAt(spot);
				Rotations.request(look[0], 70, 6, true);
				return;
			}
			spot = null;
		}
		teabagTicks--;
		MovementControl.sneak(MovementControl.PRIORITY_SIGNAL + 3, (tick / 3) % 2 == 0);
	}

	/** Don't walk into lava or off a cliff for a joke. */
	private boolean safeStep(Vec3 dir) {
		double len = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
		Vec3 probe = mc.player.position().add(dir.x / len * 0.8, 0, dir.z / len * 0.8);
		BlockPos feet = BlockPos.containing(probe.x, mc.player.getY() + 0.01, probe.z);
		if (!Walkability.isPassable(mc.level, feet)) {
			return Walkability.isPassable(mc.level, feet.above()) && Walkability.isPassable(mc.level, feet.above(2));
		}
		for (int down = 1; down <= 3; down++) {
			if (Walkability.isFloor(mc.level, feet.below(down))) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String getInfo() {
		return taunts > 0 ? Integer.toString(taunts) : null;
	}
}
