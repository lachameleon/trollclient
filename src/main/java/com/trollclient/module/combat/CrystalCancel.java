package com.trollclient.module.combat;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.BlockPlacer;
import com.trollclient.util.Delay;
import com.trollclient.util.InventoryUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class CrystalCancel extends Module {
	private final NumberSetting range = add(new NumberSetting("Range", "Only react to crystals this close to you", 8, 3, 14, 0.5)
			.unit("m"));
	private final ModeSetting delayMode = add(new ModeSetting("Delay Mode", "Fixed delay, or a random one between min and max",
			"Fixed", "Fixed", "Random"));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Wait this long after a crystal appears", 0, 0, 1000, 5)
			.unit("ms")).visibleWhen(() -> delayMode.is("Fixed"));
	private final NumberSetting minDelay = add(new NumberSetting("Min Delay", "Shortest random delay", 20, 0, 1000, 5)
			.unit("ms")).visibleWhen(() -> delayMode.is("Random"));
	private final NumberSetting maxDelay = add(new NumberSetting("Max Delay", "Longest random delay", 120, 0, 1000, 5)
			.unit("ms")).visibleWhen(() -> delayMode.is("Random"));
	private final NumberSetting blocks = add(new NumberSetting("Blocks", "Blocks placed per crystal (more = more cover)", 2, 1, 4, 1));
	private final BoolSetting ignoreOwn = add(new BoolSetting("Ignore Own", "Don't wall off crystals you placed yourself", true));
	private final BoolSetting requireEnemy = add(new BoolSetting("Require Enemy", "Only react if another player is near the crystal", true));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Look at the block while placing (server side)", true));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Return to your previous hotbar slot", true));
	private final BoolSetting airPlace = add(new BoolSetting("Air Place", "Place without a supporting block (some servers reject this)", false));

	/** A crystal to wall off; with Rotate it can take a few ticks while we turn to each block. */
	private static final class Pending {
		final int entityId;
		final long executeAt;
		int placed;
		boolean counted;

		Pending(int entityId, long executeAt) {
			this.entityId = entityId;
			this.executeAt = executeAt;
		}
	}

	/** Give up on a crystal this long after we started on it. */
	private static final long GIVE_UP_MS = 1000;

	private record OwnPlacement(Vec3 pos, long time) {
	}

	private final List<Pending> pending = new ArrayList<>();
	private final Deque<OwnPlacement> own = new ArrayDeque<>();
	private int cancelled;

	public CrystalCancel() {
		super("CrystalCancel", "Walls off every crystal other players place using the cheapest block in your hotbar.", Category.COMBAT);
	}

	@Override
	protected void onEnable() {
		pending.clear();
		own.clear();
		cancelled = 0;
	}

	@Override
	public void onWorldLeave() {
		pending.clear();
		own.clear();
	}

	@Override
	public void onEntityAdded(Entity entity) {
		if (!(entity instanceof EndCrystal) || mc.player == null) {
			return;
		}
		if (entity.distanceTo(mc.player) > range.get()) {
			return;
		}
		if (ignoreOwn.get() && isOwn(entity.position())) {
			return;
		}
		if (requireEnemy.get() && !enemyNear(entity)) {
			return;
		}
		long wait = Delay.roll(delayMode, delay, minDelay, maxDelay);
		Pending job = new Pending(entity.getId(), System.currentTimeMillis() + wait);
		// zero delay: react inside the spawn packet itself if we're already looking the right way
		if (wait <= 0 && inGame() && work(job, (EndCrystal) entity)) {
			return;
		}
		pending.add(job);
	}

	@Override
	public void onTick() {
		trackOwnPlacements();
		long now = System.currentTimeMillis();
		Iterator<Pending> it = pending.iterator();
		while (it.hasNext()) {
			Pending p = it.next();
			if (now < p.executeAt) {
				continue;
			}
			Entity e = mc.level.getEntity(p.entityId);
			if (!(e instanceof EndCrystal crystal) || !crystal.isAlive() || now > p.executeAt + GIVE_UP_MS || work(p, crystal)) {
				it.remove();
			}
		}
	}

	/** One tick of walling off a crystal. Returns true once there's nothing left to do. */
	private boolean work(Pending job, EndCrystal crystal) {
		int placed = wall(crystal, blocks.getInt() - job.placed);
		job.placed += placed;
		if (job.placed > 0 && !job.counted) {
			job.counted = true;
			cancelled++;
		}
		return job.placed >= blocks.getInt() || (placed == 0 && !rotate.get());
	}

	/**
	 * Places up to {@code max} blocks on the line between the crystal and our
	 * body. With Rotate, blocks we aren't looking at yet are aimed at for the
	 * next tick instead.
	 */
	private int wall(EndCrystal crystal, int max) {
		int placed = 0;
		for (BlockPos pos : candidates(crystal)) {
			if (placed >= max) {
				break;
			}
			int slot = InventoryUtil.cheapestBlockSlot();
			if (slot < 0) {
				break;
			}
			BlockPlacer.Placement placement = BlockPlacer.find(pos, airPlace.get());
			if (placement != null && BlockPlacer.place(placement, slot, rotate.get(), swapBack.get(), 50)) {
				placed++;
			}
		}
		return placed;
	}

	/**
	 * Free block positions between the explosion origin and the player's body,
	 * closest to the crystal first (a block there shades the most of us).
	 */
	private List<BlockPos> candidates(EndCrystal crystal) {
		Set<BlockPos> out = new LinkedHashSet<>();
		Vec3 origin = crystal.position();
		AABB self = mc.player.getBoundingBox();
		double[] heights = {0.3, 1.0, 1.6};
		for (double h : heights) {
			Vec3 target = mc.player.position().add(0, h, 0);
			Vec3 dir = target.subtract(origin);
			double len = dir.length();
			if (len < 0.5) {
				continue;
			}
			dir = dir.scale(1.0 / len);
			for (double t = 0.5; t < len; t += 0.2) {
				BlockPos pos = BlockPos.containing(origin.add(dir.scale(t)));
				if (!self.intersects(new AABB(pos)) && BlockPlacer.isFree(pos)) {
					out.add(pos);
				}
			}
		}
		// fall back to hugging the side of our own body that faces the crystal
		Vec3 flat = new Vec3(origin.x - mc.player.getX(), 0, origin.z - mc.player.getZ());
		if (flat.lengthSqr() > 1e-4) {
			flat = flat.normalize();
			BlockPos feet = BlockPos.containing(mc.player.position().add(flat.scale(0.9)));
			for (BlockPos pos : new BlockPos[]{feet, feet.above()}) {
				if (!self.intersects(new AABB(pos)) && BlockPlacer.isFree(pos)) {
					out.add(pos);
				}
			}
		}
		return new ArrayList<>(out);
	}

	private boolean enemyNear(Entity crystal) {
		for (Player p : mc.level.players()) {
			if (p != mc.player && !p.isSpectator() && p.distanceTo(crystal) < 7) {
				return true;
			}
		}
		return false;
	}

	/** Remembers where we just used an end crystal so our own don't trigger us. */
	private void trackOwnPlacements() {
		long now = System.currentTimeMillis();
		while (!own.isEmpty() && now - own.peekFirst().time() > 1500) {
			own.pollFirst();
		}
		boolean holding = mc.player.getMainHandItem().is(Items.END_CRYSTAL) || mc.player.getOffhandItem().is(Items.END_CRYSTAL);
		if (holding && mc.options.keyUse.isDown() && mc.hitResult != null) {
			own.addLast(new OwnPlacement(mc.hitResult.getLocation(), now));
		}
	}

	private boolean isOwn(Vec3 pos) {
		for (OwnPlacement o : own) {
			if (o.pos().distanceToSqr(pos) < 2.5 * 2.5) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String getInfo() {
		String timing = delayMode.is("Fixed") ? delay.display() : minDelay.getInt() + "-" + maxDelay.display();
		return cancelled > 0 ? timing + " | " + cancelled : timing;
	}
}
