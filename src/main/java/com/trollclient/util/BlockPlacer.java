package com.trollclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CartographyTableBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.List;

/** Finds a legal way to place a block at a position and does it. */
public final class BlockPlacer {
	public record Placement(BlockPos pos, BlockPos clicked, Direction face, Vec3 hit, boolean airPlace) {
	}

	private BlockPlacer() {
	}

	public static double reach() {
		return Minecraft.getInstance().player.blockInteractionRange();
	}

	/** True if a block could legally occupy {@code pos} right now (ignores reach). */
	public static boolean isFree(BlockPos pos) {
		Level level = Minecraft.getInstance().level;
		if (!level.isLoaded(pos) || pos.getY() < level.getMinY() || pos.getY() >= level.getMaxY()) {
			return false;
		}
		if (!level.getBlockState(pos).canBeReplaced()) {
			return false;
		}
		List<Entity> blockers = level.getEntities((Entity) null, new AABB(pos),
				e -> e.isAlive() && e.blocksBuilding && !e.isSpectator());
		return blockers.isEmpty();
	}

	public static Placement find(BlockPos pos, boolean allowAirPlace) {
		return find(pos, allowAirPlace, reach());
	}

	public static Placement find(BlockPos pos, boolean allowAirPlace, double reach) {
		Minecraft mc = Minecraft.getInstance();
		if (!isFree(pos)) {
			return null;
		}
		Level level = mc.level;
		Vec3 eyes = mc.player.getEyePosition();
		Placement best = null;
		double bestDist = Double.MAX_VALUE;
		for (Direction dir : Direction.values()) {
			BlockPos neighbor = pos.relative(dir);
			BlockState state = level.getBlockState(neighbor);
			if (state.canBeReplaced() || state.getShape(level, neighbor).isEmpty() || isInteractable(state)) {
				continue;
			}
			Direction face = dir.getOpposite();
			// aim at the middle of the face we click, on the block's real shape (slabs, stairs...)
			AABB box = state.getShape(level, neighbor).bounds().move(neighbor);
			Vec3 hit = box.getCenter().add(face.getStepX() * box.getXsize() / 2, face.getStepY() * box.getYsize() / 2,
					face.getStepZ() * box.getZsize() / 2);
			// a real player can only click a face they can see the front of
			Vec3 toEyes = eyes.subtract(hit);
			if (toEyes.x * face.getStepX() + toEyes.y * face.getStepY() + toEyes.z * face.getStepZ() <= 0.01) {
				continue;
			}
			double dist = eyes.distanceTo(hit);
			if (dist <= reach && dist < bestDist) {
				bestDist = dist;
				best = new Placement(pos, neighbor, face, hit, false);
			}
		}
		if (best == null && allowAirPlace) {
			Vec3 center = Vec3.atCenterOf(pos);
			if (eyes.distanceTo(center) <= reach) {
				best = new Placement(pos, pos, Direction.UP, center, true);
			}
		}
		return best;
	}

	/**
	 * Places using the given hotbar slot.
	 *
	 * <p>With {@code rotate}, this asks for a rotation towards the block and
	 * only clicks once the server already has us looking at it, using exactly
	 * the spot and face that look ray hits. Until then it returns false: call
	 * it again next tick.</p>
	 *
	 * @return true if the server was asked to place the block
	 */
	public static boolean place(Placement placement, int slot, boolean rotate, boolean swapBack, int rotationPriority) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (slot < 0 || placement == null) {
			return false;
		}
		BlockHitResult hit = new BlockHitResult(placement.hit(), placement.face(), placement.clicked(), false);
		if (rotate) {
			float[] rot = Rotations.lookAt(placement.hit());
			BlockHitResult seen = serverView(placement);
			if (seen == null) {
				Rotations.request(rot[0], rot[1], rotationPriority, false);
				return false;
			}
			// keep looking here unless something else still needs aiming at
			Rotations.request(rot[0], rot[1], rotationPriority - 1, false);
			hit = seen;
		}
		int previous = InventoryUtil.selected();
		InventoryUtil.select(slot);
		InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		if (result.consumesAction()) {
			player.swing(InteractionHand.MAIN_HAND);
		}
		if (swapBack) {
			InventoryUtil.select(previous);
		}
		return result.consumesAction();
	}

	/** The click the server's idea of our view would make for this placement, or null if it isn't lined up yet. */
	private static BlockHitResult serverView(Placement placement) {
		Level level = Minecraft.getInstance().level;
		if (placement.airPlace()) {
			BlockHitResult hit = Rotations.serverRayHit(placement.pos(), Shapes.block(), reach());
			return hit == null ? null : new BlockHitResult(hit.getLocation(), placement.face(), placement.pos(), false);
		}
		BlockHitResult hit = Rotations.serverRayHit(placement.clicked(),
				level.getBlockState(placement.clicked()).getShape(level, placement.clicked()), reach());
		return hit != null && hit.getDirection() == placement.face() ? hit : null;
	}

	/** Blocks that open a menu or toggle when right-clicked; we never place against these. */
	public static boolean isInteractable(BlockState state) {
		if (state.hasBlockEntity()) {
			return true;
		}
		Block b = state.getBlock();
		return b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
				|| b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof CraftingTableBlock
				|| b instanceof AnvilBlock || b instanceof BedBlock || b instanceof NoteBlock
				|| b instanceof RepeaterBlock || b instanceof ComparatorBlock || b instanceof CakeBlock
				|| b instanceof SmithingTableBlock || b instanceof LoomBlock || b instanceof StonecutterBlock
				|| b instanceof GrindstoneBlock || b instanceof CartographyTableBlock || b instanceof BellBlock
				|| b instanceof DaylightDetectorBlock || b instanceof RespawnAnchorBlock;
	}
}
