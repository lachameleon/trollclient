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
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/** Circles a player like a tiny moon. Turns around at ledges and walls. */
public class Orbit extends Module {
	private final TextSetting target = add(new TextSetting("Target", "Player to circle (blank = nearest)", "", 16)
			.suggestions(Targets::onlineNames).placeholder("nearest"));
	private final NumberSetting range = add(new NumberSetting("Range", "Only orbit players this close", 16, 4, 64, 1).unit("m"));
	private final NumberSetting radius = add(new NumberSetting("Radius", "Size of the circle", 2.5, 1, 8, 0.5).unit("m"));
	private final ModeSetting direction = add(new ModeSetting("Direction", "Which way round", "Clockwise",
			"Clockwise", "Counter", "Switch"));
	private final NumberSetting switchTime = add(new NumberSetting("Switch Every", "Seconds between direction changes", 3, 0.5, 15, 0.5)
			.unit("s")).visibleWhen(() -> direction.is("Switch"));
	private final BoolSetting face = add(new BoolSetting("Face Target", "Keep looking at them while you circle", true));
	private final BoolSetting hop = add(new BoolSetting("Hop", "Bunny hop around them", false));
	private final BoolSetting sprint = add(new BoolSetting("Sprint", "Sprint by turning your camera along the circle (not while Face Target is on)", false));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Don't pick friends as the nearest target", false));

	private Player current;
	private int spin = 1;
	private int ticks;
	private int blockedTicks;

	public Orbit() {
		super("Orbit", "Walks circles around a player at a set radius, turning back at ledges.", Category.MOVEMENT);
	}

	@Override
	protected void onEnable() {
		spin = direction.is("Counter") ? -1 : 1;
		ticks = 0;
	}

	@Override
	public void onWorldLeave() {
		current = null;
	}

	@Override
	public void onTick() {
		current = Targets.resolve(target.get(), range.get(), ignoreFriends.get());
		if (current == null) {
			return;
		}
		ticks++;
		switch (direction.get()) {
			case "Clockwise" -> spin = blockedTicks > 0 ? spin : 1;
			case "Counter" -> spin = blockedTicks > 0 ? spin : -1;
			default -> {
				if (ticks % Math.max(10, (int) (switchTime.get() * 20)) == 0) {
					spin = -spin;
				}
			}
		}
		if (blockedTicks > 0) {
			blockedTicks--;
		}

		Vec3 me = mc.player.position();
		Vec3 center = current.position();
		Vec3 out = new Vec3(me.x - center.x, 0, me.z - center.z);
		double dist = out.length();
		if (dist < 1e-3) {
			double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
			out = new Vec3(Math.cos(a), 0, Math.sin(a));
			dist = 1e-3;
		}
		Vec3 radial = out.scale(1 / dist);
		Vec3 tangent = new Vec3(-radial.z * spin, 0, radial.x * spin);
		// steer back onto the circle: push in or out proportionally to the error
		double error = radius.get() - dist;
		Vec3 move = tangent.add(radial.scale(Math.max(-1.5, Math.min(1.5, error * 0.8))));

		if (!safe(move)) {
			// ledge or wall ahead: go the other way for a bit
			spin = -spin;
			blockedTicks = 20;
			move = new Vec3(-radial.z * spin, 0, radial.x * spin).add(radial.scale(Math.max(-1.5, Math.min(1.5, error * 0.8))));
			if (!safe(move)) {
				MovementControl.stop(MovementControl.PRIORITY_ORBIT);
				return;
			}
		}
		MovementControl.move(MovementControl.PRIORITY_ORBIT, move.x, move.z, sprint.get());
		if (hop.get() && mc.player.onGround()) {
			MovementControl.jump();
		} else if (mc.player.horizontalCollision && mc.player.onGround()) {
			MovementControl.jump();
		}
		if (face.get()) {
			float[] look = Rotations.lookAt(current.getEyePosition());
			Rotations.request(look[0], look[1], 5, true);
		}
		if (sprint.get() && !face.get()) {
			Rotations.faceClient(mc.player, (float) Math.toDegrees(Math.atan2(move.z, move.x)) - 90f, 30f);
		}
	}

	private boolean safe(Vec3 dir) {
		double len = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
		if (len < 1e-6) {
			return true;
		}
		Vec3 probe = mc.player.position().add(dir.x / len * 0.75, 0, dir.z / len * 0.75);
		BlockPos feet = BlockPos.containing(probe.x, mc.player.getY() + 0.01, probe.z);
		if (!Walkability.isPassable(mc.level, feet)) {
			// one-block step up is fine, a wall isn't
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
		return current == null ? null : Targets.name(current);
	}
}
