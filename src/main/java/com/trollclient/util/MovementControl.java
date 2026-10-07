package com.trollclient.util;

import net.minecraft.util.Mth;

/**
 * Lets modules drive the player's movement keys. Requests are collected during
 * the start of the client tick and applied right after vanilla reads the
 * keyboard (see {@link com.trollclient.mixin.KeyboardInputMixin}).
 *
 * <p>Movement is requested as a world-space direction, so modules don't need to
 * turn the camera to walk somewhere. It is turned into the closest of the eight
 * directions WASD can produce relative to the yaw the server sees, so the
 * server's own physics would land us exactly where we end up.</p>
 */
public final class MovementControl {
	public static final int PRIORITY_TWERK = 0;
	public static final int PRIORITY_SIGNAL = 5;
	public static final int PRIORITY_FOLLOW = 10;
	public static final int PRIORITY_ORBIT = 12;
	public static final int PRIORITY_GOALIE = 13;
	public static final int PRIORITY_MIMIC = 15;
	public static final int PRIORITY_PICKUP = 20;
	public static final int PRIORITY_AVOID = 30;
	public static final int PRIORITY_DODGE = 40;
	/** A macro step asking to move is the user asking to move, so it beats every module. */
	public static final int PRIORITY_MACRO = 50;

	private static boolean moveRequested;
	private static int movePriority = Integer.MIN_VALUE;
	private static double dirX;
	private static double dirZ;
	private static boolean sprint;

	private static boolean jump;

	private static boolean sneakRequested;
	private static int sneakPriority = Integer.MIN_VALUE;
	private static boolean sneak;

	private MovementControl() {
	}

	public static void reset() {
		moveRequested = false;
		movePriority = Integer.MIN_VALUE;
		dirX = dirZ = 0;
		sprint = false;
		jump = false;
		sneakRequested = false;
		sneakPriority = Integer.MIN_VALUE;
		sneak = false;
	}

	/** Walk towards the world-space direction (dx, dz). A zero vector means "stand still". */
	public static void move(int priority, double dx, double dz, boolean sprint) {
		if (moveRequested && priority < movePriority) {
			return;
		}
		moveRequested = true;
		movePriority = priority;
		dirX = dx;
		dirZ = dz;
		MovementControl.sprint = sprint;
	}

	public static void stop(int priority) {
		move(priority, 0, 0, false);
	}

	public static void jump() {
		jump = true;
	}

	public static void sneak(int priority, boolean value) {
		if (sneakRequested && priority < sneakPriority) {
			return;
		}
		sneakRequested = true;
		sneakPriority = priority;
		sneak = value;
	}

	public static boolean isMoveRequested() {
		return moveRequested;
	}

	public static int getMovePriority() {
		return movePriority;
	}

	public static boolean isJumpRequested() {
		return jump;
	}

	public static boolean isSneakRequested() {
		return sneakRequested;
	}

	public static boolean getSneak() {
		return sneak;
	}

	public static boolean getSprint() {
		return sprint;
	}

	/** The requested world direction (unnormalised), or null to leave the keys alone. */
	public static double[] direction() {
		return moveRequested ? new double[]{dirX, dirZ} : null;
	}

	/**
	 * Picks the WASD combination (strafe = left minus right, forward = forward
	 * minus backward, each -1, 0 or 1) whose direction relative to {@code yaw}
	 * is closest to the world direction (dx, dz). Returns {0, 0} for no
	 * movement. {@code previous} gets a small bonus so we don't flicker between
	 * two neighbours when the target sits right between them.
	 */
	public static int[] keysFor(float yaw, double dx, double dz, int[] previous) {
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1e-4) {
			return new int[]{0, 0};
		}
		dx /= len;
		dz /= len;
		float sin = Mth.sin(yaw * Mth.DEG_TO_RAD);
		float cos = Mth.cos(yaw * Mth.DEG_TO_RAD);
		double wantForward = -dx * sin + dz * cos;
		double wantStrafe = dx * cos + dz * sin;
		int[] best = {0, 0};
		double bestScore = -Double.MAX_VALUE;
		for (int forward = -1; forward <= 1; forward++) {
			for (int strafe = -1; strafe <= 1; strafe++) {
				if (forward == 0 && strafe == 0) {
					continue;
				}
				double norm = Math.sqrt(forward * forward + strafe * strafe);
				double score = (forward * wantForward + strafe * wantStrafe) / norm;
				if (previous != null && previous[0] == strafe && previous[1] == forward) {
					score += 0.04;
				}
				if (score > bestScore) {
					bestScore = score;
					best = new int[]{strafe, forward};
				}
			}
		}
		return best;
	}
}
