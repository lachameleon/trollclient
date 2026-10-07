package com.trollclient.module.combat;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.BlockPlacer;
import com.trollclient.util.Delay;
import com.trollclient.util.InventoryUtil;
import com.trollclient.util.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/** Boxes a player in with your cheapest blocks the moment they stop moving. */
public class Trapper extends Module {
	private final TextSetting target = add(new TextSetting("Target", "Player to trap (blank = nearest)", "", 16)
			.suggestions(Targets::onlineNames).placeholder("nearest"));
	private final ModeSetting shape = add(new ModeSetting("Shape", "Full box, just the walls, or a lid over their head",
			"Full", "Full", "Walls", "Lid"));
	private final BoolSetting onlyStill = add(new BoolSetting("Only When Still", "Wait until they stop moving", true));
	private final NumberSetting stillTicks = add(new NumberSetting("Still For", "How long they must stand still", 10, 1, 60, 1)
			.unit("t")).visibleWhen(onlyStill::get);
	private final NumberSetting perTick = add(new NumberSetting("Blocks Per Tick", "Placements per tick", 2, 1, 6, 1));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Pause between rounds of placing", 50, 0, 1000, 25)
			.unit("ms"));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Look at each block as you place it", true));
	private final BoolSetting airPlace = add(new BoolSetting("Air Place", "Place without a supporting block (some servers block this)", false));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Return to your previous hotbar slot", true));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Never trap friends", true));

	private final Delay timer = new Delay();
	private Player current;
	private BlockPos lastFeet;
	private int still;
	private int placed;

	public Trapper() {
		super("Trapper", "Encases a standing-still player in your cheapest blocks: walls, head and lid.", Category.COMBAT);
	}

	@Override
	protected void onEnable() {
		placed = 0;
		still = 0;
		lastFeet = null;
	}

	@Override
	public void onWorldLeave() {
		current = null;
	}

	@Override
	public void onTick() {
		Player p = Targets.resolve(target.get(), BlockPlacer.reach() + 2, ignoreFriends.get());
		if (p != current) {
			current = p;
			still = 0;
			lastFeet = null;
		}
		if (p == null) {
			return;
		}
		BlockPos feet = BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
		double speed = Math.hypot(p.getX() - p.xo, p.getZ() - p.zo);
		still = feet.equals(lastFeet) && speed < 0.03 ? still + 1 : 0;
		lastFeet = feet;
		if (onlyStill.get() && still < stillTicks.getInt()) {
			return;
		}
		if (!timer.passed(delay.getInt())) {
			return;
		}
		int slot = InventoryUtil.cheapestBlockSlot();
		if (slot < 0) {
			return;
		}
		int budget = perTick.getInt();
		for (BlockPos pos : plan(feet)) {
			if (budget <= 0) {
				break;
			}
			BlockPlacer.Placement placement = BlockPlacer.find(pos, airPlace.get());
			if (placement != null && BlockPlacer.place(placement, slot, rotate.get(), swapBack.get(), 30)) {
				placed++;
				budget--;
			}
		}
		if (budget < perTick.getInt()) {
			timer.reset();
		}
	}

	/** Bottom-up order so each layer gives the next something to stand on. */
	private List<BlockPos> plan(BlockPos feet) {
		List<BlockPos> list = new ArrayList<>();
		boolean walls = !shape.is("Lid");
		if (walls) {
			for (Direction d : Direction.Plane.HORIZONTAL) {
				list.add(feet.relative(d));
			}
			for (Direction d : Direction.Plane.HORIZONTAL) {
				list.add(feet.above().relative(d));
			}
		}
		if (!shape.is("Walls")) {
			list.add(feet.above(2));
		}
		list.removeIf(pos -> !BlockPlacer.isFree(pos));
		return list;
	}

	@Override
	public Player getTarget() {
		return current;
	}

	@Override
	public String getInfo() {
		return current == null ? null : Targets.name(current) + (placed > 0 ? " " + placed : "");
	}
}
