package com.trollclient.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Block-level questions the pathfinder and dodge logic ask about the world. */
public final class Walkability {
	private Walkability() {
	}

	/** Things that hurt, slow you to a crawl, or kill you. */
	public static boolean isDangerous(BlockState state) {
		Block b = state.getBlock();
		if (state.getFluidState().is(FluidTags.LAVA)) {
			return true;
		}
		return b instanceof BaseFireBlock || b instanceof CampfireBlock || b instanceof MagmaBlock
				|| b instanceof CactusBlock || b instanceof SweetBerryBushBlock || b instanceof PowderSnowBlock
				|| b instanceof WebBlock || b == Blocks.WITHER_ROSE || b == Blocks.POINTED_DRIPSTONE;
	}

	/** The player's body can occupy this block. */
	public static boolean isPassable(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (isDangerous(state)) {
			return false;
		}
		return state.getCollisionShape(level, pos).isEmpty();
	}

	/** The block can be stood on top of (full-ish top surface, not a fence). */
	public static boolean isFloor(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (isDangerous(state)) {
			return false;
		}
		VoxelShape shape = state.getCollisionShape(level, pos);
		if (shape.isEmpty()) {
			return false;
		}
		double top = shape.max(Direction.Axis.Y);
		return top >= 0.5 && top <= 1.0;
	}

	public static boolean isWater(Level level, BlockPos pos) {
		return level.getBlockState(pos).getFluidState().is(FluidTags.WATER);
	}

	/** Feet can stand at {@code pos}: two free blocks above a floor. */
	public static boolean isStandable(Level level, BlockPos pos) {
		return level.isLoaded(pos) && isFloor(level, pos.below()) && isPassable(level, pos) && isPassable(level, pos.above());
	}

	/** Finds the nearest standable Y in a column around {@code y}, or Integer.MIN_VALUE. */
	public static int findStandableY(Level level, int x, int y, int z, int up, int down) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int d = 0; d <= Math.max(up, down); d++) {
			if (d <= up && isStandable(level, m.set(x, y + d, z))) {
				return y + d;
			}
			if (d > 0 && d <= down && isStandable(level, m.set(x, y - d, z))) {
				return y - d;
			}
		}
		return Integer.MIN_VALUE;
	}
}
