package com.trollclient.module.movement;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.pathing.Walkability;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.MovementControl;
import com.trollclient.util.Rotations;
import com.trollclient.util.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Hangs around right behind a player, out of their field of view. Angel mode
 * only moves while they aren't looking, and freezes the moment they turn round.
 */
public class Stalker extends Module {
	private final TextSetting target = add(new TextSetting("Target", "Player to stalk (blank = nearest)", "", 16)
			.suggestions(Targets::onlineNames).placeholder("nearest"));
	private final ModeSetting mode = add(new ModeSetting("Mode", "Behind: stay at their back. Angel: only move while unseen",
			"Behind", "Behind", "Angel"));
	private final NumberSetting range = add(new NumberSetting("Range", "Only stalk players this close", 24, 4, 64, 1).unit("m"));
	private final NumberSetting distance = add(new NumberSetting("Distance", "How close to get", 2.5, 1, 8, 0.5).unit("m"));
	private final NumberSetting fov = add(new NumberSetting("Their FOV", "How wide their view counts as (for staying unseen)", 70, 30, 120, 5)
			.unit("°"));
	private final BoolSetting sneak = add(new BoolSetting("Sneak", "Crouch when close, hides your nametag behind walls", true));
	private final BoolSetting stare = add(new BoolSetting("Stare", "Keep looking at them", true));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Don't pick friends as the nearest target", false));

	private Player current;
	private boolean watched;

	public Stalker() {
		super("Stalker", "Creeps along behind a player out of their view. Angel mode freezes whenever they look.", Category.MOVEMENT);
	}

	@Override
	public void onWorldLeave() {
		current = null;
	}

	@Override
	public void onTick() {
		current = Targets.resolve(target.get(), range.get(), ignoreFriends.get());
		if (current == null) {
			watched = false;
			return;
		}
		watched = isWatched(current);
		double dist = mc.player.distanceTo(current);

		if (mode.is("Angel") && watched) {
			// don't move a muscle, not even your head: hold the look the server already has
			MovementControl.stop(MovementControl.PRIORITY_MIMIC + 5);
			MovementControl.sneak(MovementControl.PRIORITY_MIMIC + 5, false);
			Rotations.request(Rotations.serverYaw(), Rotations.serverPitch(), 5, true);
			return;
		}
		if (stare.get()) {
			float[] look = Rotations.lookAt(current.getEyePosition());
			Rotations.request(look[0], look[1], 5, true);
		}
		Vec3 goal;
		if (mode.is("Behind")) {
			Vec3 look = Vec3.directionFromRotation(0, current.getYRot());
			goal = current.position().subtract(look.scale(distance.get()));
		} else {
			goal = current.position();
		}
		Vec3 d = goal.subtract(mc.player.position());
		double flat = Math.hypot(d.x, d.z);
		double stopAt = mode.is("Behind") ? 0.6 : distance.get();
		// crouch once we're in position (it hides the nametag), not on the way there: sneaking is slow
		if (sneak.get()) {
			MovementControl.sneak(MovementControl.PRIORITY_MIMIC, flat <= stopAt + 1.2 && !watched);
		}
		if (flat <= stopAt) {
			return;
		}
		Vec3 dir = new Vec3(d.x, 0, d.z).normalize();
		// never walk through them: if the straight line passes close, bend round the side away from them
		Vec3 toTarget = current.position().subtract(mc.player.position());
		toTarget = new Vec3(toTarget.x, 0, toTarget.z);
		double along = Math.max(0, Math.min(flat, toTarget.dot(dir)));
		Vec3 closest = dir.scale(along).subtract(toTarget);
		if (mode.is("Behind") && along > 0.3 && closest.length() < 1.8) {
			Vec3 side = new Vec3(-dir.z, 0, dir.x);
			if (side.dot(closest) < 0) {
				side = side.scale(-1);
			}
			dir = dir.add(side.scale(1.4));
		}
		if (mode.is("Behind") && watched) {
			// in their view: swing wide round the side instead of crossing in front of them
			Vec3 toMe = mc.player.position().subtract(current.position());
			Vec3 side = new Vec3(-toMe.z, 0, toMe.x).normalize();
			if (side.dot(dir) < 0) {
				side = side.scale(-1);
			}
			dir = dir.add(side.scale(1.2));
		}
		if (!safe(dir)) {
			MovementControl.stop(MovementControl.PRIORITY_MIMIC + 1);
			return;
		}
		MovementControl.move(MovementControl.PRIORITY_MIMIC + 1, dir.x, dir.z, flat > 6);
		if (mc.player.horizontalCollision && mc.player.onGround()) {
			MovementControl.jump();
		}
	}

	/** Is the target looking roughly our way, with nothing in between? */
	private boolean isWatched(Player p) {
		Vec3 eye = p.getEyePosition();
		Vec3 toMe = mc.player.getEyePosition().subtract(eye);
		double len = toMe.length();
		if (len < 1e-3) {
			return true;
		}
		Vec3 view = Vec3.directionFromRotation(p.getXRot(), p.getYHeadRot());
		double cos = view.dot(toMe.scale(1 / len));
		if (cos < Math.cos(Math.toRadians(fov.get() / 2))) {
			return false;
		}
		HitResult hit = mc.level.clip(new ClipContext(eye, mc.player.getEyePosition(), ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, p));
		return hit.getType() == HitResult.Type.MISS;
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
		return current;
	}

	@Override
	public String getInfo() {
		if (current == null) {
			return null;
		}
		return Targets.name(current) + (watched ? " (seen)" : "");
	}
}
