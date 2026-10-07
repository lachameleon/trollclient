package com.trollclient.module.troll;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.Delay;
import com.trollclient.util.InventoryUtil;
import com.trollclient.util.Rotations;
import com.trollclient.util.Targets;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/** Lobs snowballs and eggs at people, with real ballistics so they actually land. */
public class Pelter extends Module {
	/** Snowballs and eggs leave the hand at 1.5 b/t, fall 0.03 b/t² and lose 1% speed a tick. */
	private static final double SPEED = 1.5;
	private static final double GRAVITY = 0.03;
	private static final double DRAG = 0.99;

	private final ModeSetting ammo = add(new ModeSetting("Ammo", "What to throw", "Both", "Both", "Snowballs", "Eggs"));
	private final NumberSetting range = add(new NumberSetting("Range", "Throw at players within this distance", 20, 4, 48, 1).unit("m"));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Time between throws", 300, 50, 3000, 50).unit("ms"));
	private final BoolSetting predict = add(new BoolSetting("Predict", "Lead moving targets", true));
	private final BoolSetting arc = add(new BoolSetting("High Arc", "Lob over walls instead of throwing flat", false));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Return to your previous hotbar slot", true));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Friends don't get snowballed", true));

	private final Delay timer = new Delay();
	private int thrown;

	public Pelter() {
		super("Pelter", "Throws snowballs and eggs at nearby players, solving the arc so they hit.", Category.TROLL);
	}

	@Override
	protected void onEnable() {
		thrown = 0;
	}

	@Override
	public void onTick() {
		if (!timer.passed(delay.getInt()) || mc.player.isUsingItem() || mc.gui.screen() != null) {
			return;
		}
		Predicate<ItemStack> isAmmo = s -> switch (ammo.get()) {
			case "Snowballs" -> s.is(Items.SNOWBALL);
			case "Eggs" -> s.is(Items.EGG) || s.is(Items.BLUE_EGG) || s.is(Items.BROWN_EGG);
			default -> s.is(Items.SNOWBALL) || s.is(Items.EGG) || s.is(Items.BLUE_EGG) || s.is(Items.BROWN_EGG);
		};
		InteractionHand hand;
		int slot = -1;
		if (isAmmo.test(mc.player.getOffhandItem())) {
			hand = InteractionHand.OFF_HAND;
		} else {
			slot = InventoryUtil.findHotbar(isAmmo);
			if (slot < 0) {
				return;
			}
			hand = InteractionHand.MAIN_HAND;
		}
		List<Player> targets = Targets.playersWithin(range.get(), ignoreFriends.get());
		for (Player target : targets) {
			float[] aim = solve(target);
			if (aim != null) {
				// turn first; throw once the server has us aimed (the throw follows its idea of our look)
				Rotations.request(aim[0], aim[1], 8, true);
				if (Rotations.isFacing(aim[0], aim[1], 1.5f)) {
					throwWith(hand, slot);
					timer.reset();
				}
				return;
			}
		}
	}

	/** Yaw/pitch that lands a throw on the target's chest, or null if it's out of reach. */
	private float[] solve(Player target) {
		Vec3 from = mc.player.getEyePosition().subtract(0, 0.1, 0);
		Vec3 aim = target.position().add(0, target.getBbHeight() * 0.6, 0);
		Vec3 velocity = new Vec3(target.getX() - target.xo, 0, target.getZ() - target.zo);
		Float pitch = null;
		// solve, then re-solve against where they'll be after the flight time
		for (int pass = 0; pass < 3; pass++) {
			double dx = aim.x - from.x;
			double dz = aim.z - from.z;
			double horizontal = Math.hypot(dx, dz);
			double dy = aim.y - from.y;
			pitch = solvePitch(horizontal, dy);
			if (pitch == null) {
				return null;
			}
			if (!predict.get()) {
				break;
			}
			int ticks = flightTicks(horizontal, pitch);
			aim = target.position().add(0, target.getBbHeight() * 0.6, 0).add(velocity.scale(ticks));
		}
		float yaw = Rotations.lookAt(from, aim)[0];
		return new float[]{yaw, pitch};
	}

	/** Binary search on pitch (negative = up). Low arc searches 45° down to 45° up; high arc 45° up to 85° up. */
	private Float solvePitch(double horizontal, double dy) {
		float lo = arc.get() ? -85f : -45f;
		float hi = arc.get() ? -45f : 45f;
		double atLo = heightAt(horizontal, lo);
		double atHi = heightAt(horizontal, hi);
		// for the low arc, raising the aim (lower pitch) raises the hit point; the high arc is the other way round
		boolean rising = !arc.get();
		if (Double.isNaN(atLo) && Double.isNaN(atHi)) {
			return null;
		}
		for (int i = 0; i < 24; i++) {
			float mid = (lo + hi) / 2f;
			double h = heightAt(horizontal, mid);
			boolean tooLow = Double.isNaN(h) || h < dy;
			if (tooLow == rising) {
				hi = mid;
			} else {
				lo = mid;
			}
		}
		float pitch = (lo + hi) / 2f;
		double h = heightAt(horizontal, pitch);
		return Double.isNaN(h) || Math.abs(h - dy) > 0.8 ? null : pitch;
	}

	/** Height relative to the hand when the throw has covered {@code horizontal} blocks, NaN if it never gets there. */
	private static double heightAt(double horizontal, float pitch) {
		double rad = Math.toRadians(pitch);
		double vx = Math.cos(rad) * SPEED;
		double vy = -Math.sin(rad) * SPEED;
		double x = 0;
		double y = 0;
		for (int t = 0; t < 120; t++) {
			double nx = x + vx;
			double ny = y + vy;
			if (nx >= horizontal) {
				double f = (horizontal - x) / (nx - x);
				return y + (ny - y) * f;
			}
			x = nx;
			y = ny;
			vx *= DRAG;
			vy = vy * DRAG - GRAVITY;
			if (y < -64) {
				break;
			}
		}
		return Double.NaN;
	}

	private static int flightTicks(double horizontal, float pitch) {
		double vx = Math.cos(Math.toRadians(pitch)) * SPEED;
		double x = 0;
		for (int t = 1; t < 120; t++) {
			x += vx;
			vx *= DRAG;
			if (x >= horizontal) {
				return t;
			}
		}
		return 120;
	}

	private void throwWith(InteractionHand hand, int slot) {
		int previous = InventoryUtil.selected();
		if (hand == InteractionHand.MAIN_HAND) {
			InventoryUtil.select(slot);
		}
		float realYaw = mc.player.getYRot();
		float realPitch = mc.player.getXRot();
		// the use packet carries a rotation too: send the one the server already has, like a real click would
		mc.player.setYRot(Rotations.serverYaw());
		mc.player.setXRot(Rotations.serverPitch());
		mc.gameMode.useItem(mc.player, hand);
		mc.player.setYRot(realYaw);
		mc.player.setXRot(realPitch);
		mc.player.swing(hand);
		if (swapBack.get() && hand == InteractionHand.MAIN_HAND) {
			InventoryUtil.select(previous);
		}
		thrown++;
	}

	@Override
	public String getInfo() {
		return thrown > 0 ? Integer.toString(thrown) : null;
	}
}
