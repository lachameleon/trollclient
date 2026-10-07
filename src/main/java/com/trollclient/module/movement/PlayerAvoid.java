package com.trollclient.module.movement;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.pathing.PathFinder;
import com.trollclient.pathing.PathFollower;
import com.trollclient.pathing.Walkability;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.MovementControl;
import com.trollclient.util.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class PlayerAvoid extends Module {
	private final NumberSetting radius = add(new NumberSetting("Radius", "Run from players inside this radius", 12, 3, 48, 1)
			.unit("m"));
	private final NumberSetting fleeDistance = add(new NumberSetting("Flee Distance", "How far away to try and get", 24, 8, 64, 1)
			.unit("m"));
	private final BoolSetting sprint = add(new BoolSetting("Sprint", "Sprint while fleeing", true));
	private final BoolSetting faceMovement = add(new BoolSetting("Face Movement", "Turn your camera along the path (needed for sprinting)", true));
	private final NumberSetting maxFall = add(new NumberSetting("Max Drop", "Highest ledge the path may drop off", 3, 0, 4, 1)
			.unit("b"));
	private final NumberSetting recalc = add(new NumberSetting("Recalculate", "Ticks between route updates", 10, 2, 40, 1)
			.unit("t"));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Don't run from friends", true));

	private final PathFollower follower = new PathFollower();
	private int ticksSincePath;
	private List<Player> threats = List.of();

	public PlayerAvoid() {
		super("PlayerAvoid", "Runs away from every player within a radius using a threat-aware pathfinder.", Category.MOVEMENT);
	}

	@Override
	protected void onEnable() {
		follower.clear();
		ticksSincePath = Integer.MAX_VALUE / 2;
	}

	@Override
	public void onWorldLeave() {
		follower.clear();
	}

	@Override
	public void onTick() {
		threats = Targets.playersWithin(radius.get(), ignoreFriends.get());
		if (threats.isEmpty()) {
			follower.clear();
			return;
		}
		ticksSincePath++;
		if (follower.isDone() || follower.isStuck() || ticksSincePath >= recalc.getInt()) {
			replan();
		}
		if (!follower.tick(MovementControl.PRIORITY_AVOID, sprint.get(), faceMovement.get())) {
			fleeDirect();
		}
	}

	private void replan() {
		ticksSincePath = 0;
		BlockPos start = startPos();
		if (start == null) {
			follower.clear();
			return;
		}
		BlockPos goal = pickGoal(start);
		double danger = radius.get();
		PathFinder.Options opts = new PathFinder.Options(5000, 8, maxFall.getInt(), true);
		List<BlockPos> path = PathFinder.find(mc.level, start,
				pos -> goal != null ? pos.distSqr(goal) <= 2 : minThreatDist(pos) >= fleeDistance.get(),
				pos -> goal != null ? Math.sqrt(pos.distSqr(goal)) : Math.max(0, fleeDistance.get() - minThreatDist(pos)),
				pos -> {
					// walking near a threat is expensive, walking into one is very expensive
					double d = minThreatDist(pos);
					return d < danger ? (danger - d) * 0.8 : 0;
				},
				opts);
		follower.set(path);
	}

	/** Samples a ring of destinations and keeps the one furthest from everyone. */
	private BlockPos pickGoal(BlockPos start) {
		Vec3 away = fleeVector();
		BlockPos best = null;
		double bestScore = -Double.MAX_VALUE;
		double dist = fleeDistance.get();
		for (int i = 0; i < 24; i++) {
			double angle = Math.PI * 2 * i / 24;
			double cx = Math.cos(angle);
			double cz = Math.sin(angle);
			int x = (int) Math.floor(start.getX() + cx * dist);
			int z = (int) Math.floor(start.getZ() + cz * dist);
			if (!mc.level.isLoaded(new BlockPos(x, start.getY(), z))) {
				continue;
			}
			int y = Walkability.findStandableY(mc.level, x, start.getY(), z, 6, 8);
			if (y == Integer.MIN_VALUE) {
				continue;
			}
			BlockPos candidate = new BlockPos(x, y, z);
			double score = minThreatDist(candidate) + (cx * away.x + cz * away.z) * 6 - Math.abs(y - start.getY()) * 0.5;
			if (score > bestScore) {
				bestScore = score;
				best = candidate;
			}
		}
		return best;
	}

	/** Weighted direction pointing away from all threats (closer ones count more). */
	private Vec3 fleeVector() {
		Vec3 me = mc.player.position();
		double x = 0, z = 0;
		for (Player p : threats) {
			Vec3 d = me.subtract(p.position());
			double len = Math.max(0.5, Math.sqrt(d.x * d.x + d.z * d.z));
			x += d.x / (len * len);
			z += d.z / (len * len);
		}
		double len = Math.sqrt(x * x + z * z);
		return len < 1e-6 ? new Vec3(1, 0, 0) : new Vec3(x / len, 0, z / len);
	}

	/** No path at all (boxed in): at least shuffle directly away if it's safe. */
	private void fleeDirect() {
		Vec3 away = fleeVector();
		Vec3 probe = mc.player.position().add(away.scale(0.8));
		BlockPos feet = BlockPos.containing(probe.x, mc.player.getY() + 0.01, probe.z);
		if (Walkability.isPassable(mc.level, feet) && Walkability.isFloor(mc.level, feet.below())) {
			MovementControl.move(MovementControl.PRIORITY_AVOID, away.x, away.z, sprint.get());
		}
	}

	private double minThreatDist(BlockPos pos) {
		double min = Double.MAX_VALUE;
		for (Player p : threats) {
			double dx = p.getX() - (pos.getX() + 0.5);
			double dy = (p.getY() - pos.getY()) * 0.5;
			double dz = p.getZ() - (pos.getZ() + 0.5);
			min = Math.min(min, Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		return min;
	}

	private BlockPos startPos() {
		BlockPos feet = mc.player.blockPosition();
		if (Walkability.isStandable(mc.level, feet)) {
			return feet;
		}
		int y = Walkability.findStandableY(mc.level, feet.getX(), feet.getY(), feet.getZ(), 1, 3);
		return y == Integer.MIN_VALUE ? feet : new BlockPos(feet.getX(), y, feet.getZ());
	}

	@Override
	public String getInfo() {
		return threats.isEmpty() ? null : Integer.toString(threats.size());
	}
}
