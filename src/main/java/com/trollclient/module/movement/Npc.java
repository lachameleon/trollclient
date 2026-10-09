package com.trollclient.module.movement;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.pathing.Walkability;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.MovementControl;
import com.trollclient.util.Rotations;
import com.trollclient.util.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Acts like a villager: ambles about with no purpose, stops to stare at anyone who comes near, and says "hrmm". */
public class Npc extends Module {
	private final NumberSetting roam = add(new NumberSetting("Roam", "Wander at most this far from where you switched it on", 10, 3, 40, 1)
			.unit("m"));
	private final NumberSetting stareRange = add(new NumberSetting("Stare Range", "Stop and stare at players this close (0 = never)", 6, 0, 16, 1)
			.unit("m"));
	private final BoolSetting mumble = add(new BoolSetting("Mumble", "Say \"hrmm\" in chat now and then", false));
	private final NumberSetting mumbleEvery = add(new NumberSetting("Mumble Every", "Average time between mumbles", 40, 10, 300, 5)
			.unit("s")).visibleWhen(mumble::get);

	private enum State { IDLE, WALK, STARE }

	private State state = State.IDLE;
	private Vec3 home;
	private Vec3 goal;
	private int stateTicks;
	private int duration;
	private float idleYaw;
	private float idlePitch;
	private Player staringAt;
	private long nextMumble;

	public Npc() {
		super("NPC", "Wanders around aimlessly like a villager, stops to stare at anyone who walks by, and mumbles \"hrmm\".",
				Category.MOVEMENT);
	}

	@Override
	protected void onEnable() {
		home = mc.player == null ? null : mc.player.position();
		idle();
		nextMumble = System.currentTimeMillis() + 5000;
	}

	@Override
	public void onWorldLeave() {
		home = null;
		staringAt = null;
	}

	@Override
	public void onTick() {
		if (home == null || home.distanceTo(mc.player.position()) > roam.get() * 3) {
			// teleported, respawned or dragged off somewhere: this is home now
			home = mc.player.position();
		}
		stateTicks++;
		Player near = stareRange.getInt() > 0 ? first(Targets.playersWithin(stareRange.get(), false)) : null;
		if (near != null && state != State.STARE) {
			state = State.STARE;
			stateTicks = 0;
		}
		switch (state) {
			case STARE -> stare(near);
			case WALK -> walk();
			default -> {
				Rotations.request(idleYaw, idlePitch, 1, true);
				if (stateTicks >= duration) {
					wander();
				}
			}
		}
		if (mumble.get() && System.currentTimeMillis() >= nextMumble) {
			ChatUtil.send(ChatUtil.pick("hrmm | hmm | hrm? | hmph | hrrrm | huh"));
			long avg = (long) (mumbleEvery.get() * 1000);
			nextMumble = System.currentTimeMillis() + avg / 2 + ThreadLocalRandom.current().nextLong(avg);
		}
	}

	private void stare(Player near) {
		staringAt = near;
		if (near == null) {
			// they wandered off: carry on with our very important business after a moment
			staringAt = null;
			idle();
			return;
		}
		MovementControl.stop(MovementControl.PRIORITY_NPC);
		float[] look = Rotations.lookAt(near.getEyePosition());
		Rotations.request(look[0], look[1], 1, true);
		idleYaw = look[0];
		idlePitch = look[1];
	}

	private void walk() {
		Vec3 d = goal.subtract(mc.player.position());
		double flat = Math.hypot(d.x, d.z);
		if (flat < 0.6 || stateTicks > 200) {
			idle();
			return;
		}
		Vec3 dir = new Vec3(d.x / flat, 0, d.z / flat);
		if (!safe(dir)) {
			idle();
			return;
		}
		MovementControl.move(MovementControl.PRIORITY_NPC, dir.x, dir.z, false);
		// face where we're going, head bobbing about a little like they do
		float yaw = (float) Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90f;
		Rotations.request(Mth.wrapDegrees(yaw + Mth.sin(stateTicks * 0.1f) * 8f), 10f + Mth.sin(stateTicks * 0.07f) * 6f, 1, true);
		idleYaw = yaw;
		if (mc.player.horizontalCollision && mc.player.onGround()) {
			MovementControl.jump();
		}
	}

	private void idle() {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		state = State.IDLE;
		stateTicks = 0;
		duration = rnd.nextInt(30, 110);
		idleYaw = Mth.wrapDegrees(idleYaw + rnd.nextFloat(-70f, 70f));
		idlePitch = rnd.nextFloat(-10f, 25f);
	}

	private void wander() {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		double angle = rnd.nextDouble(Math.PI * 2);
		double dist = rnd.nextDouble(2, Math.max(2.5, roam.get()));
		goal = home.add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
		state = State.WALK;
		stateTicks = 0;
	}

	/** One block step ups are fine; walls, drops and lava aren't. */
	private boolean safe(Vec3 dir) {
		Vec3 probe = mc.player.position().add(dir.x * 0.7, 0, dir.z * 0.7);
		BlockPos feet = BlockPos.containing(probe.x, mc.player.getY() + 0.01, probe.z);
		if (!Walkability.isPassable(mc.level, feet)) {
			return Walkability.isPassable(mc.level, feet.above()) && Walkability.isPassable(mc.level, feet.above(2));
		}
		if (Walkability.isDangerous(mc.level.getBlockState(feet.below()))) {
			return false;
		}
		return Walkability.isFloor(mc.level, feet.below()) || Walkability.isFloor(mc.level, feet.below(2));
	}

	private static Player first(List<Player> list) {
		return list.isEmpty() ? null : list.get(0);
	}

	@Override
	public Player getTarget() {
		return state == State.STARE ? staringAt : null;
	}

	@Override
	public String getInfo() {
		return switch (state) {
			case STARE -> staringAt == null ? null : "staring";
			case WALK -> "wandering";
			default -> "idle";
		};
	}
}
