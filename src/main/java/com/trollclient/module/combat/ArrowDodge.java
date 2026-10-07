package com.trollclient.module.combat;

import com.trollclient.mixin.AbstractArrowAccessor;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.pathing.Walkability;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.MovementControl;
import com.trollclient.util.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ArrowDodge extends Module {
	private final NumberSetting range = add(new NumberSetting("Range", "Watch players and projectiles this far away", 40, 8, 80, 1)
			.unit("m"));
	private final BoolSetting detectAim = add(new BoolSetting("Detect Aim", "Dodge when a bow, crossbow or trident is pointed at you", true));
	private final NumberSetting tolerance = add(new NumberSetting("Aim Tolerance", "How close their aim must pass to count", 1.4, 0.3, 4, 0.1)
			.unit("m")).visibleWhen(detectAim::get);
	private final BoolSetting detectProjectiles = add(new BoolSetting("Detect Arrows", "Dodge arrows and tridents already in flight", true));
	private final BoolSetting allProjectiles = add(new BoolSetting("All Projectiles", "Also dodge snowballs, eggs, pearls, charges...", false))
			.visibleWhen(detectProjectiles::get);
	private final NumberSetting lookahead = add(new NumberSetting("Lookahead", "Ticks of flight to simulate", 20, 5, 60, 1)
			.unit("t")).visibleWhen(detectProjectiles::get);
	private final BoolSetting crouch = add(new BoolSetting("Crouch", "Duck under head-height shots, or when there's nowhere safe to go", true));
	private final BoolSetting jump = add(new BoolSetting("Jump", "Hop over shots aimed at your feet", false));
	private final BoolSetting safeEdges = add(new BoolSetting("Safe Edges", "Never step off a ledge or pillar while dodging", true));
	private final NumberSetting commit = add(new NumberSetting("Commit", "Keep dodging this many ticks after the threat", 6, 1, 20, 1)
			.unit("t"));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Friends can aim at you all they like", true));

	private record Threat(Vec3 origin, Vec3 direction, double heightOnBody, int ticksAway, String source) {
	}

	private Vec3 dodgeDir;
	private int commitLeft;
	private boolean duck;
	private String lastSource;

	public ArrowDodge() {
		super("ArrowDodge", "Strafes out of the way of anyone aiming at you, without walking off your pillar.", Category.COMBAT);
	}

	@Override
	protected void onEnable() {
		dodgeDir = null;
		commitLeft = 0;
	}

	@Override
	public void onTick() {
		Threat threat = findThreat();
		duck = false;
		if (threat != null) {
			lastSource = threat.source();
			commitLeft = commit.getInt();
			Vec3 chosen = chooseDirection(threat);
			if (chosen != null) {
				dodgeDir = chosen;
			} else {
				dodgeDir = null;
				duck = crouch.get();
			}
			if (crouch.get() && threat.heightOnBody() > 1.25) {
				duck = true;
			}
			if (jump.get() && threat.heightOnBody() < 0.5 && mc.player.onGround()) {
				MovementControl.jump();
			}
		}

		if (commitLeft > 0) {
			commitLeft--;
			if (dodgeDir != null) {
				if (isSafe(dodgeDir, 0.8)) {
					MovementControl.move(MovementControl.PRIORITY_DODGE, dodgeDir.x, dodgeDir.z, false);
				} else {
					// the ground ran out: stop dead and duck rather than fall
					MovementControl.stop(MovementControl.PRIORITY_DODGE);
					dodgeDir = null;
					duck |= crouch.get() || safeEdges.get();
				}
			}
			if (safeEdges.get() && dodgeDir != null && !isSafe(dodgeDir, 1.4)) {
				// vanilla sneaking refuses to walk off edges, a second safety net
				duck = true;
			}
		}
		if (duck) {
			MovementControl.sneak(MovementControl.PRIORITY_DODGE, true);
		}
	}

	private Threat findThreat() {
		List<Threat> threats = new ArrayList<>();
		if (detectAim.get()) {
			for (Player p : Targets.playersWithin(range.get(), ignoreFriends.get())) {
				if (!isAiming(p)) {
					continue;
				}
				Threat t = aimThreat(p);
				if (t != null) {
					threats.add(t);
				}
			}
		}
		if (detectProjectiles.get()) {
			for (Entity e : mc.level.entitiesForRendering()) {
				Threat t = projectileThreat(e);
				if (t != null) {
					threats.add(t);
				}
			}
		}
		Threat best = null;
		for (Threat t : threats) {
			if (best == null || t.ticksAway() < best.ticksAway()) {
				best = t;
			}
		}
		return best;
	}

	private static boolean isAiming(Player p) {
		if (p.isUsingItem()) {
			ItemUseAnimation anim = p.getUseItem().getUseAnimation();
			boolean ranged = anim == ItemUseAnimation.BOW || anim == ItemUseAnimation.CROSSBOW
					|| anim == ItemUseAnimation.TRIDENT || anim == ItemUseAnimation.SPEAR;
			return ranged && p.getTicksUsingItem() > 3;
		}
		return CrossbowItem.isCharged(p.getMainHandItem()) || CrossbowItem.isCharged(p.getOffhandItem());
	}

	/** Closest approach between their look ray and our body. */
	private Threat aimThreat(Player p) {
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getViewVector(1f);
		Vec3 feet = mc.player.position();
		double bestDist = Double.MAX_VALUE;
		double bestHeight = 0;
		double bestT = 0;
		for (double h = 0.1; h <= mc.player.getBbHeight(); h += 0.4) {
			Vec3 q = feet.add(0, h, 0);
			double t = q.subtract(eye).dot(look);
			if (t <= 0) {
				continue;
			}
			double d = q.distanceTo(eye.add(look.scale(t)));
			if (d < bestDist) {
				bestDist = d;
				bestHeight = h;
				bestT = t;
			}
		}
		if (bestDist > tolerance.get()) {
			return null;
		}
		// an arrow covers ~3 blocks a tick; that's a fair "time to impact" estimate
		return new Threat(eye, look, bestHeight, (int) (bestT / 3.0), Targets.name(p));
	}

	private Threat projectileThreat(Entity e) {
		boolean arrow = e instanceof AbstractArrow;
		if (!arrow && !(allProjectiles.get() && e instanceof Projectile)) {
			return null;
		}
		if (arrow && ((AbstractArrowAccessor) e).troll$isInGround()) {
			return null;
		}
		Entity owner = ((Projectile) e).getOwner();
		if (owner == mc.player || e.distanceTo(mc.player) > range.get()) {
			return null;
		}
		Vec3 pos = e.position();
		Vec3 vel = e.getDeltaMovement();
		if (vel.lengthSqr() < 0.01) {
			return null;
		}
		AABB box = mc.player.getBoundingBox().inflate(0.4);
		double gravity = e.getGravity();
		for (int i = 0; i < lookahead.getInt(); i++) {
			Vec3 next = pos.add(vel);
			Optional<Vec3> hit = box.clip(pos, next);
			if (hit.isPresent() || box.contains(pos)) {
				double height = hit.map(v -> v.y).orElse(pos.y) - mc.player.getY();
				String source = owner instanceof Player op ? Targets.name(op) + "'s arrow" : "projectile";
				return new Threat(e.position(), vel.normalize(), height, i, source);
			}
			vel = vel.scale(0.99).subtract(0, gravity, 0);
			pos = next;
		}
		return null;
	}

	/** Perpendicular to the shot, preferring the side we're already on. */
	private Vec3 chooseDirection(Threat threat) {
		Vec3 d = new Vec3(threat.direction().x, 0, threat.direction().z);
		Vec3 rel = mc.player.position().subtract(threat.origin());
		if (d.lengthSqr() < 1e-4) {
			d = new Vec3(rel.x, 0, rel.z);
		}
		if (d.lengthSqr() < 1e-4) {
			d = new Vec3(1, 0, 0);
		}
		d = d.normalize();
		Vec3 left = new Vec3(-d.z, 0, d.x);
		Vec3 right = left.scale(-1);
		boolean onLeft = rel.x * left.x + rel.z * left.z >= 0;
		Vec3 first = onLeft ? left : right;
		Vec3 second = onLeft ? right : left;
		// keep the current direction if it still works, so we don't jitter
		if (dodgeDir != null && (dodgeDir.dot(first) > 0.7 || dodgeDir.dot(second) > 0.7) && isSafe(dodgeDir, 1.2)) {
			return dodgeDir;
		}
		Vec3[] options = {
				first, second,
				first.add(d.scale(0.6)).normalize(), second.add(d.scale(0.6)).normalize(),
				first.add(d.scale(-0.6)).normalize(), second.add(d.scale(-0.6)).normalize()
		};
		for (Vec3 option : options) {
			if (isSafe(option, 1.2)) {
				return option;
			}
		}
		return null;
	}

	/** Every sample along the step has room for our body and solid ground under it. */
	private boolean isSafe(Vec3 dir, double distance) {
		Vec3 start = mc.player.position();
		double y = Math.floor(start.y + 0.01);
		for (double s = 0.3; s <= distance + 1e-6; s += 0.3) {
			Vec3 p = start.add(dir.scale(s));
			BlockPos feet = BlockPos.containing(p.x, y, p.z);
			if (!Walkability.isPassable(mc.level, feet) || !Walkability.isPassable(mc.level, feet.above())) {
				return false;
			}
			if (safeEdges.get() && !Walkability.isFloor(mc.level, feet.below())) {
				return false;
			}
		}
		return true;
	}

	@Override
	public String getInfo() {
		return commitLeft > 0 && lastSource != null ? lastSource : null;
	}
}
