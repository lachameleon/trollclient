package com.trollclient.module.movement;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.pathing.Walkability;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.Friends;
import com.trollclient.util.MovementControl;
import com.trollclient.util.Rotations;
import com.trollclient.util.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Sticks to a friend's side like private security, and steps in between them
 * and whoever gets too close, staring the intruder down.
 */
public class Bodyguard extends Module {
	private final TextSetting client = add(new TextSetting("Protect", "Player to guard (blank = nearest friend)", "", 16)
			.suggestions(Targets::onlineNames).placeholder("nearest friend"));
	private final NumberSetting range = add(new NumberSetting("Range", "Only guard someone this close to you", 32, 4, 64, 1).unit("m"));
	private final NumberSetting threatRange = add(new NumberSetting("Personal Space", "Anyone this close to them is a threat", 6, 2, 16, 0.5)
			.unit("m"));
	private final NumberSetting follow = add(new NumberSetting("Follow Distance", "How far from them to stand when nobody's around", 2.5, 1, 6, 0.5)
			.unit("m"));
	private final NumberSetting guard = add(new NumberSetting("Guard Distance", "How far in front of them to stand against a threat", 1.3, 0.8, 4, 0.1)
			.unit("m"));
	private final BoolSetting warn = add(new BoolSetting("Warn", "Crouch at intruders who get really close", true));

	private Player protectee;
	private Player threat;
	private int ticks;

	public Bodyguard() {
		super("Bodyguard", "Shadows a friend and steps between them and anyone who gets too close, staring the intruder down.",
				Category.MOVEMENT);
	}

	@Override
	protected void onEnable() {
		protectee = null;
		threat = null;
	}

	@Override
	public void onWorldLeave() {
		protectee = null;
		threat = null;
	}

	@Override
	public void onTick() {
		ticks++;
		protectee = findProtectee();
		threat = null;
		if (protectee == null) {
			return;
		}
		threat = findThreat(protectee);
		Vec3 boss = protectee.position();
		Vec3 goal;
		if (threat != null) {
			// on the line from them to the intruder, a step out in front
			Vec3 toThreat = threat.position().subtract(boss);
			toThreat = new Vec3(toThreat.x, 0, toThreat.z);
			goal = toThreat.lengthSqr() < 1e-4 ? boss : boss.add(toThreat.normalize().scale(guard.get()));
			float[] look = Rotations.lookAt(threat.getEyePosition());
			Rotations.request(look[0], look[1], 5, true);
			if (warn.get() && threat.distanceTo(protectee) < guard.get() + 1.5) {
				MovementControl.sneak(MovementControl.PRIORITY_BODYGUARD, (ticks / 3) % 2 == 0);
			}
		} else {
			// a pace behind and to the side, the way security walks
			Vec3 facing = Vec3.directionFromRotation(0, protectee.getYHeadRot());
			Vec3 back = new Vec3(-facing.x, 0, -facing.z).normalize();
			Vec3 side = new Vec3(-back.z, 0, back.x);
			goal = boss.add(back.scale(follow.get() * 0.8)).add(side.scale(follow.get() * 0.6));
			if (mc.player.distanceTo(protectee) < follow.get() + 1.5) {
				// scanning the crowd
				float[] look = Rotations.lookAt(protectee.getEyePosition());
				Rotations.request(look[0] + (float) Math.sin(ticks * 0.04f) * 60f, 5f, 2, true);
			}
		}
		Vec3 d = goal.subtract(mc.player.position());
		double flat = Math.hypot(d.x, d.z);
		if (flat < 0.4) {
			MovementControl.stop(MovementControl.PRIORITY_BODYGUARD);
			return;
		}
		Vec3 dir = new Vec3(d.x / flat, 0, d.z / flat);
		// never walk through the person we're guarding: bend round them
		Vec3 toBoss = new Vec3(boss.x - mc.player.getX(), 0, boss.z - mc.player.getZ());
		double along = Math.max(0, Math.min(flat, toBoss.dot(dir)));
		Vec3 closest = dir.scale(along).subtract(toBoss);
		if (along > 0.3 && closest.length() < 1.0) {
			Vec3 around = new Vec3(-dir.z, 0, dir.x);
			if (around.dot(closest) < 0) {
				around = around.scale(-1);
			}
			dir = dir.add(around.scale(1.3));
		}
		if (!safe(dir)) {
			MovementControl.stop(MovementControl.PRIORITY_BODYGUARD);
			return;
		}
		MovementControl.move(MovementControl.PRIORITY_BODYGUARD, dir.x, dir.z, flat > 4);
		if (mc.player.horizontalCollision && mc.player.onGround()) {
			MovementControl.jump();
		}
	}

	private Player findProtectee() {
		if (!client.get().isBlank()) {
			return Targets.resolve(client.get(), range.get(), false);
		}
		for (Player p : Targets.playersWithin(range.get(), false)) {
			if (Friends.isFriend(p)) {
				return p;
			}
		}
		return null;
	}

	/** The closest non-friend inside their personal space, us excluded. */
	private Player findThreat(Player boss) {
		Player best = null;
		double bestDist = threatRange.get();
		for (Player p : mc.level.players()) {
			if (p == mc.player || p == boss || !p.isAlive() || p.isSpectator() || Friends.isFriend(p)) {
				continue;
			}
			double dist = p.distanceTo(boss);
			if (dist < bestDist) {
				bestDist = dist;
				best = p;
			}
		}
		return best;
	}

	private boolean safe(Vec3 dir) {
		double len = Math.hypot(dir.x, dir.z);
		if (len < 1e-6) {
			return false;
		}
		Vec3 probe = mc.player.position().add(dir.x / len * 0.7, 0, dir.z / len * 0.7);
		BlockPos feet = BlockPos.containing(probe.x, mc.player.getY() + 0.01, probe.z);
		if (!Walkability.isPassable(mc.level, feet)) {
			return Walkability.isPassable(mc.level, feet.above()) && Walkability.isPassable(mc.level, feet.above(2));
		}
		if (Walkability.isDangerous(mc.level.getBlockState(feet.below()))) {
			return false;
		}
		return Walkability.isFloor(mc.level, feet.below()) || Walkability.isFloor(mc.level, feet.below(2));
	}

	@Override
	public Player getTarget() {
		return threat != null ? threat : protectee;
	}

	@Override
	public String getInfo() {
		if (protectee == null) {
			return null;
		}
		return Targets.name(protectee) + (threat != null ? " vs " + Targets.name(threat) : "");
	}
}
