package com.trollclient.pathing;

import com.trollclient.util.MovementControl;
import com.trollclient.util.Rotations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;

/** Walks a path produced by {@link PathFinder} using {@link MovementControl}. */
public final class PathFollower {
	private List<BlockPos> path = Collections.emptyList();
	private int index;
	private Vec3 lastProgressPos = Vec3.ZERO;
	private int ticksWithoutProgress;

	public void set(List<BlockPos> path) {
		this.path = path;
		this.index = 0;
		this.ticksWithoutProgress = 0;
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null) {
			lastProgressPos = p.position();
		}
	}

	public void clear() {
		set(Collections.emptyList());
	}

	public boolean isDone() {
		return index >= path.size();
	}

	public boolean isStuck() {
		return ticksWithoutProgress > 25;
	}

	public List<BlockPos> getPath() {
		return path;
	}

	public int getIndex() {
		return index;
	}

	public BlockPos last() {
		return path.isEmpty() ? null : path.get(path.size() - 1);
	}

	/**
	 * Requests movement towards the next node.
	 *
	 * @param faceMovement turn the real camera towards the path (lets vanilla sprint)
	 * @return false once the path is finished
	 */
	public boolean tick(int priority, boolean sprint, boolean faceMovement) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null || isDone()) {
			return false;
		}
		Vec3 pos = p.position();

		// skip any nodes we've already reached (or cut past)
		while (index < path.size()) {
			BlockPos node = path.get(index);
			double dx = node.getX() + 0.5 - pos.x;
			double dz = node.getZ() + 0.5 - pos.z;
			double dy = node.getY() - pos.y;
			boolean reached = dx * dx + dz * dz < 0.35 * 0.35 && Math.abs(dy) < 1.1;
			boolean cut = index + 1 < path.size() && closerToNext(pos, index);
			if (reached || cut) {
				index++;
			} else {
				break;
			}
		}
		if (isDone()) {
			return false;
		}

		BlockPos node = path.get(index);
		double dx = node.getX() + 0.5 - pos.x;
		double dz = node.getZ() + 0.5 - pos.z;
		boolean straight = isStraightAhead();
		MovementControl.move(priority, dx, dz, sprint && straight);

		boolean stepUp = node.getY() > Math.floor(pos.y + 0.01) + 0.4;
		if (p.onGround() && (stepUp || p.horizontalCollision)) {
			MovementControl.jump();
		}
		if (p.isInWater() && node.getY() >= pos.y) {
			MovementControl.jump();
		}
		if (faceMovement) {
			float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
			Rotations.faceClient(p, yaw, 25f);
		}

		if (pos.distanceToSqr(lastProgressPos) > 0.25) {
			lastProgressPos = pos;
			ticksWithoutProgress = 0;
		} else {
			ticksWithoutProgress++;
		}
		return true;
	}

	private boolean closerToNext(Vec3 pos, int i) {
		BlockPos a = path.get(i);
		BlockPos b = path.get(i + 1);
		if (b.getY() != a.getY() || Math.abs(pos.y - a.getY()) > 0.6) {
			return false;
		}
		double da = sq(a.getX() + 0.5 - pos.x) + sq(a.getZ() + 0.5 - pos.z);
		double db = sq(b.getX() + 0.5 - pos.x) + sq(b.getZ() + 0.5 - pos.z);
		return db < da && da < 1.0;
	}

	private boolean isStraightAhead() {
		if (index + 2 >= path.size()) {
			return true;
		}
		BlockPos a = path.get(index);
		BlockPos b = path.get(index + 1);
		BlockPos c = path.get(index + 2);
		return (b.getX() - a.getX()) == (c.getX() - b.getX()) && (b.getZ() - a.getZ()) == (c.getZ() - b.getZ());
	}

	private static double sq(double v) {
		return v * v;
	}
}
