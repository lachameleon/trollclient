package com.trollclient.module.troll;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.BlockPlacer;
import com.trollclient.util.Delay;
import com.trollclient.util.InventoryUtil;
import com.trollclient.util.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class NoWayHome extends Module {
	private final NumberSetting range = add(new NumberSetting("Range", "Pick on players within this distance", 7, 2, 12, 0.5)
			.unit("m"));
	private final NumberSetting lookAhead = add(new NumberSetting("Look Ahead", "How far along their view to try placing", 5, 1, 8, 0.5)
			.unit("m"));
	private final NumberSetting wallHeight = add(new NumberSetting("Wall Height", "1 blocks feet, 2 means they can't jump it", 2, 1, 2, 1)
			.unit("b"));
	private final BoolSetting useLook = add(new BoolSetting("Follow Pitch", "Also try blocks along their actual look ray", true));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Time between placements", 80, 0, 1000, 10)
			.unit("ms"));
	private final NumberSetting perTick = add(new NumberSetting("Blocks Per Tick", "Placements allowed per tick", 1, 1, 4, 1));
	private final ModeSetting material = add(new ModeSetting("Material", "Which block to use", "Cheapest", "Cheapest", "Obsidian"));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Look at the block while placing (server side)", true));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Return to your previous hotbar slot", true));
	private final BoolSetting airPlace = add(new BoolSetting("Air Place", "Place without a supporting block (some servers reject this)", false));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Leave friends alone", true));

	private final Delay timer = new Delay();
	private int placedTotal;
	private String lastVictim;

	public NoWayHome() {
		super("NoWayHome", "Puts a block in front of a player in every direction they face.", Category.TROLL);
	}

	@Override
	protected void onEnable() {
		placedTotal = 0;
		lastVictim = null;
	}

	@Override
	public void onTick() {
		if (!timer.passed(delay.getInt())) {
			return;
		}
		int placed = 0;
		for (Player target : Targets.playersWithin(range.get() + lookAhead.get(), ignoreFriends.get())) {
			while (placed < perTick.getInt()) {
				int slot = material.is("Obsidian") ? InventoryUtil.findHotbar(Items.OBSIDIAN) : InventoryUtil.cheapestBlockSlot();
				if (slot < 0) {
					return;
				}
				BlockPlacer.Placement placement = nextPlacement(target);
				if (placement == null || !BlockPlacer.place(placement, slot, rotate.get(), swapBack.get(), 40)) {
					break;
				}
				placed++;
				placedTotal++;
				lastVictim = Targets.name(target);
			}
			if (placed >= perTick.getInt()) {
				break;
			}
		}
		if (placed > 0) {
			timer.reset();
		}
	}

	/**
	 * Walks along the direction the target faces, at their own Y level first,
	 * and returns the first block in their path that we can actually place.
	 * Columns that are already walled up stop the search: they're stuck.
	 */
	private BlockPlacer.Placement nextPlacement(Player target) {
		for (BlockPos pos : candidates(target)) {
			BlockPlacer.Placement placement = BlockPlacer.find(pos, airPlace.get());
			if (placement != null) {
				return placement;
			}
		}
		return null;
	}

	private List<BlockPos> candidates(Player target) {
		Set<BlockPos> out = new LinkedHashSet<>();
		AABB body = target.getBoundingBox();
		AABB self = mc.player.getBoundingBox();
		Vec3 feet = target.position();
		int y = Mth.floor(feet.y + 0.01);
		float yawRad = target.getYRot() * Mth.DEG_TO_RAD;
		Vec3 dir = new Vec3(-Mth.sin(yawRad), 0, Mth.cos(yawRad));

		boolean blockedColumn = false;
		BlockPos lastColumn = null;
		for (double t = 0.35; t <= lookAhead.get() && !blockedColumn; t += 0.25) {
			Vec3 p = feet.add(dir.scale(t));
			BlockPos column = new BlockPos(Mth.floor(p.x), y, Mth.floor(p.z));
			if (column.equals(lastColumn) || body.intersects(new AABB(column))) {
				continue;
			}
			lastColumn = column;
			boolean feetSolid = !mc.level.getBlockState(column).canBeReplaced();
			boolean headSolid = !mc.level.getBlockState(column.above()).canBeReplaced();
			if (feetSolid && (headSolid || wallHeight.getInt() < 2)) {
				blockedColumn = true; // already a wall in front of them
				continue;
			}
			if (!self.intersects(new AABB(column))) {
				out.add(column);
			}
			if (wallHeight.getInt() >= 2 && !self.intersects(new AABB(column.above()))) {
				out.add(column.above());
			}
		}

		if (useLook.get()) {
			Vec3 eye = target.getEyePosition();
			Vec3 look = target.getViewVector(1f);
			for (double t = 0.5; t <= lookAhead.get(); t += 0.25) {
				BlockPos pos = BlockPos.containing(eye.add(look.scale(t)));
				if (!body.intersects(new AABB(pos)) && !self.intersects(new AABB(pos))) {
					out.add(pos);
				}
			}
		}
		return new ArrayList<>(out);
	}

	@Override
	public String getInfo() {
		return lastVictim == null ? null : lastVictim + " " + placedTotal;
	}
}
