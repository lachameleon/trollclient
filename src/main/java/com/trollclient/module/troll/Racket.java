package com.trollclient.module.troll;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.BlockPlacer;
import com.trollclient.util.Rotations;
import com.trollclient.util.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/** Flaps every door, gate, lever, bell and note block in reach. Mostly near other people. */
public class Racket extends Module {
	private final BoolSetting doors = add(new BoolSetting("Doors", "Wooden and copper doors", true));
	private final BoolSetting trapdoors = add(new BoolSetting("Trapdoors", "Wooden and copper trapdoors", true));
	private final BoolSetting gates = add(new BoolSetting("Fence Gates", "Every kind of fence gate", true));
	private final BoolSetting levers = add(new BoolSetting("Levers", "Click click click", true));
	private final BoolSetting buttons = add(new BoolSetting("Buttons", "Press every button", false));
	private final BoolSetting bells = add(new BoolSetting("Bells", "Ring every bell", true));
	private final BoolSetting noteBlocks = add(new BoolSetting("Note Blocks", "Plays them (and retunes them, sorry)", false));
	private final NumberSetting speed = add(new NumberSetting("Speed", "Clicks per second", 6, 1, 20, 1).unit("/s"));
	private final BoolSetting nearPlayers = add(new BoolSetting("Near Players", "Only use blocks close to another player", true));
	private final NumberSetting playerRadius = add(new NumberSetting("Player Radius", "How close to a player the block must be", 8, 2, 32, 1)
			.unit("m")).visibleWhen(nearPlayers::get);
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Look at each block as you click it", true));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Don't bother friends", true));

	private final List<BlockPos> candidates = new ArrayList<>();
	private int scanCooldown;
	private int cursor;
	private int aimTicks;
	private float budget;

	public Racket() {
		super("Racket", "Spams doors, trapdoors, gates, levers, bells and note blocks around other players.", Category.TROLL);
	}

	@Override
	protected void onEnable() {
		candidates.clear();
		scanCooldown = 0;
		budget = 0;
	}

	@Override
	public void onWorldLeave() {
		candidates.clear();
	}

	@Override
	public void onTick() {
		if (mc.gui.screen() != null) {
			return;
		}
		if (--scanCooldown <= 0) {
			scan();
			scanCooldown = 10;
		}
		if (candidates.isEmpty()) {
			budget = 0;
			return;
		}
		// sneaking with something in hand skips block interactions entirely
		if (mc.player.isSecondaryUseActive() && !(mc.player.getMainHandItem().isEmpty() && mc.player.getOffhandItem().isEmpty())) {
			return;
		}
		budget = Math.min(budget + speed.getFloat() / 20f, 4);
		while (!candidates.isEmpty()) {
			cursor %= candidates.size();
			BlockPos pos = candidates.get(cursor);
			// with Rotate, also turn towards the next block while we wait for the budget
			Click result = click(pos, budget >= 1);
			if (result == Click.GONE) {
				candidates.remove(cursor);
				aimTicks = 0;
				continue;
			}
			if (result == Click.READY) {
				aimTicks = 0;
				break;
			}
			if (result == Click.AIMING) {
				// can't line up on it (hidden face, odd shape): try another one
				if (++aimTicks > 10) {
					aimTicks = 0;
					cursor++;
				}
				break;
			}
			budget -= 1;
			aimTicks = 0;
			cursor++;
		}
	}

	private void scan() {
		candidates.clear();
		List<Player> players = nearPlayers.get() ? Targets.playersWithin(BlockPlacer.reach() + playerRadius.get() + 2, ignoreFriends.get()) : List.of();
		if (nearPlayers.get() && players.isEmpty()) {
			return;
		}
		Vec3 eyes = mc.player.getEyePosition();
		double reach = BlockPlacer.reach() - 0.25;
		int r = (int) Math.ceil(reach);
		BlockPos center = BlockPos.containing(eyes);
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
			if (Vec3.atCenterOf(pos).distanceTo(eyes) > reach) {
				continue;
			}
			BlockState state = mc.level.getBlockState(pos);
			if (!wanted(state, pos)) {
				continue;
			}
			// the top half of a door is the same door; only keep the bottom
			if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
				continue;
			}
			if (nearPlayers.get() && !nearAny(pos, players)) {
				continue;
			}
			candidates.add(pos.immutable());
		}
	}

	private boolean nearAny(BlockPos pos, List<Player> players) {
		Vec3 c = Vec3.atCenterOf(pos);
		double r = playerRadius.get();
		for (Player p : players) {
			if (p.position().distanceTo(c) <= r) {
				return true;
			}
		}
		return false;
	}

	private boolean wanted(BlockState state, BlockPos pos) {
		Block b = state.getBlock();
		if (b instanceof DoorBlock door) {
			return doors.get() && door.type().canOpenByHand();
		}
		if (b instanceof TrapDoorBlock) {
			return trapdoors.get() && b != Blocks.IRON_TRAPDOOR;
		}
		if (b instanceof FenceGateBlock) {
			return gates.get();
		}
		if (b instanceof LeverBlock) {
			return levers.get();
		}
		if (b instanceof ButtonBlock) {
			return buttons.get();
		}
		if (b instanceof BellBlock) {
			return bells.get();
		}
		// note blocks only sound with air above them
		return b instanceof NoteBlock && noteBlocks.get() && mc.level.getBlockState(pos.above()).isAir();
	}

	/**
	 * Clicks {@code pos} if {@code act}. With Rotate, only once the server has
	 * seen us looking at the right face, and on exactly the spot that look hits.
	 */
	private Click click(BlockPos pos, boolean act) {
		BlockState state = mc.level.getBlockState(pos);
		if (!wanted(state, pos) || Vec3.atCenterOf(pos).distanceTo(mc.player.getEyePosition()) > BlockPlacer.reach()) {
			return Click.GONE;
		}
		BlockHitResult hit = hitFor(pos, state);
		if (rotate.get()) {
			float[] look = Rotations.lookAt(hit.getLocation());
			Rotations.request(look[0], look[1], 20, false);
			BlockHitResult seen = Rotations.serverRayHit(pos, state.getShape(mc.level, pos), BlockPlacer.reach());
			boolean lined = seen != null && seen.getDirection() == hit.getDirection();
			if (lined && state.getBlock() instanceof BellBlock) {
				// the crown doesn't ring
				lined = seen.getLocation().y - pos.getY() < 0.8;
			}
			if (!lined) {
				return Click.AIMING;
			}
			hit = seen;
		}
		if (!act) {
			return Click.READY;
		}
		if (mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
			mc.player.swing(InteractionHand.MAIN_HAND);
		}
		return Click.DONE;
	}

	private enum Click {
		DONE, READY, AIMING, GONE
	}

	private BlockHitResult hitFor(BlockPos pos, BlockState state) {
		Vec3 center = Vec3.atCenterOf(pos);
		Vec3 eyes = mc.player.getEyePosition();
		Direction face = Direction.getApproximateNearest(eyes.subtract(center));
		if (state.getBlock() instanceof BellBlock) {
			// bells only ring when struck on the right side, below the crown
			Direction facing = state.getValue(BellBlock.FACING);
			BellAttachType attach = state.getValue(BellBlock.ATTACHMENT);
			Direction.Axis axis = attach == BellAttachType.FLOOR ? facing.getAxis()
					: attach == BellAttachType.CEILING ? face.getAxis() : facing.getClockWise().getAxis();
			if (!axis.isHorizontal()) {
				axis = Direction.Axis.X;
			}
			Direction a = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
			face = eyes.subtract(center).dot(Vec3.atLowerCornerOf(a.getUnitVec3i())) >= 0 ? a : a.getOpposite();
			Vec3 at = center.add(face.getStepX() * 0.5, -0.2, face.getStepZ() * 0.5);
			return new BlockHitResult(at, face, pos, false);
		}
		// middle of the face on the block's real shape (doors and trapdoors are thin)
		VoxelShape shape = state.getShape(mc.level, pos);
		AABB box = shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
		face = Direction.getApproximateNearest(eyes.subtract(box.getCenter()));
		Vec3 at = box.getCenter().add(face.getStepX() * box.getXsize() / 2, face.getStepY() * box.getYsize() / 2,
				face.getStepZ() * box.getZsize() / 2);
		return new BlockHitResult(at, face, pos, false);
	}

	@Override
	public String getInfo() {
		return candidates.isEmpty() ? null : Integer.toString(candidates.size());
	}
}
