package com.trollclient.util;

import com.trollclient.mixin.LocalPlayerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Server-side ("silent") rotations. Modules request a rotation during the tick;
 * {@link com.trollclient.mixin.LocalPlayerMixin} swaps it in for the whole of
 * the player's tick, so the physics (move direction, sprint jumps, elytra) use
 * the same yaw the movement packet carries, and the camera never moves.
 *
 * <p>Every rotation we produce is a whole number of mouse steps away from the
 * last one the server saw, the way real mouse input is, and nothing ever sends
 * rotation packets of its own: actions that need a rotation request it, then
 * act on a later tick once {@link #isFacing} / {@link #serverRayHit} say the
 * server has it.</p>
 */
public final class Rotations {
	private static boolean requested;
	private static float yaw;
	private static float pitch;
	private static int priority = Integer.MIN_VALUE;
	private static boolean visible;

	private static boolean swapped;
	private static boolean wasSilent;
	private static float realYaw;
	private static float realPitch;
	private static float realBodyRot;
	private static float bobX;
	private static float bobY;

	private Rotations() {
	}

	/** Requests a silent rotation for this tick. Higher priority wins; on a tie the first request stays. */
	public static void request(float yaw, float pitch, int priority, boolean visibleInThirdPerson) {
		if (requested && priority <= Rotations.priority) {
			return;
		}
		Rotations.requested = true;
		Rotations.yaw = yaw;
		Rotations.pitch = Mth.clamp(pitch, -90f, 90f);
		Rotations.priority = priority;
		Rotations.visible = visibleInThirdPerson;
	}

	public static boolean isRequested() {
		return requested;
	}

	/** Start of tick: drop anything left over (the player doesn't tick while loading, for example). */
	public static void reset() {
		requested = false;
		priority = Integer.MIN_VALUE;
	}

	public static float[] lookAt(Vec3 from, Vec3 to) {
		double dx = to.x - from.x;
		double dy = to.y - from.y;
		double dz = to.z - from.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
		return new float[]{Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90f, 90f)};
	}

	public static float[] lookAt(Vec3 to) {
		LocalPlayer p = Minecraft.getInstance().player;
		return lookAt(p.getEyePosition(), to);
	}

	/** The smallest turn the mouse can make at the current sensitivity (see MouseHandler#turnPlayer). */
	public static float mouseStep() {
		double s = Minecraft.getInstance().options.sensitivity().get() * 0.6 + 0.2;
		return (float) (s * s * s * 8.0 * 0.15);
	}

	/** {@code to}, nudged so it's a whole number of mouse steps away from {@code from}. */
	public static float snap(float from, float to) {
		float step = mouseStep();
		return from + Math.round((to - from) / step) * step;
	}

	/** Yaw the server last received from us (what the last movement packet carried). */
	public static float serverYaw() {
		return ((LocalPlayerAccessor) Minecraft.getInstance().player).troll$getYRotLast();
	}

	/** Pitch the server last received from us. */
	public static float serverPitch() {
		return ((LocalPlayerAccessor) Minecraft.getInstance().player).troll$getXRotLast();
	}

	/** True once the server already has us looking within {@code tolerance} degrees of yaw/pitch. */
	public static boolean isFacing(float yaw, float pitch, float tolerance) {
		return Math.abs(Mth.wrapDegrees(yaw - serverYaw())) <= tolerance
				&& Math.abs(Mth.clamp(pitch, -90f, 90f) - serverPitch()) <= tolerance;
	}

	/**
	 * The click a real player would make on {@code shape} at {@code pos} while
	 * looking the way the server thinks we look: where the ray hits and which
	 * face, or null if it misses or is out of reach.
	 */
	public static BlockHitResult serverRayHit(BlockPos pos, VoxelShape shape, double reach) {
		if (shape.isEmpty()) {
			return null;
		}
		Vec3 eyes = Minecraft.getInstance().player.getEyePosition();
		Vec3 dir = Vec3.directionFromRotation(serverPitch(), serverYaw());
		return shape.clip(eyes, eyes.add(dir.scale(reach)), pos);
	}

	/** Mixin hook: start of LocalPlayer#tick, before input, physics and the movement packet. */
	public static void begin(LocalPlayer p) {
		LocalPlayerAccessor acc = (LocalPlayerAccessor) p;
		if (!requested || p.isPassenger()) {
			if (wasSilent) {
				// back on the real camera: take the short way round from the last silent yaw, never a 300°+ jump
				float lastSent = acc.troll$getYRotLast();
				float turns = Math.round((p.getYRot() - lastSent) / 360f) * 360f;
				if (turns != 0) {
					p.setYRot(p.getYRot() - turns);
					p.yRotO -= turns;
				}
			}
			wasSilent = false;
			return;
		}
		swapped = true;
		realYaw = p.getYRot();
		realPitch = p.getXRot();
		realBodyRot = p.yBodyRot;
		bobX = p.xBob;
		bobY = p.yBob;
		// continue from what the server last saw, in whole mouse steps, the short way round
		float lastYaw = acc.troll$getYRotLast();
		float lastPitch = acc.troll$getXRotLast();
		p.setYRot(snap(lastYaw, lastYaw + Mth.wrapDegrees(yaw - lastYaw)));
		p.setXRot(Mth.clamp(snap(lastPitch, pitch), -90f, 90f));
	}

	/** Mixin hook: end of LocalPlayer#tick, after the movement packet went out. */
	public static void end(LocalPlayer p) {
		if (swapped) {
			p.setYRot(realYaw);
			p.setXRot(realPitch);
			// the first-person hand sway eases towards the rotation; redo it with the camera's so the hand stays put
			p.xBob = bobX + (realPitch - bobX) * 0.5f;
			p.yBob = bobY + (realYaw - bobY) * 0.5f;
			if (!visible) {
				// keep the turn to ourselves: head follows the camera, body stays within reach of it
				p.setYHeadRot(realYaw);
				p.yBodyRot = realYaw + Mth.clamp(Mth.wrapDegrees(realBodyRot - realYaw), -50f, 50f);
			}
			wasSilent = true;
		}
		swapped = false;
		requested = false;
		priority = Integer.MIN_VALUE;
	}

	/** Yaw the player is steering with this tick: the silent one while it's swapped in, otherwise the camera's. */
	public static float cameraYaw(LocalPlayer p) {
		return swapped ? realYaw : p.getYRot();
	}

	public static boolean isSwapped() {
		return swapped;
	}

	/** Turns the real camera towards {@code targetYaw} in whole mouse steps, at most {@code maxStep} degrees. */
	public static void faceClient(LocalPlayer p, float targetYaw, float maxStep) {
		float current = p.getYRot();
		float delta = Mth.clamp(Mth.wrapDegrees(targetYaw - current), -maxStep, maxStep);
		float next = snap(current, current + delta);
		p.yRotO += next - current;
		p.setYRot(next);
	}
}
