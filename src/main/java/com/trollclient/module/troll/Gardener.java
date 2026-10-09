package com.trollclient.module.troll;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Bone meals the ground around people so grass and flowers burst up wherever they stand. */
public class Gardener extends Module {
	private final ModeSetting mode = add(new ModeSetting("Mode", "Whose feet the flowers go round", "Near Players",
			"Near Players", "Around Me"));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Time between bone meals", 400, 100, 3000, 50).unit("ms"));
	private final NumberSetting spread = add(new NumberSetting("Spread", "How far from their feet to fertilise", 2, 0, 4, 1).unit("m"));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Look at the ground you fertilise", true));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Return to your previous hotbar slot", true));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Leave friends' lawns alone", false));

	private final Delay timer = new Delay();
	private BlockPos aimed;
	private Player current;
	private int fed;

	public Gardener() {
		super("Gardener", "Bone meals the grass around nearby players so flowers spring up wherever they stand.", Category.TROLL);
	}

	@Override
	protected void onEnable() {
		fed = 0;
		aimed = null;
	}

	@Override
	public void onWorldLeave() {
		aimed = null;
		current = null;
	}

	@Override
	public void onTick() {
		current = null;
		Vec3 centre;
		if (mode.is("Around Me")) {
			centre = mc.player.position();
		} else {
			List<Player> near = Targets.playersWithin(BlockPlacer.reach() + spread.get() + 1, ignoreFriends.get());
			if (near.isEmpty()) {
				return;
			}
			current = near.get(0);
			centre = current.position();
		}
		if (!timer.passed(delay.getInt()) || mc.gui.screen() != null || mc.player.isUsingItem()) {
			return;
		}
		InteractionHand hand;
		int slot = -1;
		if (mc.player.getOffhandItem().is(Items.BONE_MEAL)) {
			hand = InteractionHand.OFF_HAND;
		} else {
			slot = InventoryUtil.findHotbar(Items.BONE_MEAL);
			if (slot < 0) {
				return;
			}
			hand = InteractionHand.MAIN_HAND;
		}
		// stick with the patch we're already turning towards, so we don't twitch between spots
		BlockPos ground = aimed != null && usable(aimed) && reachable(aimed) && near(aimed, centre) ? aimed : pick(centre);
		aimed = ground;
		if (ground == null) {
			return;
		}
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false);
		if (rotate.get()) {
			// look at it first; fertilise once the server has seen us looking at the top face
			float[] look = Rotations.lookAt(hit.getLocation());
			Rotations.request(look[0], look[1], 14, false);
			BlockHitResult seen = Rotations.serverRayHit(ground, mc.level.getBlockState(ground).getShape(mc.level, ground),
					BlockPlacer.reach());
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
			fed++;
		}
		if (swapBack.get() && slot >= 0) {
			InventoryUtil.select(previous);
		}
		aimed = null;
		timer.reset();
	}

	/** A random patch of grass (or moss, or nylium) round {@code centre} that we can reach. */
	private BlockPos pick(Vec3 centre) {
		int r = spread.getInt();
		BlockPos feet = BlockPos.containing(centre);
		List<BlockPos> options = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = -2; dy <= 1; dy++) {
					BlockPos pos = feet.offset(dx, dy, dz);
					if (usable(pos) && reachable(pos)) {
						options.add(pos);
					}
				}
			}
		}
		return options.isEmpty() ? null : options.get(ThreadLocalRandom.current().nextInt(options.size()));
	}

	/** Ground that sprouts plants when bone mealed, with room above it for them to grow. */
	private boolean usable(BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		boolean ground = state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MOSS_BLOCK)
				|| state.is(Blocks.CRIMSON_NYLIUM) || state.is(Blocks.WARPED_NYLIUM);
		return ground && mc.level.getBlockState(pos.above()).isAir();
	}

	private boolean reachable(BlockPos pos) {
		return Vec3.atCenterOf(pos).add(0, 0.5, 0).distanceTo(mc.player.getEyePosition()) <= BlockPlacer.reach() - 0.3;
	}

	private boolean near(BlockPos pos, Vec3 centre) {
		double r = spread.get() + 1;
		return Math.abs(pos.getX() + 0.5 - centre.x) <= r && Math.abs(pos.getZ() + 0.5 - centre.z) <= r;
	}

	@Override
	public Player getTarget() {
		return current;
	}

	@Override
	public String getInfo() {
		return fed > 0 ? Integer.toString(fed) : null;
	}
}
