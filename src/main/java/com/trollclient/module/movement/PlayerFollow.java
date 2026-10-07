package com.trollclient.module.movement;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.pathing.PathFinder;
import com.trollclient.pathing.PathFollower;
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

import java.util.List;

public class PlayerFollow extends Module {
	private final TextSetting target = add(new TextSetting("Target", "Player to follow (blank = nearest player)", "", 16)
			.suggestions(Targets::onlineNames).placeholder("nearest"));
	private final NumberSetting distance = add(new NumberSetting("Distance", "Distance to keep from the target", 3, 1, 16, 0.5)
			.unit("m"));
	private final ModeSetting movement = add(new ModeSetting("Movement", "Pathfind around obstacles or walk straight at them",
			"Path", "Path", "Direct"));
	private final BoolSetting sprint = add(new BoolSetting("Sprint", "Sprint to catch up", true));
	private final BoolSetting faceMovement = add(new BoolSetting("Face Movement", "Turn your camera along the path", true));
	private final BoolSetting stare = add(new BoolSetting("Stare", "Constantly look at them (server side)", true));
	private final NumberSetting recalc = add(new NumberSetting("Recalculate", "Ticks between route updates", 8, 2, 40, 1)
			.unit("t")).visibleWhen(() -> movement.is("Path"));

	private final PathFollower follower = new PathFollower();
	private int ticksSincePath;
	private BlockPos lastGoal;
	private Player current;

	public PlayerFollow() {
		super("PlayerFollow", "Follows a player of your choice with pathfinding, keeping a set distance.", Category.MOVEMENT);
	}

	public void setTarget(String name) {
		target.set(name);
	}

	@Override
	protected void onEnable() {
		follower.clear();
		lastGoal = null;
		ticksSincePath = Integer.MAX_VALUE / 2;
	}

	@Override
	public void onWorldLeave() {
		follower.clear();
		current = null;
	}

	@Override
	public void onTick() {
		current = resolveTarget();
		if (current == null) {
			follower.clear();
			return;
		}
		if (stare.get()) {
			float[] look = Rotations.lookAt(current.getEyePosition());
			Rotations.request(look[0], look[1], 5, true);
		}

		double dist = mc.player.distanceTo(current);
		if (dist <= distance.get()) {
			follower.clear();
			return;
		}

		if (movement.is("Direct")) {
			Vec3 d = current.position().subtract(mc.player.position());
			MovementControl.move(MovementControl.PRIORITY_FOLLOW, d.x, d.z, sprint.get() && dist > distance.get() + 3);
			if (mc.player.horizontalCollision && mc.player.onGround()) {
				MovementControl.jump();
			}
			if (faceMovement.get()) {
				Rotations.faceClient(mc.player, (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f, 25f);
			}
			return;
		}

		ticksSincePath++;
		BlockPos goal = current.blockPosition();
		boolean moved = lastGoal == null || lastGoal.distSqr(goal) > 4;
		if (follower.isDone() || follower.isStuck() || (moved && ticksSincePath >= recalc.getInt())) {
			replan(goal);
		}
		follower.tick(MovementControl.PRIORITY_FOLLOW, sprint.get() && dist > distance.get() + 3, faceMovement.get());
	}

	private void replan(BlockPos goal) {
		ticksSincePath = 0;
		lastGoal = goal;
		BlockPos start = mc.player.blockPosition();
		if (!Walkability.isStandable(mc.level, start)) {
			int y = Walkability.findStandableY(mc.level, start.getX(), start.getY(), start.getZ(), 1, 3);
			if (y != Integer.MIN_VALUE) {
				start = new BlockPos(start.getX(), y, start.getZ());
			}
		}
		double keep = distance.get();
		Vec3 targetPos = current.position();
		List<BlockPos> path = PathFinder.find(mc.level, start,
				pos -> Vec3.atBottomCenterOf(pos).distanceTo(targetPos) <= Math.max(1.0, keep - 0.5),
				pos -> Vec3.atBottomCenterOf(pos).distanceTo(targetPos),
				null,
				PathFinder.Options.defaults());
		follower.set(path);
	}

	private Player resolveTarget() {
		String name = target.get().trim();
		if (!name.isEmpty()) {
			return Targets.byName(name);
		}
		List<Player> near = Targets.playersWithin(128, false);
		return near.isEmpty() ? null : near.get(0);
	}

	@Override
	public Player getTarget() {
		return current;
	}

	@Override
	public String getInfo() {
		if (current != null) {
			return Targets.name(current);
		}
		return target.get().isBlank() ? "nearest" : target.get() + "?";
	}
}
