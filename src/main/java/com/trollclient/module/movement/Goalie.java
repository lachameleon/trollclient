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

/**
 * Plants itself right in front of a player and stays there, sidestepping
 * when they sidestep, like a goalkeeper who picked the wrong sport.
 */
public class Goalie extends Module {
	private final TextSetting target = add(new TextSetting("Target", "Player to block (blank = nearest)", "", 16)
			.suggestions(Targets::onlineNames).placeholder("nearest"));
	private final NumberSetting range = add(new NumberSetting("Range", "Only block players this close", 16, 4, 64, 1).unit("m"));
	private final NumberSetting distance = add(new NumberSetting("Distance", "How far in front of them to stand", 1.4, 0.8, 5, 0.1)
			.unit("m"));
	private final ModeSetting block = add(new ModeSetting("Block", "Stand where they're walking, or where they're looking",
			"Walk", "Walk", "Look"));
	private final BoolSetting stare = add(new BoolSetting("Stare", "Look them in the eye", true));
	private final BoolSetting jump = add(new BoolSetting("Jump", "Jump when they jump, so they can't hop past", true));
	private final BoolSetting taunt = add(new BoolSetting("Taunt", "Crouch spam once you're in position", false));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Don't pick friends as the nearest target", true));

	private Player current;
	/** Their smoothed walking velocity, blocks per tick. */
	private Vec3 velocity = Vec3.ZERO;
	private Vec3 lastPos;
	private boolean theyWereOnGround = true;
	private boolean inPosition;
	private int ticks;

	public Goalie() {
		super("Goalie", "Gets in front of a player and stays there, matching every sidestep. Good luck getting past.", Category.MOVEMENT);
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
		ticks++;
		Player p = Targets.resolve(target.get(), range.get(), ignoreFriends.get());
		if (p != current) {
			current = p;
			lastPos = null;
			velocity = Vec3.ZERO;
		}
		inPosition = false;
		if (p == null) {
			return;
		}
		Vec3 pos = p.position();
		Vec3 delta = lastPos == null ? Vec3.ZERO : pos.subtract(lastPos);
		lastPos = pos;
		// smooth out the jitter of remote player movement
		velocity = velocity.scale(0.6).add(new Vec3(delta.x, 0, delta.z).scale(0.4));

		if (stare.get()) {
			float[] look = Rotations.lookAt(p.getEyePosition());
			Rotations.request(look[0], look[1], 5, true);
		}
		if (jump.get()) {
			// remote players' onGround isn't synced reliably; a quick rise from rest reads as a jump
			boolean rising = delta.y > 0.2 && delta.y < 0.6;
			if (rising && theyWereOnGround && mc.player.onGround()) {
				MovementControl.jump();
			}
			theyWereOnGround = Math.abs(delta.y) < 0.01;
		}

		Vec3 goal = goal(p);
		Vec3 d = goal.subtract(mc.player.position());
		double flat = Math.hypot(d.x, d.z);
		if (flat < 0.35) {
			inPosition = true;
			MovementControl.stop(MovementControl.PRIORITY_GOALIE);
			if (taunt.get()) {
				MovementControl.sneak(MovementControl.PRIORITY_GOALIE, (ticks / 3) % 2 == 0);
			}
			return;
		}
		Vec3 dir = new Vec3(d.x, 0, d.z).normalize();
		// coming from behind: never walk through them, bend round the side instead
		Vec3 toTarget = pos.subtract(mc.player.position());
		toTarget = new Vec3(toTarget.x, 0, toTarget.z);
		double along = Math.max(0, Math.min(flat, toTarget.dot(dir)));
		Vec3 closest = dir.scale(along).subtract(toTarget);
		if (along > 0.3 && closest.length() < 1.2) {
			Vec3 side = new Vec3(-dir.z, 0, dir.x);
			if (side.dot(closest) < 0) {
				side = side.scale(-1);
			}
			dir = dir.add(side.scale(1.3));
		}
		if (!safe(dir)) {
			MovementControl.stop(MovementControl.PRIORITY_GOALIE);
			return;
		}
		// sprint only works forwards: worth it when they're far ahead of us
		MovementControl.move(MovementControl.PRIORITY_GOALIE, dir.x, dir.z, flat > 4);
		if (mc.player.horizontalCollision && mc.player.onGround()) {
			MovementControl.jump();
		}
	}

	/**
	 * The spot in front of them: along their walking direction (led a few
	 * ticks ahead so we get there first), or their look when standing still.
	 */
	private Vec3 goal(Player p) {
		double speed = velocity.length();
		Vec3 facing;
		if (block.is("Walk") && speed > 0.03) {
			facing = velocity.normalize();
		} else {
			Vec3 look = Vec3.directionFromRotation(0, p.getYHeadRot());
			facing = new Vec3(look.x, 0, look.z).normalize();
		}
		Vec3 lead = block.is("Walk") ? velocity.scale(4) : Vec3.ZERO;
		return p.position().add(lead).add(facing.scale(distance.get()));
	}

	/** Never block someone by walking off a ledge or into lava. */
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
		return Targets.name(current) + (inPosition ? " (blocking)" : "");
	}
}
