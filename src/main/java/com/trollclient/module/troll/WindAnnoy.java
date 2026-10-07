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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class WindAnnoy extends Module {
	/** Wind charges fly in a straight line at this many blocks per tick. */
	private static final double CHARGE_SPEED = 1.5;

	private final NumberSetting range = add(new NumberSetting("Range", "Throw at players within this distance", 12, 3, 32, 0.5)
			.unit("m"));
	private final ModeSetting delayMode = add(new ModeSetting("Delay Mode", "Fixed delay, or a random one between min and max",
			"Fixed", "Fixed", "Random"));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Time between throws", 600, 0, 3000, 25)
			.unit("ms")).visibleWhen(() -> delayMode.is("Fixed"));
	private final NumberSetting minDelay = add(new NumberSetting("Min Delay", "Shortest random delay", 400, 0, 3000, 25)
			.unit("ms")).visibleWhen(() -> delayMode.is("Random"));
	private final NumberSetting maxDelay = add(new NumberSetting("Max Delay", "Longest random delay", 1200, 0, 3000, 25)
			.unit("ms")).visibleWhen(() -> delayMode.is("Random"));
	private final ModeSetting targeting = add(new ModeSetting("Targeting", "Who gets the next charge", "Closest",
			"Closest", "Random", "Rotate"));
	private final ModeSetting aim = add(new ModeSetting("Aim", "Hit them directly or burst at their feet", "Feet", "Feet", "Body"));
	private final BoolSetting predict = add(new BoolSetting("Predict", "Lead moving targets", true));
	private final BoolSetting requireSight = add(new BoolSetting("Line Of Sight", "Only throw when nothing is in the way", true));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Return to your previous hotbar slot", true));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Leave friends alone", true));

	private final Delay timer = new Delay();
	private long nextDelay;
	private int rotateIndex;
	private Player aiming;
	private int thrown;

	public WindAnnoy() {
		super("WindAnnoy", "Throws wind charges at every player within a radius.", Category.TROLL);
	}

	@Override
	protected void onEnable() {
		thrown = 0;
		nextDelay = 0;
		aiming = null;
	}

	@Override
	public void onTick() {
		if (!timer.passed(nextDelay) || mc.player.isUsingItem()) {
			return;
		}
		InteractionHand hand;
		int slot = -1;
		if (mc.player.getOffhandItem().is(Items.WIND_CHARGE)) {
			hand = InteractionHand.OFF_HAND;
		} else {
			slot = InventoryUtil.findHotbar(Items.WIND_CHARGE);
			if (slot < 0) {
				return;
			}
			hand = InteractionHand.MAIN_HAND;
		}
		ItemStack stack = hand == InteractionHand.OFF_HAND ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(slot);
		if (mc.player.getCooldowns().isOnCooldown(stack)) {
			return;
		}

		List<Player> targets = Targets.playersWithin(range.get(), ignoreFriends.get());
		targets.removeIf(p -> requireSight.get() && !canSee(aimPoint(p)));
		if (targets.isEmpty()) {
			return;
		}
		// stick with the player we're turning towards until the charge is away
		Player target = aiming != null && targets.contains(aiming) ? aiming : switch (targeting.get()) {
			case "Random" -> targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
			case "Rotate" -> targets.get(Math.floorMod(rotateIndex++, targets.size()));
			default -> targets.get(0);
		};
		aiming = target;
		float[] rot = Rotations.lookAt(aimPoint(target));
		Rotations.request(rot[0], rot[1], 8, true);
		// the charge flies along the look the server already has: turn first, throw on a later tick
		if (!Rotations.isFacing(rot[0], rot[1], 2f)) {
			return;
		}

		throwAt(hand, slot);
		aiming = null;
		timer.reset();
		nextDelay = Delay.roll(delayMode, delay, minDelay, maxDelay);
	}

	private Vec3 aimPoint(Player target) {
		Vec3 base = aim.is("Feet") ? target.position().add(0, 0.15, 0) : target.position().add(0, target.getBbHeight() * 0.5, 0);
		if (!predict.get()) {
			return base;
		}
		Vec3 velocity = new Vec3(target.getX() - target.xo, target.getY() - target.yo, target.getZ() - target.zo);
		double ticks = mc.player.getEyePosition().distanceTo(base) / CHARGE_SPEED;
		// ignore vertical velocity: jumping targets land again before the charge arrives
		return base.add(velocity.x * ticks, 0, velocity.z * ticks);
	}

	private boolean canSee(Vec3 point) {
		Vec3 eyes = mc.player.getEyePosition();
		HitResult hit = mc.level.clip(new ClipContext(eyes, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
		return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(point) < 1.0;
	}

	private void throwAt(InteractionHand hand, int slot) {
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
