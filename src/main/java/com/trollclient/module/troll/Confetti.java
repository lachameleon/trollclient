package com.trollclient.module.troll;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.pathing.Walkability;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.BlockPlacer;
import com.trollclient.util.Delay;
import com.trollclient.util.InventoryUtil;
import com.trollclient.util.Rotations;
import com.trollclient.util.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Sets off firework rockets at people's feet. Optionally only to celebrate a kill. */
public class Confetti extends Module {
	private static final byte DEATH = 3;

	private final ModeSetting mode = add(new ModeSetting("Mode", "When to launch", "Near Players",
			"Near Players", "On Kills", "Around Me"));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Time between rockets", 900, 100, 5000, 50).unit("ms"));
	private final NumberSetting volley = add(new NumberSetting("Volley", "Rockets per kill", 3, 1, 10, 1))
			.visibleWhen(() -> mode.is("On Kills"));
	private final NumberSetting radius = add(new NumberSetting("Radius", "React to kills within this distance", 16, 4, 64, 1)
			.unit("m")).visibleWhen(() -> mode.is("On Kills"));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Look where you launch", true));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Return to your previous hotbar slot", true));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Don't aim at friends", false));

	private final Delay timer = new Delay();
	private int pending;
	private Vec3 party;
	private int launched;

	public Confetti() {
		super("Confetti", "Launches firework rockets at players' feet, around you, or to celebrate a kill.", Category.TROLL);
	}

	@Override
	protected void onEnable() {
		pending = 0;
		launched = 0;
	}

	@Override
	public void onEntityEvent(Entity entity, byte event) {
		if (mode.is("On Kills") && event == DEATH && entity instanceof Player p && p != mc.player
				&& mc.player != null && p.distanceTo(mc.player) <= radius.get()) {
			pending = volley.getInt();
			party = p.position();
		}
	}

	@Override
	public void onTick() {
		if (!timer.passed(delay.getInt()) || mc.gui.screen() != null || mc.player.isUsingItem()) {
			return;
		}
		Vec3 spot = switch (mode.get()) {
			case "On Kills" -> pending > 0 ? party : null;
			case "Around Me" -> mc.player.position().add(ThreadLocalRandom.current().nextDouble(-3, 3), 0,
					ThreadLocalRandom.current().nextDouble(-3, 3));
			default -> {
				List<Player> near = Targets.playersWithin(BlockPlacer.reach() + 3, ignoreFriends.get());
				yield near.isEmpty() ? null : near.get(0).position();
			}
		};
		if (spot == null) {
			return;
		}
		BlockHitResult hit = launchSpot(spot);
		if (hit == null) {
			return;
		}
		InteractionHand hand;
		int slot = -1;
		if (mc.player.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
			hand = InteractionHand.OFF_HAND;
		} else {
			slot = InventoryUtil.findHotbar(Items.FIREWORK_ROCKET);
			if (slot < 0) {
				return;
			}
			hand = InteractionHand.MAIN_HAND;
		}
		if (rotate.get()) {
			// look at the spot first; launch once the server has seen us looking at it
			float[] look = Rotations.lookAt(hit.getLocation());
			BlockHitResult seen = Rotations.serverRayHit(hit.getBlockPos(),
					mc.level.getBlockState(hit.getBlockPos()).getShape(mc.level, hit.getBlockPos()), BlockPlacer.reach());
			Rotations.request(look[0], look[1], 15, false);
			if (seen == null || seen.getDirection() != Direction.UP) {
				return;
			}
			hit = seen;
		}
		int previous = InventoryUtil.selected();
		if (slot >= 0) {
			InventoryUtil.select(slot);
		}
		if (mc.gameMode.useItemOn(mc.player, hand, hit).consumesAction()) {
			mc.player.swing(hand);
			launched++;
			if (pending > 0) {
				pending--;
			}
		}
		if (swapBack.get() && slot >= 0) {
			InventoryUtil.select(previous);
		}
		timer.reset();
	}

	/** Top face of the floor block nearest to {@code spot} that's still within reach. */
	private BlockHitResult launchSpot(Vec3 spot) {
		Vec3 eyes = mc.player.getEyePosition();
		double reach = BlockPlacer.reach() - 0.3;
		// walk from the spot back towards us until the floor is close enough to click
		Vec3 toMe = eyes.subtract(spot);
		for (int i = 0; i <= 12; i++) {
			Vec3 p = spot.add(toMe.scale(i / 12.0));
			BlockPos column = BlockPos.containing(p.x, spot.y + 0.5, p.z);
			for (int dy = 0; dy <= 3; dy++) {
				BlockPos floor = column.below(dy);
				if (Walkability.isFloor(mc.level, floor) && !BlockPlacer.isInteractable(mc.level.getBlockState(floor))
						&& mc.level.getBlockState(floor.above()).canBeReplaced()) {
					Vec3 top = Vec3.atCenterOf(floor).add(0, 0.5, 0);
					if (top.distanceTo(eyes) <= reach) {
						return new BlockHitResult(top, Direction.UP, floor, false);
					}
					break;
				}
			}
		}
		return null;
	}

	@Override
	public String getInfo() {
		return launched > 0 ? Integer.toString(launched) : null;
	}
}
