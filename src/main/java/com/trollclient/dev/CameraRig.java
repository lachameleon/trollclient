package com.trollclient.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;

/**
 * A free camera for cinematic shots: an invisible client-side marker entity
 * the game renders from. Moves are eased and set once per tick; the renderer
 * interpolates between ticks, so motion stays smooth at any frame rate.
 *
 * <p>The game only draws your own player (and stops drawing your hand) in a
 * third-person view, which pulls the camera {@value #PULL_BACK} blocks back
 * from its entity. So the rig runs in third person and parks the marker that
 * far ahead of where the shot should be.</p>
 */
public final class CameraRig {
	private static final double PULL_BACK = 4.0;

	private static Marker camera;
	private static Vec3 shotPos = Vec3.ZERO;
	private static net.minecraft.client.CameraType previousView;
	private static Vec3 fromPos;
	private static Vec3 toPos;
	private static float fromYaw;
	private static float toYaw;
	private static float fromPitch;
	private static float toPitch;
	private static int moveTicks;
	private static int moveAge;
	/** When set, the camera keeps looking at this point (overrides the yaw/pitch tween). */
	private static Vec3 lookAt;

	private CameraRig() {
	}

	public static boolean isActive() {
		return camera != null;
	}

	/** Cuts to a new shot. */
	public static void cut(Vec3 pos, Vec3 focus) {
		float[] rot = rotationTo(pos, focus);
		Minecraft mc = Minecraft.getInstance();
		if (camera == null) {
			camera = new Marker(EntityTypes.MARKER, mc.level);
			camera.setId(-7777);
			mc.level.addEntity(camera);
		}
		if (previousView == null) {
			previousView = mc.options.getCameraType();
		}
		mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
		shotPos = pos;
		Vec3 marker = markerFor(pos, rot[0], rot[1]);
		camera.snapTo(marker.x, marker.y, marker.z, rot[0], rot[1]);
		camera.setOldPosAndRot();
		fromPos = toPos = pos;
		fromYaw = toYaw = rot[0];
		fromPitch = toPitch = rot[1];
		moveTicks = 0;
		lookAt = focus;
		mc.setCameraEntity(camera);
	}

	/** Dollies to {@code pos} over {@code seconds}, still looking at {@code focus}. */
	public static void dolly(Vec3 pos, Vec3 focus, float seconds) {
		if (camera == null) {
			cut(pos, focus);
			return;
		}
		fromPos = shotPos;
		toPos = pos;
		fromYaw = camera.getYRot();
		fromPitch = camera.getXRot();
		float[] rot = rotationTo(pos, focus);
		toYaw = fromYaw + Mth.wrapDegrees(rot[0] - fromYaw);
		toPitch = rot[1];
		moveTicks = Math.max(1, Math.round(seconds * 20));
		moveAge = 0;
		lookAt = focus;
	}

	/** Keeps tracking a (moving) point without moving the camera. */
	public static void track(Vec3 focus) {
		lookAt = focus;
	}

	public static void release() {
		Minecraft mc = Minecraft.getInstance();
		if (camera != null) {
			mc.setCameraEntity(mc.player);
			if (previousView != null) {
				mc.options.setCameraType(previousView);
				previousView = null;
			}
			if (mc.level != null) {
				mc.level.removeEntity(camera.getId(), net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
			}
			camera = null;
		}
	}

	/** End of client tick: advance the move; the renderer interpolates from the previous tick. */
	public static void tick() {
		if (camera == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (camera.isRemoved()) {
			com.trollclient.TrollClient.LOGGER.info("[showcase] camera marker was removed; adding it back");
			mc.level.addEntity(camera);
		}
		if (mc.getCameraEntity() != camera) {
			// teleports make the server send "your camera is you" again; take it back
			mc.setCameraEntity(camera);
		}
		Vec3 pos = shotPos;
		if (moveTicks > 0 && moveAge < moveTicks) {
			moveAge++;
			float k = smooth(moveAge / (float) moveTicks);
			pos = fromPos.lerp(toPos, k);
		}
		float yaw;
		float pitch;
		if (lookAt != null) {
			float[] rot = rotationTo(pos, lookAt);
			yaw = camera.getYRot() + Mth.wrapDegrees(rot[0] - camera.getYRot());
			pitch = rot[1];
		} else {
			float k = moveTicks > 0 ? smooth(moveAge / (float) moveTicks) : 1;
			yaw = Mth.lerp(k, fromYaw, toYaw);
			pitch = Mth.lerp(k, fromPitch, toPitch);
		}
		shotPos = pos;
		Vec3 marker = markerFor(pos, yaw, pitch);
		camera.setPos(marker.x, marker.y, marker.z);
		camera.setYRot(yaw);
		camera.setXRot(pitch);
		if (mc.options.getCameraType() != net.minecraft.client.CameraType.THIRD_PERSON_BACK) {
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
		}
	}

	/** Where the marker must sit so the third-person pull-back lands the camera on {@code shot}. */
	private static Vec3 markerFor(Vec3 shot, float yaw, float pitch) {
		return shot.add(Vec3.directionFromRotation(pitch, yaw).scale(PULL_BACK));
	}

	private static float smooth(float t) {
		t = Mth.clamp(t, 0f, 1f);
		return t * t * (3 - 2 * t);
	}

	private static float[] rotationTo(Vec3 from, Vec3 to) {
		double dx = to.x - from.x;
		double dy = to.y - from.y;
		double dz = to.z - from.z;
		float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		return new float[]{yaw, pitch};
	}
}
