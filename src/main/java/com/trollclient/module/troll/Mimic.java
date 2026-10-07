package com.trollclient.module.troll;

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
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Copies everything a player does, like a little sibling. Optionally as a mirror image. */
public class Mimic extends Module {
	private final TextSetting target = add(new TextSetting("Target", "Player to copy (blank = nearest)", "", 16)
			.suggestions(Targets::onlineNames).placeholder("nearest"));
	private final NumberSetting range = add(new NumberSetting("Range", "Only copy players this close", 24, 4, 64, 1).unit("m"));
	private final BoolSetting sneak = add(new BoolSetting("Sneak", "Crouch when they crouch", true));
	private final BoolSetting jump = add(new BoolSetting("Jump", "Jump when they jump", true));
	private final BoolSetting swing = add(new BoolSetting("Swing", "Swing when they swing", true));
	private final ModeSetting look = add(new ModeSetting("Look", "Copy their head: same way, mirrored, or not at all",
			"Mirror", "Off", "Copy", "Mirror"));
	private final ModeSetting walk = add(new ModeSetting("Walk", "Copy their steps the same way or mirrored", "Off",
			"Off", "Copy", "Mirror"));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Don't pick friends as the nearest target", false));

	private Player current;
	private Vec3 lastPos;
	private boolean lastSwinging;
	private boolean wasOnGround = true;

	public Mimic() {
		super("Mimic", "Copies a player's crouching, jumping, swinging, head and steps. Mirror mode is extra creepy.", Category.TROLL);
	}

	@Override
	protected void onEnable() {
		current = null;
		lastPos = null;
	}

	@Override
	public void onWorldLeave() {
		current = null;
		lastPos = null;
	}

	@Override
	public void onTick() {
		Player p = Targets.resolve(target.get(), range.get(), ignoreFriends.get());
		if (p != current) {
			current = p;
			lastPos = p == null ? null : p.position();
			lastSwinging = p != null && p.swinging;
		}
		if (p == null) {
			return;
		}
		Vec3 pos = p.position();
		Vec3 delta = lastPos == null ? Vec3.ZERO : pos.subtract(lastPos);
		lastPos = pos;

		if (sneak.get()) {
			MovementControl.sneak(MovementControl.PRIORITY_MIMIC, p.isCrouching());
		}
		if (jump.get()) {
			// remote players' onGround isn't synced reliably; a quick rise from rest reads as a jump
			boolean rising = delta.y > 0.2 && delta.y < 0.6;
			if (rising && wasOnGround && mc.player.onGround()) {
				MovementControl.jump();
			}
			wasOnGround = Math.abs(delta.y) < 0.01;
		}
		if (swing.get()) {
			if (p.swinging && !lastSwinging) {
				mc.player.swing(p.swingingArm == null ? InteractionHand.MAIN_HAND : p.swingingArm);
			}
			lastSwinging = p.swinging;
		}

		Vec3 normal = mirrorNormal(p);
		switch (look.get()) {
			case "Copy" -> Rotations.request(p.getYHeadRot(), p.getXRot(), 6, true);
			case "Mirror" -> {
				Vec3 dir = Vec3.directionFromRotation(p.getXRot(), p.getYHeadRot());
				Vec3 m = reflect(dir, normal);
				float yaw = (float) Math.toDegrees(Math.atan2(m.z, m.x)) - 90f;
				Rotations.request(Mth.wrapDegrees(yaw), p.getXRot(), 6, true);
			}
			default -> {
			}
		}

		double speed = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		if (!walk.is("Off") && speed > 0.03 && speed < 1.5) {
			Vec3 step = walk.is("Mirror") ? reflect(new Vec3(delta.x, 0, delta.z), normal) : new Vec3(delta.x, 0, delta.z);
			if (safe(step)) {
				MovementControl.move(MovementControl.PRIORITY_MIMIC, step.x, step.z, speed > 0.2);
			}
		}
	}

	/** Unit vector from them to us: the mirror sits halfway, facing along it. */
	private Vec3 mirrorNormal(Player p) {
		Vec3 n = mc.player.position().subtract(p.position());
		n = new Vec3(n.x, 0, n.z);
		return n.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : n.normalize();
	}

	private static Vec3 reflect(Vec3 v, Vec3 n) {
		return v.subtract(n.scale(2 * v.dot(n)));
	}

	/** Never copy someone off a ledge or into lava. */
	private boolean safe(Vec3 dir) {
		double len = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
		if (len < 1e-6) {
			return false;
		}
		Vec3 probe = mc.player.position().add(dir.x / len * 0.7, 0, dir.z / len * 0.7);
		BlockPos feet = BlockPos.containing(probe.x, mc.player.getY() + 0.01, probe.z);
		if (!Walkability.isPassable(mc.level, feet)) {
			return true; // a wall just stops us
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
